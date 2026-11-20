package com.dsh.memwatch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/** 界面小工具与选值对话框（五档 + 自定义输入）。 */
public final class Ui {

    public interface OnBool { void on(boolean v); }
    public interface OnInt { void on(int v); }

    private Ui() {}

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static TextView text(Context c, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    public static LinearLayout card(Context c, int bg, int radiusDp) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp(c, radiusDp));
        box.setBackground(g);
        box.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 10);
        box.setLayoutParams(lp);
        return box;
    }

    public static Button button(Context c, String label, int textColor, int bgColor) {
        Button b = new Button(c);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setAllCaps(false);
        b.setTextColor(textColor);
        GradientDrawable g = new GradientDrawable();
        g.setColor(bgColor);
        g.setCornerRadius(dp(c, 10));
        b.setBackground(g);
        b.setPadding(dp(c, 8), dp(c, 6), dp(c, 8), dp(c, 6));
        return b;
    }

    public static Switch switchRow(Context c, String label, boolean checked, int color, final OnBool cb) {
        Switch sw = new Switch(c);
        sw.setText(label);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sw.setTextColor(color);
        sw.setChecked(checked);
        sw.setPadding(0, dp(c, 8), 0, 0);
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                cb.on(v);
            }
        });
        return sw;
    }

    public static LinearLayout.LayoutParams full(int topMarginDp, Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, topMarginDp);
        return lp;
    }

    /** 单项选择；custom=true 时追加「自定义…」，可选任意数字 */
    public static void choose(final Activity a, String title, final String[] labels, final int[] values,
                              int current, final boolean custom, final OnInt cb) {
        final int n = labels.length + (custom ? 1 : 0);
        String[] items = new String[n];
        System.arraycopy(labels, 0, items, 0, labels.length);
        if (custom) items[n - 1] = "自定义…";
        int idx = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == current) idx = i;

        new AlertDialog.Builder(a)
                .setTitle(title)
                .setSingleChoiceItems(items, idx, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        if (custom && which == n - 1) askNumber(a, title, cb);
                        else cb.on(values[which]);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void askNumber(final Activity a, String title, final OnInt cb) {
        final EditText et = new EditText(a);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setGravity(Gravity.CENTER);
        new AlertDialog.Builder(a)
                .setTitle(title + "（自定义）")
                .setView(et)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        try {
                            cb.on(Integer.parseInt(et.getText().toString().trim()));
                        } catch (Throwable ignore) {}
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    public static int tint(int color, int alphaPct) {
        return Theme.alpha(color, alphaPct);
    }
}
