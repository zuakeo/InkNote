package com.zuakeo.inknote;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 界面小工具：状态栏、顶栏按钮、状态图标。 */
public class Ui {

    /** 所有顶栏按钮统一这个高度，图标和文字才能在同一条中线上。 */
    private static final int BAR_H = 34;

    public static int dp(Activity a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static void whiteStatusBar(Activity a) {
        try {
            a.getWindow().setStatusBarColor(Color.WHITE);
            a.getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            a.getWindow().setNavigationBarColor(Color.WHITE);
        } catch (Throwable ignored) { }
    }

    /**
     * 下划线反馈。这台机器的动画时长是 0（animator_duration_scale 未设置，框架按 0 处理），
     * ValueAnimator 一启动就结束，逐帧动画画不出来。所以做成「线立刻出现 → 停 700ms → 再执行动作」。
     */
    static void flashUnderline(Activity a, View line, View anchor, Runnable action) {
        setLineWidth(a, line, anchor);
        line.postDelayed(() -> {
            if (action != null) action.run();
        }, 700);
    }

    /** 只闪一下线，不延迟动作。 */
    public static void flashOnly(Activity a, View line, View anchor) {
        setLineWidth(a, line, anchor);
        line.postDelayed(() -> {
            FrameLayout.LayoutParams q = (FrameLayout.LayoutParams) line.getLayoutParams();
            q.width = 0;
            line.setLayoutParams(q);
        }, 700);
    }

    private static void setLineWidth(Activity a, View line, View anchor) {
        int full = anchor.getWidth() > 0 ? anchor.getWidth()
                : (anchor.getMeasuredWidth() > 0 ? anchor.getMeasuredWidth() : dp(a, 36));
        ViewGroup.LayoutParams raw = line.getLayoutParams();
        if (raw instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) raw;
            p.width = full;
            line.setLayoutParams(p);
        } else if (raw instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) raw;
            p.width = full;
            line.setLayoutParams(p);
        }
    }

    /** 文字按钮。 */
    public static View underlineButton(final Activity a, String text, int sizeSp,
                                       final Runnable action) {
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextSize(sizeSp);
        tv.setTextColor(Color.BLACK);
        tv.setIncludeFontPadding(false);

        FrameLayout box = new FrameLayout(a);
        box.setMinimumHeight(dp(a, BAR_H));
        box.setPadding(0, 0, 0, dp(a, 5));
        box.addView(tv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        View line = new View(a);
        line.setBackgroundColor(Color.BLACK);
        box.addView(line, new FrameLayout.LayoutParams(
                0, dp(a, 2), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

        box.setOnClickListener(v -> flashUnderline(a, line, tv, action));
        return box;
    }

    /** 图标按钮（返回尖角）。 */
    public static View underlineIconButton(final Activity a, int drawableRes, int sizeDp,
                                           final Runnable action) {
        ImageView iv = new ImageView(a);
        iv.setImageDrawable(a.getResources().getDrawable(drawableRes));

        FrameLayout box = new FrameLayout(a);
        box.setMinimumHeight(dp(a, BAR_H));
        box.setPadding(0, 0, 0, dp(a, 5));
        int s = dp(a, sizeDp);
        iv.setTranslationY(dp(a, 2));   // 视觉上跟文字中线对齐
        box.addView(iv, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));

        View line = new View(a);
        line.setBackgroundColor(Color.BLACK);
        box.addView(line, new FrameLayout.LayoutParams(
                0, dp(a, 2), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

        box.setOnClickListener(v -> flashUnderline(a, line, iv, action));
        return box;
    }

    /** 圆圈状态图标：true=对勾，false=叉。 */
    public static ImageView stateIcon(Activity a, boolean ok) {
        ImageView iv = new ImageView(a);
        setState(iv, ok);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(a, 30), dp(a, 30)));
        return iv;
    }

    public static void setState(ImageView iv, boolean ok) {
        Drawable d = iv.getContext().getResources()
                .getDrawable(ok ? R.drawable.ic_state_ok : R.drawable.ic_state_no);
        iv.setImageDrawable(d);
    }
}
