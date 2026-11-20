package com.dsh.memwatch;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.LruCache;

/**
 * 包名 -> 应用显示名（例如 com.tencent.mm -> 微信）。
 *
 * 只在采样线程里调用，结果进 LruCache 后每帧只是哈希查询；
 * 查不到（系统守护进程、已卸载）就原样返回包名，不抛异常。
 */
public final class Labels {

    private static final LruCache<String, String> CACHE = new LruCache<>(512);
    private static PackageManager pm;

    private Labels() {}

    public static String of(Context c, String pkg) {
        if (pkg == null || pkg.length() == 0) return "";
        String hit = CACHE.get(pkg);
        if (hit != null) return hit;

        String label = pkg;
        try {
            if (pm == null) pm = c.getApplicationContext().getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence cs = pm.getApplicationLabel(ai);
            if (cs != null && cs.length() > 0) label = cs.toString().trim();
        } catch (Throwable ignore) {
            // 系统进程 / 已卸载 / 权限限制
        }
        if (label.length() == 0) label = pkg;
        CACHE.put(pkg, label);
        return label;
    }

    /** 名字相同或太长时给个短名，供悬浮窗这种窄区域用 */
    public static String shortOf(Context c, String pkg) {
        String label = of(c, pkg);
        if (label.length() <= 10) return label;
        int dot = pkg.lastIndexOf('.');
        return dot > 0 && dot + 1 < pkg.length() ? pkg.substring(dot + 1) : label;
    }

    public static void clear() {
        CACHE.evictAll();
    }
}
