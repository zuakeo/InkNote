package com.zuakeo.inknote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/** 设置页。墨水屏友好：大字号、纯黑白、一屏滚动。 */
public class SettingsActivity extends Activity {

    private EditText pc;
    private TextView pcState, stats, hotState, hotSummary, keySummary;
    private ImageView pcIcon, hotIcon;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.WHITE));
        Ui.whiteStatusBar(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        // 顶栏
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(12), dp(20), dp(8));

        View back = Ui.underlineIconButton(this, R.drawable.ic_back_chev, 22, this::finish);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.rightMargin = dp(16);
        bar.addView(back, bp);

        TextView title = new TextView(this);
        title.setText("设置");
        title.setTextSize(26);
        title.setTypeface(null, 1);
        title.setTextColor(Color.BLACK);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        bar.addView(Ui.underlineButton(this, "保存", 18, this::doSave));

        root.addView(bar);
        root.addView(divider());

        ScrollView sv = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(4), dp(24), dp(40));

        // ── 电脑 ──
        body.addView(section("转写方式 · 电脑优先"));
        body.addView(hint("电脑开着就用电脑转，中文最准（带热词和润色）。"));

        body.addView(label("电脑地址"));
        pc = input(Net.prefs(this).getString("pc", ""), "http://192.0.2.10:8765");
        body.addView(pc);

        LinearLayout scanRow = new LinearLayout(this);
        scanRow.setOrientation(LinearLayout.HORIZONTAL);
        scanRow.setGravity(Gravity.CENTER_VERTICAL);
        scanRow.setPadding(0, dp(10), 0, dp(4));
        scanRow.addView(pill("扫描局域网", this::doScan));

        pcIcon = Ui.stateIcon(this, false);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(28), dp(28));
        ip.leftMargin = dp(18);
        scanRow.addView(pcIcon, ip);

        pcState = new TextView(this);
        pcState.setTextSize(15);
        pcState.setTextColor(0xFF666666);
        pcState.setPadding(dp(10), 0, 0, 0);
        pcState.setText("检测中…");
        scanRow.addView(pcState);
        body.addView(scanRow);

        // ── Groq ──
        body.addView(section("转写方式 · 电脑连不上时"));
        body.addView(hint("用手机自己连 Groq。没填也能用，只是要等电脑开机。"));

        keySummary = new TextView(this);
        keySummary.setTextSize(17);
        keySummary.setTextColor(Color.BLACK);
        keySummary.setPadding(dp(14), dp(12), dp(14), dp(12));
        keySummary.setBackground(box());
        keySummary.setOnClickListener(v -> editKey());
        body.addView(keySummary);
        refreshKeySummary();

        // ── 热词 ──
        body.addView(section("热词"));
        body.addView(hint("用顿号隔开。人名、地名、专业词写上，识别更准。双向同步，删除也会同步。"));

        hotSummary = new TextView(this);
        hotSummary.setTextSize(17);
        hotSummary.setTextColor(Color.BLACK);
        hotSummary.setGravity(Gravity.CENTER_VERTICAL);
        hotSummary.setPadding(dp(14), dp(12), dp(14), dp(12));
        hotSummary.setBackground(box());
        hotSummary.setOnClickListener(v -> editHotwords());
        body.addView(hotSummary);
        refreshHotSummary();

        LinearLayout hotRow = new LinearLayout(this);
        hotRow.setOrientation(LinearLayout.HORIZONTAL);
        hotRow.setGravity(Gravity.CENTER_VERTICAL);
        hotRow.setPadding(0, dp(10), 0, dp(4));
        hotRow.addView(pill("同步到电脑", this::syncHot));

        hotIcon = Ui.stateIcon(this, false);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(28), dp(28));
        hp.leftMargin = dp(18);
        hotRow.addView(hotIcon, hp);

        hotState = new TextView(this);
        hotState.setTextSize(15);
        hotState.setTextColor(0xFF666666);
        hotState.setPadding(dp(10), 0, 0, 0);
        // 打开页面时按实际记录显示，不是一律「未同步」
        String hwNow = Net.prefs(this).getString("hotwords", Net.DEFAULT_HOTWORDS);
        String hpNow = Net.prefs(this).getString("hotwords_synced_text", "");
        boolean synced = hwNow != null && hwNow.trim().equals(hpNow.trim());
        Ui.setState(hotIcon, synced);
        hotState.setText(synced ? "已同步" : "未同步");
        hotRow.addView(hotState);
        body.addView(hotRow);

        // ── 状态 ──
        body.addView(section("状态"));
        stats = new TextView(this);
        stats.setTextSize(16);
        stats.setTextColor(0xFF333333);
        stats.setLineSpacing(dp(6), 1f);
        body.addView(stats);

        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        refreshStats();
        checkPc();
    }

    // ---------- 摘要 ----------

    private void refreshKeySummary() {
        String k = Vault.read(this, "groq");
        if (k == null) k = "";
        k = k.trim();
        if (k.isEmpty()) {
            keySummary.setText("未设置　点这里填写");
            keySummary.setTextColor(0xFF999999);
        } else {
            keySummary.setText("已设置　点这里修改");
            keySummary.setTextColor(Color.BLACK);
        }
    }

    private void refreshHotSummary() {
        int n = countWords(Net.prefs(this).getString("hotwords", Net.DEFAULT_HOTWORDS));
        hotSummary.setText("共 " + n + " 个词　点这里编辑");
    }

    private int countWords(String s) {
        if (s == null || s.trim().isEmpty()) return 0;
        int n = 0;
        for (String w : s.replace("、", "\n").split("\n")) {
            if (!w.trim().isEmpty()) n++;
        }
        return n;
    }

    private void refreshStats() {
        NoteDb db = new NoteDb(this);
        int notes = db.list().size();
        int pending = 0;
        for (File d : SyncService.pendingDirs(this)) {
            File[] fs = d.listFiles();
            if (fs != null) {
                for (File f : fs) if (f.getName().endsWith(".m4a")) pending++;
            }
        }
        stats.setText("记事 " + notes + " 条　·　待转写 " + pending + " 条\n"
                + "录音存在应用私有目录，不会自动删除。");
    }

    // ---------- 弹窗编辑 ----------

    private EditText dialogEdit(String value, String hintText, boolean multiline) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hintText);
        e.setTextSize(17);
        e.setTextColor(Color.BLACK);
        e.setHintTextColor(0xFFAAAAAA);
        e.setBackground(box());
        int p = dp(12);
        e.setPadding(p, p, p, p);
        e.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);   // 不遮成小圆点
        if (multiline) {
            e.setSingleLine(false);
            e.setMinLines(7);
            e.setGravity(Gravity.TOP | Gravity.START);
        }
        e.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private View dialogWrap(EditText e) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(20), dp(8), dp(20), dp(4));
        wrap.addView(e);
        return wrap;
    }

    private void editKey() {
        final EditText e = dialogEdit(Vault.read(this, "groq"), "gsk_...", false);
        new AlertDialog.Builder(this)
                .setTitle("Groq Key")
                .setView(dialogWrap(e))
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> {
                    String v = e.getText().toString().trim();
                    String stored = v;
                    if (!v.isEmpty()) {
                        try {
                            stored = Vault.encrypt(v);
                        } catch (Throwable t) {
                            toast("加密失败，未保存");
                            return;
                        }
                    }
                    Net.prefs(this).edit().putString("groq", stored).apply();
                    refreshKeySummary();
                    toast("已保存");
                })
                .show();
    }

    private void editHotwords() {
        final EditText e = dialogEdit(
                Net.prefs(this).getString("hotwords", Net.DEFAULT_HOTWORDS),
                "劳务公司、签字、签证材料…", true);
        new AlertDialog.Builder(this)
                .setTitle("热词")
                .setView(dialogWrap(e))
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> {
                    Net.prefs(this).edit()
                            .putString("hotwords", e.getText().toString().trim()).apply();
                    refreshHotSummary();
                    toast("已保存");
                })
                .show();
    }

    // ---------- 动作 ----------

    private void doSave() {
        Net.prefs(this).edit().putString("pc", pc.getText().toString().trim()).apply();
        Intent i = new Intent(this, SyncService.class);
        i.setAction(SyncService.ACTION_SYNC);
        startService(i);
        toast("已保存");
        checkPc();
        refreshStats();
        syncHot();
    }

    private void doScan() {
        pcState.setText("扫描中…");
        new Thread(() -> {
            String found = Net.discoverPublic(pc.getText().toString().trim(), this);
            h.post(() -> {
                if (found == null) {
                    pcState.setText("没找到电脑");
                    Ui.setState(pcIcon, false);
                } else {
                    pc.setText(found);
                    pcState.setText("找到了");
                    Ui.setState(pcIcon, true);
                }
            });
        }, "scan").start();
    }

    private void syncHot() {
        hotState.setText("同步中");
        new Thread(() -> {
            int r = Net.syncHotwords(this);
            h.post(() -> {
                Ui.setState(hotIcon, r >= 0);
                if (r == 1) {
                    hotState.setText("已从电脑更新");
                    refreshHotSummary();
                } else if (r == 2) {
                    hotState.setText("已同步到电脑");
                } else if (r == 3) {
                    hotState.setText("两边都改过，已合并");
                    refreshHotSummary();
                } else if (r == 0) {
                    hotState.setText("两边一致");
                } else {
                    hotState.setText("连不上电脑");
                }
                hotState.setTextColor(0xFF666666);
            });
        }, "hot").start();
    }

    private void checkPc() {
        String addr = pc.getText().toString().trim();
        if (addr.isEmpty()) {
            pcState.setText("未设置");
            Ui.setState(pcIcon, false);
            return;
        }
        pcState.setText("检测中…");
        new Thread(() -> {
            boolean ok = Net.pingPublic(addr, this);
            if (!ok) {
                // 刚开机/刚联网时第一次常失败，隔一秒再试一次
                try { Thread.sleep(1200); } catch (InterruptedException ignored) { }
                ok = Net.pingPublic(addr, this);
            }
            final boolean fine = ok;
            h.post(() -> {
                Ui.setState(pcIcon, fine);
                pcState.setText(fine ? "能连上" : "连不上");
                pcState.setTextColor(0xFF666666);
            });
        }, "ping").start();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------- 控件工厂 ----------

    private int dp(int v) {
        return Ui.dp(this, v);
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(0xFFE0E0E0);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return v;
    }

    private TextView section(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(15);
        v.setTextColor(0xFF888888);
        v.setPadding(0, dp(24), 0, dp(6));
        return v;
    }

    private TextView hint(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(14);
        v.setTextColor(0xFF999999);
        v.setLineSpacing(dp(4), 1f);
        v.setPadding(0, 0, 0, dp(8));
        return v;
    }

    private TextView label(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(17);
        v.setTextColor(Color.BLACK);
        v.setPadding(0, dp(10), 0, dp(6));
        return v;
    }

    /** 描边小按钮。 */
    private TextView pill(String text, Runnable action) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(17);
        v.setTextColor(Color.BLACK);
        v.setPadding(dp(18), dp(10), dp(18), dp(10));
        v.setBackground(box());
        v.setOnClickListener(x -> action.run());
        return v;
    }

    private EditText input(String value, String hintText) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hintText);
        e.setTextSize(17);
        e.setTextColor(Color.BLACK);
        e.setHintTextColor(0xFFAAAAAA);
        e.setBackground(box());
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        e.setSingleLine(true);
        return e;
    }

    private Drawable box() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.WHITE);
        g.setStroke(dp(1), 0xFFBBBBBB);
        g.setCornerRadius(dp(2));
        return g;
    }
}
