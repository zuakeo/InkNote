package com.zuakeo.inknote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.ContentUris;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.MediaStore;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 后台转写：盯着收件箱，有网就把录音送去转文字，转完落成一条便签。
 *
 * 收件箱在应用私有目录 files/inbox：
 *   语音键用 root 直接写进来并 chown 成本应用 uid（不是 /sdcard，免得被 FUSE 挡住看不见）。
 *   所以这里不需要任何 root 轮询，普通文件读写即可。
 */
public class SyncService extends Service {
    public static final String TAG = "InkNote";
    public static final String ACTION_UPDATE = "com.zuakeo.inknote.UPDATE";
    public static final String ACTION_SYNC = "com.zuakeo.inknote.SYNC";
    private static final String CHANNEL = "ink_quiet";

    private volatile boolean running = true;
    private Thread loop;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "记事本后台", NotificationManager.IMPORTANCE_NONE);
            ch.setShowBadge(false);
            ch.enableVibration(false);
            ch.setSound(null, null);
            ch.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            nm.createNotificationChannel(ch);
        }
        startForeground(1, buildNotification("待命"));

        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null) {
                cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        new Thread(() -> {
                            try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
                            lastHotCheck = 0;      // 网络回来了，热词也立刻对一次
                            failCount = 0;         // 退避清零，别让刚恢复的网络还在等
                            nextTryAt = 0;
                            syncOnce();
                        }, "net-sync").start();
                    }
                });
            }
        } catch (Throwable t) {
            Log.e(TAG, "net callback", t);
        }

        loop = new Thread(() -> {
            while (running) {
                try {
                    syncOnce();
                } catch (Throwable t) {
                    Log.e(TAG, "sync", t);
                }
                try {
                    Thread.sleep(15000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "sync-loop");
        loop.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_SYNC.equals(intent.getAction())) {
            new Thread(this::syncOnce, "sync-now").start();
        }
        return START_STICKY;
    }

    private Notification buildNotification(String text) {
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("记事本")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_agenda)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void notifyState(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(1, buildNotification(text));
        } catch (Throwable ignored) { }
    }

    /** 收件箱：语音键往这里丢录音，也是待转写队列。 */
    public static File inboxDir(Context c) {
        File d = new File(c.getFilesDir(), "inbox");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 列表要显示「待转写」的地方。 */
    public static List<File> pendingDirs(Context c) {
        List<File> out = new ArrayList<>();
        out.add(inboxDir(c));
        // 语音键写私有收件箱失败时，会落到 /sdcard/InkNote/pending。
        // 以前这里不返回它，那些录音既不显示、也不会被转写，一直躺在公共目录里
        // （实测积压过 7 条，最老的躺了一天）。
        File pub = publicPendingDir();
        if (pub != null) out.add(pub);
        return out;
    }

    /** 公共兜底目录；拿不到外部存储时返回 null。 */
    public static File publicPendingDir() {
        try {
            File base = android.os.Environment.getExternalStorageDirectory();
            return base == null ? null : new File(base, "InkNote/pending");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 把公共兜底目录里能读的录音搬进私有收件箱，进入正常转写流程。 */
    private void importPublicPending(File inbox) {
        File pub = publicPendingDir();
        if (pub == null || !pub.isDirectory()) return;
        File[] fs = pub.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (!f.isFile() || !f.getName().endsWith(".m4a")) continue;
            File dst = new File(inbox, f.getName());
            try {
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.close();
                in.close();
                f.delete();
                Log.i(TAG, "从公共目录导入 " + f.getName());
            } catch (Throwable t) {
                // 读不到多半是权限（root 写的 660）。留给 root 侧清理，别反复重试。
                Log.w(TAG, "公共目录文件读不到：" + f.getName());
            }
        }
    }

    private long lastHotCheck = 0;
    /** 转写失败后的退避：30s → 1m → 2m → 5m → 15m，成功即清零 */
    private static final long[] BACKOFF = {30000, 60000, 120000};   // 最多等 2 分钟
    /** 短于这个秒数的录音直接丢弃（误按居多）。 */
    private static final int MIN_SEC = 3;
    private int failCount = 0;
    private long nextTryAt = 0;

    /**
     * 热词同步的兜底轮询：15 分钟一次。
     * 绝大多数情况不靠它 —— 打开页面、转写成功、网络恢复都会主动对一次，
     * 而且每次只是问一个 30 字节的版本号，没变就什么都不做。
     */
    private void maybeSyncHotwords() {
        long now = System.currentTimeMillis();
        if (now - lastHotCheck < 15 * 60 * 1000) return;
        lastHotCheck = now;
        int r = Net.syncHotwords(this);
        if (r < 0) {
            // 电脑不在：一分钟后再试
            lastHotCheck = now - 15 * 60 * 1000 + 60000;
        }
        if (r == 1) {
            Log.i(TAG, "热词已从电脑拉下来");
            Intent i = new Intent(ACTION_UPDATE);
            i.setPackage(getPackageName());
            sendBroadcast(i);
        } else if (r == 2) {
            Log.i(TAG, "热词已推送到电脑");
        }
    }

    private synchronized void syncOnce() {
        maybeSyncHotwords();
        File inbox = inboxDir(this);
        importPublicPending(inbox);
        importFromMediaStore(inbox);
        File[] fs = inbox.listFiles();
        if (fs == null) return;
        List<File> all = new ArrayList<>();
        for (File f : fs) {
            if (!f.isFile() || !f.getName().endsWith(".m4a")) continue;
            if (System.currentTimeMillis() - f.lastModified() < 3000) continue;
            // 太小的一定是没写完的残file，等下一轮再说
            if (f.length() < 2048) continue;
            all.add(f);
        }
        if (all.isEmpty()) return;
        // 太短 / 太安静的录音不要落成记事：
        //   · 短于 3 秒基本是误按
        //   · 峰值低于阈值说明没人说话（Whisper 在静音上会幻觉出「ご視聴ありがとうございました」这类句子）
        // Loudness.peak 返回 -1 表示判不出来，这时不拦（宁可留空录音，也不误删用户的话）。
        int ignored = 0;
        for (java.util.Iterator<File> it = all.iterator(); it.hasNext(); ) {
            File f = it.next();
            int sec = duration(f);
            if (sec > 0 && sec < MIN_SEC) {
                Log.i(TAG, "跳过太短（" + sec + "s）：" + f.getName());
                try { f.delete(); } catch (Throwable ignoredEx) { }
                it.remove();
                ignored++;
                continue;
            }
            int voiced = Loudness.voiced(f, 8000);
            if (voiced == 0) {
                Log.i(TAG, "跳过太安静（没有达标的 100ms 窗口）：" + f.getName());
                try { f.delete(); } catch (Throwable ignoredEx) { }
                it.remove();
                ignored++;
            }
        }
        if (ignored > 0) notifyState("已忽略 " + ignored + " 条（太短或没声音）");
        if (System.currentTimeMillis() < nextTryAt) return;
        if (!Net.hasNetwork(this)) {
            notifyState("有 " + all.size() + " 条录音等着联网转写");
            return;
        }
        Collections.sort(all, (a, b) -> a.getName().compareTo(b.getName()));

        int done = 0;
        for (File f : all) {
            notifyState("正在转写 " + f.getName());
            String[] pair = Net.transcribePair(f, this);
            String text = pair == null ? null : pair[1];
            String raw = pair == null ? null : pair[0];
            if (text == null) {
                failCount = Math.min(failCount + 1, BACKOFF.length);
                nextTryAt = System.currentTimeMillis() + BACKOFF[failCount - 1];
                notifyState("转写没成功，" + (BACKOFF[failCount - 1] / 60000.0) + " 分钟后重试");
                break;
            }
            failCount = 0;
            nextTryAt = 0;
            saveNote(f, text, raw);
            done++;
            if (done >= 5) break;
        }
        if (done > 0) {
            Intent i = new Intent(ACTION_UPDATE);
            i.setPackage(getPackageName());
            sendBroadcast(i);
            notifyState("已转写 " + done + " 条");
        }
    }

    private void saveNote(File src, String text, String raw) {
        NoteDb db = new NoteDb(this);
        long created = parseTime(src.getName());
        // 同一条录音只落一次
        if (exists(db, created)) {
            try { src.delete(); } catch (Throwable ignored) { }
            return;
        }
        int sec = duration(src);
        File dst = new File(db.audioDir(), src.getName());
        try {
            if (dst.exists()) dst.delete();
            if (!src.renameTo(dst)) {
                java.io.FileInputStream in = new java.io.FileInputStream(src);
                java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.close();
                in.close();
                src.delete();
            }
        } catch (Throwable t) {
            Log.e(TAG, "move audio", t);
        }
        String clean = text == null ? "" : text.trim();
        db.insert(clean, raw == null ? null : raw.trim(),
                dst.exists() ? dst.getAbsolutePath() : null, sec, created,
                clean.isEmpty() ? 2 : 1);
    }

    /** 从 MediaStore 收语音键推来的音频（Music/VoiceKey）。
     *  语音键在 App 内写不了我们的私有目录（Android 11 包可见性 + FUSE 权限），
     *  改走系统媒体库：它插自己的文件不需要权限，我们读只需要存储权限。 */
    private void importFromMediaStore(File inbox) {
        Cursor c = null;
        try {
            Uri col = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            c = getContentResolver().query(col,
                    new String[]{MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME},
                    MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ?",
                    new String[]{"Music/VoiceKey%"}, null);
            if (c == null) return;
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String name = c.getString(1);
                if (name == null || !name.endsWith(".m4a")) continue;
                Uri u = ContentUris.withAppendedId(col, id);
                File dst = new File(inbox, name);
                java.io.InputStream in = null;
                java.io.FileOutputStream out = null;
                try {
                    in = getContentResolver().openInputStream(u);
                    if (in == null) continue;
                    out = new java.io.FileOutputStream(dst);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.close(); out = null;
                    in.close(); in = null;
                    getContentResolver().delete(u, null, null);
                    Log.i(TAG, "从媒体库导入 " + name);
                } catch (Throwable t) {
                    Log.w(TAG, "媒体库导入失败：" + name + " " + t);
                    try { if (out != null) out.close(); } catch (Throwable ignored) { }
                    try { if (in != null) in.close(); } catch (Throwable ignored) { }
                    try { dst.delete(); } catch (Throwable ignored) { }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "media import: " + t);
        } finally {
            try { if (c != null) c.close(); } catch (Throwable ignored) { }
        }
    }

    private boolean exists(NoteDb db, long created) {
        Cursor c = db.getReadableDatabase().rawQuery(
                "select 1 from note where created=? limit 1",
                new String[]{String.valueOf(created)});
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    private int duration(File f) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            String ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return ms == null ? 0 : (int) (Long.parseLong(ms) / 1000);
        } catch (Throwable t) {
            return 0;
        } finally {
            try { r.release(); } catch (Throwable ignored) { }
        }
    }

    /** 文件名 vk_20261118_130512.m4a → 毫秒时间戳。 */
    static long parseTime(String name) {
        try {
            String s = name.replace("vk_", "").replace(".m4a", "");
            Date d = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(s);
            return d == null ? System.currentTimeMillis() : d.getTime();
        } catch (Throwable t) {
            return System.currentTimeMillis();
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        if (loop != null) loop.interrupt();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
