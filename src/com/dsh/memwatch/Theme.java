package com.dsh.memwatch;

/** 内置主题：悬浮窗与主界面共用，悬浮窗可单独覆盖（暂共用）。 */
public final class Theme {

    public final String name;
    public final int bg;      // 悬浮窗背景
    public final int text;    // 主文字
    public final int dim;     // 次要文字
    public final int accent;  // 强调色
    public final int ok;      // 正常
    public final int warn;    // 接近阈值
    public final int alert;   // 超阈值（提醒色）
    public final int track;   // 进度条底
    public final int curve;   // 曲线

    private Theme(String name, int bg, int text, int dim, int accent,
                  int ok, int warn, int alert, int track, int curve) {
        this.name = name;
        this.bg = bg;
        this.text = text;
        this.dim = dim;
        this.accent = accent;
        this.ok = ok;
        this.warn = warn;
        this.alert = alert;
        this.track = track;
        this.curve = curve;
    }

    public static final Theme[] ALL = {
            new Theme("深色",     0xFF101318, 0xFFE9EEF5, 0xFF9AA6B5, 0xFF4C8DFF, 0xFF4CD08A, 0xFFFFB020, 0xFFFF5C5C, 0x33FFFFFF, 0xFF4CD08A),
            new Theme("浅色",     0xFFFFFFFF, 0xFF1B2330, 0xFF6B7785, 0xFF2F6FED, 0xFF17A673, 0xFFE08A16, 0xFFE04141, 0x22000000, 0xFF2F6FED),
            new Theme("蓝色科技", 0xFF07141F, 0xFFD6E7FF, 0xFF7FA6CC, 0xFF34A0FF, 0xFF3CC9FF, 0xFFFFC14D, 0xFFFF6B4A, 0x334C8DFF, 0xFF34A0FF),
            new Theme("绿色护眼", 0xFF0E1A12, 0xFFDDF3E4, 0xFF8FB49E, 0xFF4CD08A, 0xFF7BE2A8, 0xFFFFC857, 0xFFFF7A59, 0x334CD08A, 0xFF4CD08A),
            new Theme("橙色提醒", 0xFF1A1208, 0xFFFFE9CC, 0xFFC7A582, 0xFFFF9A3C, 0xFFFFB347, 0xFFFFC14D, 0xFFFFA31A, 0x33FF9A3C, 0xFFFF9A3C),
            new Theme("高对比",   0xFF000000, 0xFFFFFFFF, 0xFFB0B0B0, 0xFFFFFF00, 0xFF00FF66, 0xFFFFFF00, 0xFFFF3B30, 0x44FFFFFF, 0xFFFFFF00),
    };

    public static Theme get(int id) {
        if (id < 0) id = 0;
        if (id >= ALL.length) id = ALL.length - 1;
        return ALL[id];
    }

    /** 跟随系统深色模式：夜间用深色主题，日间用浅色主题；关闭跟随时用用户选的主题 */
    public static Theme effective(android.content.Context c) {
        if (Prefs.followSystem(c)) {
            int mode = c.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES ? ALL[0] : ALL[1];
        }
        return get(Prefs.themeId(c));
    }

    public static String[] names() {
        String[] n = new String[ALL.length];
        for (int i = 0; i < ALL.length; i++) n[i] = ALL[i].name;
        return n;
    }

    /** 按百分比调整颜色透明度（0-100） */
    public static int alpha(int color, int pct) {
        if (pct < 0) pct = 0;
        if (pct > 100) pct = 100;
        int a = (color >>> 24) * pct / 100;
        return (a << 24) | (color & 0x00FFFFFF);
    }
}
