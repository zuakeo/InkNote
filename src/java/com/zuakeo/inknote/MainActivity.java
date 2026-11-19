package com.zuakeo.inknote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 记事本主页：时间、正文、录音时长。断网也能看到刚录的东西。 */
public class MainActivity extends Activity {

    private NoteDb db;
    private List<Note> data = new ArrayList<>();
    private BaseAdapter adapter;
    private ListView list;
    private TextView empty;
    private TextView count;

    private final Handler h = new Handler(Looper.getMainLooper());
    private String signature = "";

    // 批量选择：长按任一条进入，点条目勾选/取消，顶部出现「全选 / 删除 / 取消」
    private boolean selecting = false;
    private final java.util.LinkedHashSet<String> picked = new java.util.LinkedHashSet<>();
    private LinearLayout selBar;
    private TextView selCount;

    private final BroadcastReceiver updater = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            reload(true);
        }
    };

    /** 每 3 秒轻量刷一次：录音落地后不用退出重进也能看见。 */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            reload(false);
            h.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.WHITE));
        Ui.whiteStatusBar(this);
        db = new NoteDb(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        // 顶栏
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.BOTTOM);          // 底部对齐
        int pad = dp(20);
        bar.setPadding(pad, dp(10), pad, dp(8));

        TextView title = new TextView(this);
        title.setText("记事本");
        title.setTextSize(26);
        title.setTextColor(Color.BLACK);
        title.setTypeface(null, 1);
        bar.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 「3 条」紧跟在标题后面，跟标题底部对齐
        count = new TextView(this);
        count.setTextSize(15);
        count.setTextColor(0xFF888888);
        count.setPadding(dp(10), 0, 0, dp(5));
        bar.addView(count);

        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, dp(1), 1f));

        TextView settings = new TextView(this);
        settings.setText("设置");
        settings.setTextSize(26);
        settings.setTextColor(0xFF444444);
        settings.setPadding(0, 0, 0, dp(2));
        settings.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            overridePendingTransition(0, 0);
        });
        bar.addView(settings);

        root.addView(bar);

        View line = new View(this);
        line.setBackgroundColor(0xFF000000);
        root.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));

        // 选择模式的操作条（默认隐藏）
        selBar = new LinearLayout(this);
        selBar.setOrientation(LinearLayout.HORIZONTAL);
        selBar.setBackgroundColor(0xFFEDEDED);
        selBar.setPadding(pad, dp(8), pad, dp(8));
        selBar.setVisibility(View.GONE);
        selCount = new TextView(this);
        selCount.setTextSize(18);
        selCount.setTextColor(0xFF222222);
        selBar.addView(selCount, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        selBar.addView(selAction("全选", v -> selectAll()));
        selBar.addView(selAction("删除", v -> deletePicked()));
        selBar.addView(selAction("取消", v -> exitSelect()));
        root.addView(selBar);

        list = new ListView(this);
        list.setBackgroundColor(Color.WHITE);
        list.setDivider(new ColorDrawable(0xFF999999));
        list.setDividerHeight(dp(1));
        list.setVerticalScrollBarEnabled(false);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dp(110));
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            Note n = data.get(pos);
            if (selecting) { togglePick(n); return; }
            Intent i = new Intent(MainActivity.this, DetailActivity.class);
            if (n.id >= 0) {
                i.putExtra("id", n.id);
            } else {
                i.putExtra("path", n.audio);
                i.putExtra("created", n.created);
                i.putExtra("sec", n.sec);
            }
            startActivity(i);
            overridePendingTransition(0, 0);
        });
        // 长按 → 进入批量选择（以前是单条删除弹窗，现在统一走勾选，单条也能删）
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            Note n = data.get(pos);
            if (selecting) togglePick(n); else enterSelect(n);
            return true;
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        empty = new TextView(this);
        empty.setText("还没有记事。\n\n点右上角 ＋ 写一条，\n或者按住墨智键说话，松开就存下来。");
        empty.setTextSize(18);
        empty.setTextColor(0xFF777777);
        empty.setGravity(Gravity.CENTER);
        empty.setLineSpacing(dp(10), 1f);
        root.addView(empty, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 底部居中的圆形悬浮按钮
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(this);
        wrap.addView(root, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        android.widget.FrameLayout fab = new android.widget.FrameLayout(this);
        android.graphics.drawable.GradientDrawable circle =
                new android.graphics.drawable.GradientDrawable();
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circle.setColor(Color.WHITE);
        circle.setStroke(dp(2), 0xFF000000);
        fab.setBackground(circle);
        android.widget.ImageView plusIcon = new android.widget.ImageView(this);
        plusIcon.setImageDrawable(getResources().getDrawable(R.drawable.ic_plus));
        int ps = dp(22);
        fab.addView(plusIcon, new android.widget.FrameLayout.LayoutParams(
                ps, ps, Gravity.CENTER));
        int fs = dp(58);
        android.widget.FrameLayout.LayoutParams flp =
                new android.widget.FrameLayout.LayoutParams(fs, fs,
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        flp.bottomMargin = dp(26);
        wrap.addView(fab, flp);
        fab.setOnClickListener(v -> {
            Intent i = new Intent(MainActivity.this, DetailActivity.class);
            i.putExtra("new", true);
            startActivity(i);
            overridePendingTransition(0, 0);
        });

        setContentView(wrap);
        startService(new Intent(this, SyncService.class));
        reload(true);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(SyncService.ACTION_UPDATE);
        registerReceiver(updater, f);
        reload(true);
        h.removeCallbacks(tick);
        h.postDelayed(tick, 3000);
        // 回到前台时对一次热词：先问版本，没变就什么都不做
        new Thread(() -> Net.syncHotwords(MainActivity.this), "hot").start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
        try { unregisterReceiver(updater); } catch (Throwable ignored) { }
    }

    private List<Note> collect() {
        List<Note> notes = db.list();
        List<Note> pending = new ArrayList<>();
        for (File d : SyncService.pendingDirs(this)) {
            File[] fs = d.listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                if (!f.isFile() || !f.getName().endsWith(".m4a")) continue;
                Note n = new Note();
                n.id = -1;
                n.audio = f.getAbsolutePath();
                n.text = "";
                n.created = SyncService.parseTime(f.getName());
                n.sec = probeDuration(f);
                n.state = 0;
                pending.add(n);
            }
        }
        List<Note> all = new ArrayList<>();
        all.addAll(pending);
        all.addAll(notes);
        Collections.sort(all, (a, b) -> Long.compare(b.created, a.created));
        return all;
    }

    /** 待转写的录音也要显示时长。 */
    private int probeDuration(File f) {
        android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            String ms = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            return ms == null ? 0 : (int) (Long.parseLong(ms) / 1000);
        } catch (Throwable t) {
            return 0;
        } finally {
            try { r.release(); } catch (Throwable ignored) { }
        }
    }

    private String sig(List<Note> all) {
        StringBuilder sb = new StringBuilder();
        sb.append(all.size());
        for (Note n : all) sb.append('|').append(n.id).append(':').append(n.created)
                .append(':').append(n.text == null ? 0 : n.text.length())
                .append(':').append(n.audio == null ? 0 : n.audio.length());
        return sb.toString();
    }

    private void reload(boolean force) {
        List<Note> all = collect();
        String s = sig(all);
        if (!force && s.equals(signature)) return;
        signature = s;
        data = all;
        adapter.notifyDataSetChanged();
        count.setText(data.isEmpty() ? "" : data.size() + " 条");
        boolean none = data.isEmpty();
        list.setVisibility(none ? View.GONE : View.VISIBLE);
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
    }

    private String timeText(long ms) {
        long now = System.currentTimeMillis();
        // 录音文件名里带的时间可能比系统时间早/晚几百毫秒，落在"未来"就会显示成日期
        if (ms > now) ms = now;
        Date d = new Date(ms);
        if (now - ms < 20L * 3600 * 1000 && now >= ms) {
            return new SimpleDateFormat("HH:mm", Locale.CHINA).format(d);
        }
        if (now - ms < 350L * 24 * 3600 * 1000) {
            return new SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(d);
        }
        return new SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(d);
    }

    // ---------- 批量选择 ----------

    private String keyOf(Note n) {
        return n.id >= 0 ? "i" + n.id : "p" + n.audio;
    }

    private TextView selAction(String text, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(18);
        t.setTextColor(0xFF000000);
        t.setPadding(dp(14), dp(4), dp(14), dp(4));
        t.setOnClickListener(l);
        return t;
    }

    private void enterSelect(Note n) {
        selecting = true;
        picked.clear();
        if (n != null) picked.add(keyOf(n));
        updateSelBar();
        adapter.notifyDataSetChanged();
    }

    private void togglePick(Note n) {
        String k = keyOf(n);
        if (!picked.remove(k)) picked.add(k);
        if (picked.isEmpty()) selecting = false;
        updateSelBar();
        adapter.notifyDataSetChanged();
    }

    private void selectAll() {
        picked.clear();
        for (Note n : data) picked.add(keyOf(n));
        updateSelBar();
        adapter.notifyDataSetChanged();
    }

    private void exitSelect() {
        selecting = false;
        picked.clear();
        updateSelBar();
        adapter.notifyDataSetChanged();
    }

    private void updateSelBar() {
        if (selBar == null) return;
        selBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
        if (selecting) selCount.setText("已选 " + picked.size() + " 条");
    }

    private void deletePicked() {
        final int cnt = picked.size();
        if (cnt == 0) return;
        new AlertDialog.Builder(this)
                .setMessage("删除选中的 " + cnt + " 条？录音也一起删掉。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> {
                    for (Note n : new ArrayList<>(data)) {
                        if (!picked.contains(keyOf(n))) continue;
                        if (n.id >= 0) {
                            db.delete(n.id);
                        } else if (n.audio != null) {
                            new File(n.audio).delete();
                        }
                    }
                    exitSelect();
                    reload(true);
                })
                .show();
    }

    private class Adapter extends BaseAdapter {
        @Override
        public int getCount() { return data.size(); }

        @Override
        public Object getItem(int i) { return data.get(i); }

        @Override
        public long getItemId(int i) { return data.get(i).id; }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            LinearLayout row;
            TextView t1, t2, t3;
            if (cv == null) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                int lp = dp(20);
                row.setPadding(lp, dp(12), lp, dp(9));

                t1 = new TextView(MainActivity.this);
                t1.setTextSize(13);
                t1.setTextColor(0xFF999999);
                row.addView(t1);

                t2 = new TextView(MainActivity.this);
                t2.setTextSize(18);
                t2.setTextColor(0xFF111111);
                t2.setMaxLines(4);
                t2.setEllipsize(TextUtils.TruncateAt.END);
                t2.setLineSpacing(dp(4), 1f);
                LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                p2.topMargin = dp(5);
                row.addView(t2, p2);

                t3 = new TextView(MainActivity.this);
                t3.setTextSize(13);
                t3.setTextColor(0xFF666666);
                t3.setGravity(Gravity.END);
                LinearLayout.LayoutParams p3 = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                p3.topMargin = dp(3);
                row.addView(t3, p3);

                row.setTag(new TextView[]{t1, t2, t3});
            } else {
                row = (LinearLayout) cv;
                TextView[] tags = (TextView[]) row.getTag();
                t1 = tags[0];
                t2 = tags[1];
                t3 = tags[2];
            }
            Note n = data.get(pos);
            t1.setText(timeText(n.created));
            if (selecting) {
                t1.setText((picked.contains(keyOf(n)) ? "☑ " : "☐ ") + timeText(n.created));
                t1.setTextColor(picked.contains(keyOf(n)) ? 0xFF000000 : 0xFF999999);
            } else {
                t1.setTextColor(0xFF999999);
            }
            String body = n.text == null ? "" : n.text.trim();
            if (n.id < 0) {
                body = "⏳ 待转写　联网后自动变成文字";
            } else if (body.isEmpty()) {
                body = n.hasAudio() ? "（只有录音，没识别出文字）" : "（空）";
            }
            t2.setText(body);
            if (n.hasAudio()) {
                t3.setVisibility(View.VISIBLE);
                t3.setText("🎤 录音 " + (n.sec > 0 ? n.sec + " 秒" : ""));
            } else {
                t3.setVisibility(View.GONE);
            }
            return row;
        }
    }
}
