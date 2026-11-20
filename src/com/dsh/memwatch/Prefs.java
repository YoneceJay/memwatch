package com.dsh.memwatch;

import android.content.Context;
import android.content.SharedPreferences;

/** 所有可调项的持久化。读取走内存缓存，采样循环里反复读也不会有 IO。 */
public final class Prefs {

    public static final int[] INTERVALS = {500, 1000, 2000, 3000, 5000, 10000, 30000, 60000};
    public static final String[] INTERVAL_NAMES = {"0.5 秒", "1 秒", "2 秒", "3 秒", "5 秒", "10 秒", "30 秒", "1 分"};

    public static final int[] TOPNS = {4, 6, 8, 12, 20, 0};          // 0 = 全部
    public static final String[] TOPN_NAMES = {"4 行", "6 行", "8 行", "12 行", "20 行", "全部"};

    public static final int[] ALERTS = {70, 75, 80, 85, 90, 95};
    public static final String[] ALERT_NAMES = {"70%", "75%", "80%", "85%", "90%", "95%"};

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("memwatch", Context.MODE_PRIVATE);
    }

    // ---- 采样 ----
    public static int interval(Context c) {
        return sp(c).getInt("interval", 2000);
    }

    public static void setInterval(Context c, int v) {
        sp(c).edit().putInt("interval", v).apply();
    }

    public static boolean precise(Context c) {
        return sp(c).getBoolean("precise", true);
    }

    public static void setPrecise(Context c, boolean v) {
        sp(c).edit().putBoolean("precise", v).apply();
    }

    public static boolean screenOnly(Context c) {
        return sp(c).getBoolean("screen_only", true);
    }

    public static void setScreenOnly(Context c, boolean v) {
        sp(c).edit().putBoolean("screen_only", v).apply();
    }

    public static boolean showSystem(Context c) {
        return sp(c).getBoolean("show_system", false);
    }

    public static void setShowSystem(Context c, boolean v) {
        sp(c).edit().putBoolean("show_system", v).apply();
    }

    // ---- 悬浮窗 ----
    public static boolean expanded(Context c) {
        return sp(c).getBoolean("expanded", true);
    }

    public static void setExpanded(Context c, boolean v) {
        sp(c).edit().putBoolean("expanded", v).apply();
    }

    public static int topN(Context c) {
        return sp(c).getInt("top_n", 6);
    }

    public static void setTopN(Context c, int v) {
        sp(c).edit().putInt("top_n", v).apply();
    }

    /** 背景不透明度百分比 0-100 */
    public static int bgAlphaPct(Context c) {
        return sp(c).getInt("bg_alpha_pct", 78);
    }

    public static void setBgAlphaPct(Context c, int v) {
        sp(c).edit().putInt("bg_alpha_pct", clampPct(v)).apply();
    }

    /** 文字不透明度百分比 0-100 */
    public static int textAlphaPct(Context c) {
        return sp(c).getInt("text_alpha_pct", 100);
    }

    public static void setTextAlphaPct(Context c, int v) {
        sp(c).edit().putInt("text_alpha_pct", clampPct(v)).apply();
    }

    private static int clampPct(int v) {
        return v < 10 ? 10 : (v > 100 ? 100 : v);
    }

    // ---- 主题 ----
    public static int themeId(Context c) {
        return sp(c).getInt("theme_id", 4);   // 默认「橙色提醒」
    }

    public static void setThemeId(Context c, int v) {
        sp(c).edit().putInt("theme_id", v).apply();
    }

    // ---- 固定应用 ----
    public static String[] pinned(Context c) {
        String raw = sp(c).getString("pinned", "");
        if (raw == null || raw.length() == 0) return new String[0];
        return raw.split("\\|");
    }

    public static boolean isPinned(Context c, String pkg) {
        if (pkg == null) return false;
        for (String p : pinned(c)) if (pkg.equals(p)) return true;
        return false;
    }

    public static void togglePin(Context c, String pkg) {
        if (pkg == null || pkg.length() == 0) return;
        String[] cur = pinned(c);
        StringBuilder sb = new StringBuilder();
        boolean exists = false;
        for (String p : cur) {
            if (p.equals(pkg)) { exists = true; continue; }
            if (sb.length() > 0) sb.append('|');
            sb.append(p);
        }
        if (!exists) {
            if (sb.length() > 0) sb.append('|');
            sb.append(pkg);
        }
        sp(c).edit().putString("pinned", sb.toString()).apply();
    }

    public static void removePin(Context c, String pkg) {
        String[] cur = pinned(c);
        StringBuilder sb = new StringBuilder();
        for (String p : cur) {
            if (p.equals(pkg)) continue;
            if (sb.length() > 0) sb.append('|');
            sb.append(p);
        }
        sp(c).edit().putString("pinned", sb.toString()).apply();
    }

    // ---- 固定应用子悬浮窗 ----
    /** 固定应用子悬浮窗开关（与主悬浮窗完全独立） */
    public static boolean subOn(Context c) {
        return sp(c).getBoolean("sub_on", false);
    }

    public static void setSubOn(Context c, boolean v) {
        sp(c).edit().putBoolean("sub_on", v).apply();
    }

    /** 主悬浮窗显示形态：0=圆球 1=简略 2=详细 */
    public static int mainMode(Context c) {
        return sp(c).getInt("main_mode", 2);
    }

    public static void setMainMode(Context c, int v) {
        sp(c).edit().putInt("main_mode", v).apply();
    }

    /** 固定应用小窗：0=圆球 1=详细 */
    public static int subMode(Context c) {
        return sp(c).getInt("sub_mode", 1);
    }

    public static void setSubMode(Context c, int v) {
        sp(c).edit().putInt("sub_mode", v).apply();
    }

    /** 迷你圆球模式 */
    public static boolean ball(Context c) {
        return sp(c).getBoolean("ball", false);
    }

    public static void setBall(Context c, boolean v) {
        sp(c).edit().putBoolean("ball", v).apply();
    }

    /** 主题跟随系统深色模式 */
    public static boolean followSystem(Context c) {
        return sp(c).getBoolean("follow_system", false);
    }

    public static void setFollowSystem(Context c, boolean v) {
        sp(c).edit().putBoolean("follow_system", v).apply();
    }

    public static int subX(Context c) {
        return sp(c).getInt("sub_x", Integer.MIN_VALUE);
    }

    public static int subY(Context c) {
        return sp(c).getInt("sub_y", Integer.MIN_VALUE);
    }

    public static void setSubPos(Context c, int x, int y) {
        sp(c).edit().putInt("sub_x", x).putInt("sub_y", y).apply();
    }

    public static int textSizeSp(Context c) {
        return sp(c).getInt("text_size", 11);
    }

    public static void setTextSizeSp(Context c, int v) {
        sp(c).edit().putInt("text_size", v).apply();
    }

    public static int alertPct(Context c) {
        return sp(c).getInt("alert_pct", 85);
    }

    public static void setAlertPct(Context c, int v) {
        sp(c).edit().putInt("alert_pct", v).apply();
    }

    public static boolean alertNotify(Context c) {
        return sp(c).getBoolean("alert_notify", true);
    }

    public static void setAlertNotify(Context c, boolean v) {
        sp(c).edit().putBoolean("alert_notify", v).apply();
    }

    public static boolean showBar(Context c) {
        return sp(c).getBoolean("show_bar", true);
    }

    public static void setShowBar(Context c, boolean v) {
        sp(c).edit().putBoolean("show_bar", v).apply();
    }

    public static boolean showCurve(Context c) {
        return sp(c).getBoolean("show_curve", true);
    }

    public static void setShowCurve(Context c, boolean v) {
        sp(c).edit().putBoolean("show_curve", v).apply();
    }

    public static boolean overlayOn(Context c) {
        return sp(c).getBoolean("overlay_on", false);
    }

    public static void setOverlayOn(Context c, boolean v) {
        sp(c).edit().putBoolean("overlay_on", v).apply();
    }

    public static int overlayX(Context c) {
        return sp(c).getInt("ov_x", Integer.MIN_VALUE);
    }

    public static int overlayY(Context c) {
        return sp(c).getInt("ov_y", Integer.MIN_VALUE);
    }

    public static void setOverlayPos(Context c, int x, int y) {
        sp(c).edit().putInt("ov_x", x).putInt("ov_y", y).apply();
    }
}
