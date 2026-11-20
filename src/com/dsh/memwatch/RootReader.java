package com.dsh.memwatch;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * ROOT 执行器：本机 Android 8+ 把 /proc 与其他应用内存接口都锁了，
 * 第三方应用想看到"所有应用"的内存只能走 su。
 *
 * 设计要点：
 *  - 只在需要时执行，结果缓存 60 秒（可用/不可用都缓存），避免反复唤醒 su 进程；
 *  - 每次命令有硬超时，su 弹窗卡住不会拖住采样线程；
 *  - 失败一律返回 null，由上层自动降级到受限模式。
 */
public final class RootReader {

    private static final Object LOCK = new Object();
    private static final String[] CANDIDATES = {"su", "/system/xbin/su", "/system/bin/su", "/sbin/su"};

    private static long lastProbeAt;
    private static boolean available;
    private static String workingBinary;

    private RootReader() {}

    public static boolean available() {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (now - lastProbeAt < 60_000L) return available;
            lastProbeAt = now;
            available = probe();
            return available;
        }
    }

    public static void invalidate() {
        synchronized (LOCK) {
            lastProbeAt = 0L;
        }
    }

    private static boolean probe() {
        String out = exec("id", 3000);
        return out != null && out.contains("uid=0");
    }

    /** 执行一条 root 命令，返回 stdout（含 stderr），超时或不可用返回 null */
    public static String exec(String cmd, int timeoutMs) {
        if (workingBinary != null) {
            String r = run(workingBinary, cmd, timeoutMs);
            if (r != null) return r;
            workingBinary = null;
        }
        for (String bin : CANDIDATES) {
            String r = run(bin, cmd, timeoutMs);
            if (r != null) {
                workingBinary = bin;
                return r;
            }
        }
        return null;
    }

    private static String run(String bin, String cmd, int timeoutMs) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(bin, "-c", cmd);
            pb.redirectErrorStream(true);
            p = pb.start();
            final Process fp = p;
            final StringBuilder sb = new StringBuilder(4096);
            Thread reader = new Thread(new Runnable() {
                @Override public void run() {
                    BufferedReader r = null;
                    try {
                        r = new BufferedReader(new InputStreamReader(fp.getInputStream(), "UTF-8"));
                        char[] buf = new char[8192];
                        int n;
                        while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
                    } catch (Throwable ignore) {
                    } finally {
                        if (r != null) try { r.close(); } catch (Throwable ignore) {}
                    }
                }
            }, "memwatch-su-read");
            reader.setDaemon(true);
            reader.start();

            long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                try {
                    int code = p.exitValue();
                    if (code != 0 && sb.length() == 0) return null;
                    return sb.toString();
                } catch (IllegalThreadStateException running) {
                    if (System.currentTimeMillis() > deadline) {
                        p.destroy();
                        return null;
                    }
                    try { Thread.sleep(15L); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        p.destroy();
                        return null;
                    }
                }
            }
        } catch (Throwable e) {
            return null;
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignore) {}
        }
    }
}
