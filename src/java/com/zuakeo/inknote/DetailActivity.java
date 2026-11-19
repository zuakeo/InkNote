package com.zuakeo.inknote;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 记事详情：看、听、改。录音和文字在一条里。 */
public class DetailActivity extends Activity {

    private Note note;
    private boolean isNew;
    private boolean editing;
    private MediaPlayer player;
    private TextView playBtn;
    private ImageView playIcon;
    private FrameLayout playBox;
    private View playLine;
    private TextView progress;
    private EditText editor;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.WHITE));
        Ui.whiteStatusBar(this);

        isNew = getIntent().getBooleanExtra("new", false);
        String path = getIntent().getStringExtra("path");
        if (isNew) {
            note = new Note();
            note.id = -2;
            note.created = System.currentTimeMillis();
            note.text = "";
        } else if (path != null) {
            note = new Note();
            note.id = -1;
            note.audio = path;
            note.text = "";
            note.created = getIntent().getLongExtra("created", System.currentTimeMillis());
            note.sec = getIntent().getIntExtra("sec", 0);
        } else {
            long id = getIntent().getLongExtra("id", -1);
            note = new NoteDb(this).get(id);
        }
        if (note == null) {
            finish();
            return;
        }
        render();
        if (isNew) startEdit();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void render() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        int pad = dp(24);

        // 顶栏
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(10), dp(20), dp(6));

        View back = Ui.underlineIconButton(this, R.drawable.ic_back_chev, 24, this::finish);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.rightMargin = dp(18);
        bar.addView(back, bp);

        TextView time = new TextView(this);
        time.setTextSize(18);
        time.setIncludeFontPadding(false);
        time.setTextColor(0xFF888888);
        time.setText(isNew ? "新建" : new SimpleDateFormat("yyyy年M月d日 HH:mm",
                Locale.CHINA).format(new Date(note.created)));
        bar.addView(time, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View action = Ui.underlineButton(this, editing ? "保存" : "编辑", 19, () -> {
            if (editing) save();
            else startEdit();
        });
        bar.addView(action);
        root.addView(bar);

        View line = new View(this);
        line.setBackgroundColor(0xFFE0E0E0);
        root.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        if (editing) {
            editor = new EditText(this);
            editor.setText(note.text == null ? "" : note.text);
            editor.setHint("写点什么…");
            editor.setTextSize(19);
            editor.setTextColor(Color.BLACK);
            editor.setHintTextColor(0xFFAAAAAA);
            editor.setBackgroundColor(Color.WHITE);
            editor.setGravity(Gravity.TOP | Gravity.START);
            editor.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            editor.setPadding(pad, dp(16), pad, dp(16));
            ScrollView sv = new ScrollView(this);
            sv.addView(editor);
            root.addView(sv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        } else {
            ScrollView sv = new ScrollView(this);
            TextView body = new TextView(this);
            String text = note.text == null ? "" : note.text.trim();
            if (TextUtils.isEmpty(text)) {
                text = note.id == -1
                        ? "（这段录音还没转写，联网后自动变成文字）"
                        : "（只有录音，没识别出文字）";
            }
            body.setText(text);
            body.setTextSize(20);
            body.setTextColor(Color.BLACK);
            body.setLineSpacing(dp(8), 1f);
            body.setTextIsSelectable(true);
            body.setPadding(pad, dp(14), pad, dp(24));
            sv.addView(body);
            root.addView(sv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }

        if (note.hasAudio()) {
            View l2 = new View(this);
            l2.setBackgroundColor(0xFFE0E0E0);
            root.addView(l2, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

            LinearLayout bar2 = new LinearLayout(this);
            bar2.setOrientation(LinearLayout.HORIZONTAL);
            bar2.setGravity(Gravity.CENTER_VERTICAL);
            bar2.setPadding(pad, dp(8), pad, dp(12));

            // 图标和文字一起包一层，整块可点，点图标也生效
            LinearLayout hit = new LinearLayout(this);
            hit.setOrientation(LinearLayout.HORIZONTAL);
            hit.setGravity(Gravity.CENTER_VERTICAL);
            hit.setPadding(0, 0, 0, dp(5));

            playIcon = new ImageView(this);
            playIcon.setImageDrawable(getResources().getDrawable(R.drawable.ic_play_tri));
            LinearLayout.LayoutParams pipp = new LinearLayout.LayoutParams(dp(22), dp(22));
            pipp.rightMargin = dp(12);
            hit.addView(playIcon, pipp);

            playBtn = new TextView(this);
            playBtn.setText("播放录音");
            playBtn.setTextSize(19);
            playBtn.setTextColor(Color.BLACK);
            playBtn.setIncludeFontPadding(false);
            playBtn.setPadding(0, 0, dp(22), 0);
            hit.addView(playBtn);

            playBox = new FrameLayout(this);
            playBox.addView(hit, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER_VERTICAL));

            playLine = new View(this);
            playLine.setBackgroundColor(Color.BLACK);
            playBox.addView(playLine, new FrameLayout.LayoutParams(
                    0, dp(2), Gravity.BOTTOM | Gravity.START));

            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.rightMargin = dp(18);
            bar2.addView(playBox, plp);
            playBox.setOnClickListener(v -> {
                Ui.flashOnly(this, playLine, hit);
                toggle();
            });

            progress = new TextView(this);
            progress.setTextSize(15);
            progress.setTextColor(0xFF777777);
            progress.setText(note.sec > 0 ? note.sec + " 秒" : "");
            bar2.addView(progress);

            root.addView(bar2);

            // 进度条：每秒长一格，不逐帧滚动（墨水屏逐帧会一直闪）
            track = new View(this);
            track.setBackgroundColor(0xFFDDDDDD);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
            tp.leftMargin = pad;
            tp.rightMargin = pad;
            root.addView(track, tp);

            fill = new View(this);
            fill.setBackgroundColor(Color.BLACK);
            fillW = dp(3);
            LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(fillW, dp(3));
            fp.leftMargin = pad;
            fp.topMargin = -dp(3);
            root.addView(fill, fp);

            // 底部留白，免得进度条贴着屏幕边被切掉
            View tail = new View(this);
            root.addView(tail, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(14)));
        }

        setContentView(root);
    }

    private void startEdit() {
        editing = true;
        render();
        if (editor != null) {
            editor.requestFocus();
            editor.setSelection(editor.getText().length());
            h.postDelayed(() -> {
                try {
                    InputMethodManager im = (InputMethodManager)
                            getSystemService(Context.INPUT_METHOD_SERVICE);
                    im.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
                } catch (Throwable ignored) { }
            }, 300);
        }
    }

    private void save() {
        String text = editor == null ? "" : editor.getText().toString().trim();
        NoteDb db = new NoteDb(this);
        if (note.id == -2) {
            // 新建
            if (!text.isEmpty()) {
                db.insert(text, null, 0, note.created, 3);
            }
            finish();
            return;
        }
        if (note.id == -1) {
            // 给还没转写的录音补文字：把录音收进归档，然后落成一条记事
            String audioPath = null;
            File src = note.audio == null ? null : new File(note.audio);
            if (src != null && src.exists()) {
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
                    audioPath = dst.exists() ? dst.getAbsolutePath() : null;
                } catch (Throwable ignored) { }
            }
            db.insert(text, audioPath, note.sec, note.created, text.isEmpty() ? 2 : 1);
            finish();
            return;
        }
        db.updateText(note.id, text);
        note.text = text;
        editing = false;
        render();
        h.postDelayed(() -> {
            try {
                InputMethodManager im = (InputMethodManager)
                        getSystemService(Context.INPUT_METHOD_SERVICE);
                im.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0);
            } catch (Throwable ignored) { }
        }, 50);
    }

    /** 播放状态：换图标 + 换文字。 */
    private void setPlayState(boolean playing, String label) {
        if (playIcon != null) {
            playIcon.setImageDrawable(getResources().getDrawable(
                    playing ? R.drawable.ic_pause_bars : R.drawable.ic_play_tri));
        }
        if (playBtn != null) playBtn.setText(label);
    }

    private void toggle() {
        if (player != null && player.isPlaying()) {
            player.pause();
            setPlayState(false, "继续播放");
            return;
        }
        if (player != null) {
            player.start();
            setPlayState(true, "暂停");
            return;
        }
        File f = new File(note.audio);
        if (!f.exists()) {
            playBtn.setText("录音文件不在了");
            return;
        }
        try {
            player = new MediaPlayer();
            player.setDataSource(f.getAbsolutePath());
            player.prepare();
            player.start();
            setPlayState(true, "暂停");
            player.setOnCompletionListener(mp -> {
                setPlayState(false, "重新播放");
                progress.setText(note.sec > 0 ? note.sec + " 秒" : "");
                if (fill != null) {
                    LinearLayout.LayoutParams fp =
                            (LinearLayout.LayoutParams) fill.getLayoutParams();
                    fp.width = dp(3);
                    fill.setLayoutParams(fp);
                }
            });
            tick();
        } catch (Throwable t) {
            playBtn.setText("播放失败");
        }
    }

    private int lastSec = -1;
    private View track, fill;
    private int fillW = 3;

    /** 一秒刷一次，而且只在秒数变了才重绘 —— 墨水屏每重绘一次就闪一下。 */
    private void tick() {
        if (player == null) return;
        try {
            if (player.isPlaying() && progress != null) {
                int pos = player.getCurrentPosition() / 1000;
                int total = player.getDuration() / 1000;
                if (pos != lastSec) {
                    lastSec = pos;
                    progress.setText(pos + " / " + total + " 秒");
                    if (fill != null && track != null && total > 0) {
                        int full = track.getWidth();
                        int w = (int) ((long) full * Math.min(pos, total) / total);
                        LinearLayout.LayoutParams fp =
                                (LinearLayout.LayoutParams) fill.getLayoutParams();
                        fp.width = Math.max(dp(3), w);
                        fill.setLayoutParams(fp);
                    }
                }
                h.postDelayed(this::tick, 400);
            }
        } catch (Throwable ignored) { }
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (player != null) {
            try { player.stop(); } catch (Throwable ignored) { }
            try { player.release(); } catch (Throwable ignored) { }
            player = null;
        }
        super.onDestroy();
    }
}
