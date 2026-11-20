package com.dsh.memwatch;

/**
 * 进程/应用内存条目。纯 Java（无 Android 依赖），便于在容器里离线验证解析逻辑。
 * 对象由池复用，取用前请调用 reset()。
 */
public final class Proc {

    public int pid;
    public long rssKb;
    public long pssKb;
    public String name = "";
    public String pkg = "";
    public String label;
    public boolean self;
    public boolean app;      // 有包名的应用进程
    public int procCount = 1;

    public void reset() {
        pid = 0;
        rssKb = 0;
        pssKb = 0;
        name = "";
        pkg = "";
        label = null;
        self = false;
        app = false;
        procCount = 1;
    }

    /** 展示用内存：优先 PSS，没有则 RSS */
    public long kb() {
        return pssKb > 0 ? pssKb : rssKb;
    }

    /** 从进程名推断包名（com.a.b:push -> com.a.b） */
    public static String pkgOf(String processName) {
        if (processName == null || processName.isEmpty()) return null;
        int colon = processName.indexOf(':');
        String base = colon > 0 ? processName.substring(0, colon) : processName;
        if (base.indexOf('.') < 0) return null;          // surfaceflinger 等原生进程
        char c = base.charAt(0);
        if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z')) return null;
        return base;
    }
}
