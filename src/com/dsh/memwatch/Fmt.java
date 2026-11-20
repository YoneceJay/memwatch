package com.dsh.memwatch;

/** 轻量格式化，避免 String.format 的分配与开销。 */
public final class Fmt {

    private Fmt() {}

    /** KB -> "12K" / "345M" / "1.24G" */
    public static String size(long kb) {
        if (kb <= 0) return "0";
        if (kb < 1024) return kb + "K";
        long tenthsMb = kb * 10 / 1024;
        if (tenthsMb < 10240) {                    // < 1G
            long i = tenthsMb / 10, d = tenthsMb % 10;
            return d == 0 ? (i + "M") : (i + "." + d + "M");
        }
        long hundredthsGb = kb * 100 / 1048576;    // >= 1G
        long i = hundredthsGb / 100, d = hundredthsGb % 100;
        if (d == 0) return i + "G";
        return d < 10 ? (i + ".0" + d + "G") : (i + "." + d + "G");
    }

    /** 百分比 0..100，total 为 0 时返回 0 */
    public static int pct(long part, long total) {
        if (total <= 0 || part <= 0) return 0;
        long p = part * 100 / total;
        if (p > 100) p = 100;
        return (int) p;
    }

    public static String pctStr(long part, long total) {
        return pct(part, total) + "%";
    }
}
