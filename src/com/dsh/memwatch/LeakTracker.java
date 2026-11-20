package com.dsh.memwatch;

import java.util.HashMap;

/**
 * 简易「内存持续增长 / 疑似泄漏」检测。
 *
 * 每个包保留最近 20 个采样点，判定条件（同时满足）：
 *  - 至少 8 个点；
 *  - 首尾涨幅 ≥ 30MB 且 ≥ 15%；
 *  - 70% 以上的相邻点没有回落（整体单调向上）。
 * 判定只看当前指标口径（RSS 或 PSS），口径切换会重置序列。
 */
public final class LeakTracker {

    private static final int N = 20;
    private static final long MIN_GROW_KB = 30 * 1024;
    private static final int MIN_POINTS = 8;

    private final HashMap<String, long[]> series = new HashMap<>(128);
    private final HashMap<String, Integer> counts = new HashMap<>(128);
    private final HashMap<String, Boolean> flags = new HashMap<>(128);
    private boolean lastPrecise;

    public void reset() {
        series.clear();
        counts.clear();
        flags.clear();
    }

    /** 每轮每个包调一次；返回是否疑似泄漏 */
    public boolean update(String pkg, long kb, boolean precise) {
        if (precise != lastPrecise) {
            reset();
            lastPrecise = precise;
        }
        long[] arr = series.get(pkg);
        if (arr == null) {
            arr = new long[N];
            series.put(pkg, arr);
            counts.put(pkg, 0);
        }
        Integer c = counts.get(pkg);
        int n = c == null ? 0 : c;
        if (n < N) {
            arr[n] = kb;
            n++;
        } else {
            System.arraycopy(arr, 1, arr, 0, N - 1);
            arr[N - 1] = kb;
        }
        counts.put(pkg, n);

        boolean leak = false;
        if (n >= MIN_POINTS) {
            long first = arr[0], last = arr[n - 1];
            long grow = last - first;
            if (first > 0 && grow >= MIN_GROW_KB && grow * 100 / first >= 15) {
                int up = 0;
                for (int i = 1; i < n; i++) if (arr[i] >= arr[i - 1]) up++;
                leak = up * 100 / (n - 1) >= 70;
            }
        }
        flags.put(pkg, leak);
        return leak;
    }

    public boolean isLeak(String pkg) {
        Boolean b = flags.get(pkg);
        return b != null && b;
    }

    public int tracked() {
        return series.size();
    }
}
