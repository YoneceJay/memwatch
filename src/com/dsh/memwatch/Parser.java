package com.dsh.memwatch;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * 输出解析器。纯 Java，无 Android 依赖，可在容器里用真实 dump 离线验证。
 *
 * 支持两种设备输出：
 *  1) ps -A -o PID,RSS,NAME
 *  2) dumpsys meminfo 的 "Total PSS by process:" / "Total RSS by process:" 段
 */
public final class Parser {

    private Parser() {}

    /**
     * 解析 `ps -A -o PID,RSS,NAME`。
     * 每行形如： "    1   7608 init" / "  221  97416 com.larus.nova:smp"
     */
    public static int parsePs(String out, ArrayList<Proc> dst, ProcPool pool) {
        if (out == null) return 0;
        int n = 0;
        int len = out.length();
        int i = 0;
        while (i < len) {
            int eol = out.indexOf('\n', i);
            if (eol < 0) eol = len;
            int end = eol;
            if (end > i && out.charAt(end - 1) == '\r') end--;

            int p = i;
            while (p < end && (out.charAt(p) == ' ' || out.charAt(p) == '\t')) p++;
            int pidStart = p;
            while (p < end && isDigit(out.charAt(p))) p++;
            int pidEnd = p;
            if (pidEnd > pidStart) {
                while (p < end && (out.charAt(p) == ' ' || out.charAt(p) == '\t')) p++;
                int rssStart = p;
                while (p < end && isDigit(out.charAt(p))) p++;
                int rssEnd = p;
                if (rssEnd > rssStart) {
                    while (p < end && (out.charAt(p) == ' ' || out.charAt(p) == '\t')) p++;
                    if (p < end) {
                        int pid = parseInt(out, pidStart, pidEnd);
                        long rss = parseLong(out, rssStart, rssEnd);
                        String name = out.substring(p, end);
                        if (pid > 0 && name.length() > 0 && name.indexOf("PID") < 0) {
                            Proc pr = pool.obtain();
                            pr.pid = pid;
                            pr.rssKb = rss;
                            pr.name = name;
                            n++;
                            dst.add(pr);
                        }
                    }
                }
            }
            i = eol + 1;
        }
        return n;
    }

    /**
     * 解析 dumpsys meminfo 的 "Total PSS by process:" 段，填充 pid -> PSS(KB)。
     * 行形如： "  2,168,159K: com.ss.android.ugc.aweme (pid 21640 / activities)"
     *          "    492,380K: com.tencent.mm:appbrand0 (pid 23042) (user 999)"
     */
    public static int parseDumpsysPss(String out, HashMap<Integer, Long> pidToPss) {
        if (out == null) return 0;
        int from = out.indexOf("Total PSS by process:");
        if (from < 0) from = out.indexOf("Total RSS by process:");
        if (from < 0) return 0;

        int n = 0;
        int len = out.length();
        int i = out.indexOf('\n', from);
        if (i < 0) return 0;
        i++;
        while (i < len) {
            int eol = out.indexOf('\n', i);
            if (eol < 0) eol = len;
            int end = eol;
            if (end > i && out.charAt(end - 1) == '\r') end--;

            int p = i;
            while (p < end && out.charAt(p) == ' ') p++;
            if (p >= end) break;                       // 空行 = 段结束
            int valStart = p;
            while (p < end && (isDigit(out.charAt(p)) || out.charAt(p) == ',')) p++;
            if (p == valStart || p >= end || out.charAt(p) != 'K') {
                // 不是数据行，段已结束（遇到下一个标题）
                break;
            }
            long kb = parseLong(out, valStart, p);
            int pidIdx = out.indexOf("(pid ", p);
            if (pidIdx < 0 || pidIdx > end) { i = eol + 1; continue; }
            int q = pidIdx + 5;
            int pidStart = q;
            while (q < end && isDigit(out.charAt(q))) q++;
            if (q > pidStart) {
                int pid = parseInt(out, pidStart, q);
                if (pid > 0 && kb > 0) {
                    Long old = pidToPss.get(pid);
                    if (old == null) { pidToPss.put(pid, kb); n++; }
                }
            }
            i = eol + 1;
        }
        return n;
    }

    /** 进程名是否应用进程（形如 com.xxx.yyy） */
    public static boolean isAppProcessName(String name) {
        return Proc.pkgOf(name) != null;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    static int parseInt(String s, int from, int to) {
        int v = 0;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') break;
            v = v * 10 + (c - '0');
            if (v < 0) return 0;
        }
        return v;
    }

    static long parseLong(String s, int from, int to) {
        long v = 0;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c == ',') continue;
            if (c < '0' || c > '9') break;
            v = v * 10 + (c - '0');
            if (v < 0) return 0;
        }
        return v;
    }
}
