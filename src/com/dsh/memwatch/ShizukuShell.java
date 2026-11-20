package com.dsh.memwatch;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/**
 * Shizuku 通道：没有 ROOT 时借 Shizuku 的 shell 身份执行 ps / dumpsys，
 * 从而拿到「所有应用」的内存。
 *
 * 关键实现点（都在设备上踩过）：
 *  1. 命令走 IShizukuService.newProcess（AIDL 事务号 7），不再反射私有方法；
 *  2. 输出必须「读到 EOF」判定结束，不能用轮询 exitValue —— 进程还在跑时
 *     exitValue 会抛 IllegalThreadStateException，一旦被吞成 -1 就会立刻返回空串；
 *  3. 所有异常都记到 logcat（tag MemWatch），失败原因同时留在 lastError 给界面显示。
 */
final class ShizukuShell {

    private static final String TAG = "MemWatch";
    static final int REQUEST_CODE = 0x5A1;

    /** Shizuku 管理器的真实包名是 moe.shizuku.privileged.api（历史版本用过 manager） */
    private static final String[] PKGS = {"moe.shizuku.privileged.api", "moe.shizuku.manager"};

    private static volatile boolean binderReady;
    private static volatile String lastError = "";
    private static boolean inited;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final java.util.ArrayList<Runnable> pending = new java.util.ArrayList<>(2);

    private ShizukuShell() {}

    static synchronized void init() {
        if (inited) return;
        if (Build.VERSION.SDK_INT < 24) return;
        inited = true;
        try {
            Shizuku.addBinderReceivedListenerSticky(new Shizuku.OnBinderReceivedListener() {
                @Override public void onBinderReceived() {
                    binderReady = true;
                    Log.i(TAG, "shizuku binder received");
                    main.post(new Runnable() {
                        @Override public void run() {
                            for (Runnable r : pending) r.run();
                            pending.clear();
                        }
                    });
                }
            });
            Shizuku.addBinderDeadListener(new Shizuku.OnBinderDeadListener() {
                @Override public void onBinderDead() {
                    binderReady = false;
                    Log.w(TAG, "shizuku binder dead");
                }
            });
            if (Shizuku.pingBinder()) {
                binderReady = true;
                Log.i(TAG, "shizuku ping ok, ver=" + Shizuku.getVersion() + " uid=" + Shizuku.getUid());
            } else {
                Log.w(TAG, "shizuku ping failed");
            }
        } catch (Throwable t) {
            Log.e(TAG, "shizuku init failed", t);
        }
    }

    static void whenReady(Runnable r) {
        if (binderReady) main.post(r);
        else pending.add(r);
    }

    static boolean isBinderReady() {
        return binderReady;
    }

    static boolean isInstalled(Context c) {
        for (String p : PKGS) {
            try {
                c.getPackageManager().getPackageInfo(p, 0);
                return true;
            } catch (Throwable ignore) {
            }
        }
        return false;
    }

    static boolean isServiceAlive() {
        if (Build.VERSION.SDK_INT < 24) return false;
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean hasPermission() {
        if (Build.VERSION.SDK_INT < 24) return false;
        try {
            return binderReady && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    static void requestPermission() {
        if (Build.VERSION.SDK_INT < 24) return;
        try {
            Shizuku.requestPermission(REQUEST_CODE);
        } catch (Throwable t) {
            Log.e(TAG, "requestPermission failed", t);
        }
    }

    static void setPermissionCallback(final Runnable onGranted) {
        if (Build.VERSION.SDK_INT < 24) return;
        try {
            Shizuku.addRequestPermissionResultListener(new Shizuku.OnRequestPermissionResultListener() {
                @Override public void onRequestPermissionResult(int requestCode, int grantResult) {
                    Log.i(TAG, "permission result code=" + requestCode + " grant=" + grantResult);
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        for (Runnable r : pending) r.run();
                        pending.clear();
                        if (onGranted != null) main.post(onGranted);
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "addPermissionCallback failed", t);
        }
    }

    static boolean available() {
        return Build.VERSION.SDK_INT >= 24 && hasPermission();
    }

    static String lastError() {
        return lastError;
    }

    /** 以 shell 身份执行一条命令；不可用/超时返回 null */
    static String exec(String cmd, int timeoutMs) {
        if (Build.VERSION.SDK_INT < 24) return null;
        if (!hasPermission()) {
            lastError = "未授权";
            return null;
        }
        IRemoteProcess rp = null;
        try {
            IBinder binder = Shizuku.getBinder();
            if (binder == null) {
                lastError = "binder 为空";
                Log.w(TAG, "exec: binder null");
                return null;
            }
            IShizukuService svc = IShizukuService.Stub.asInterface(binder);
            rp = svc.newProcess(new String[]{"sh", "-c", cmd}, null, null);
            if (rp == null) {
                lastError = "newProcess 返回空";
                Log.w(TAG, "exec: newProcess null");
                return null;
            }

            final StringBuilder out = new StringBuilder(8192);
            Thread so = readerThread(rp.getInputStream(), out, "out");
            Thread se = readerThread(rp.getErrorStream(), out, "err");
            so.start();
            se.start();

            // 读到 EOF 即命令结束；这里只做超时兜底
            so.join(timeoutMs);
            boolean timeout = so.isAlive();
            if (timeout) {
                try { rp.destroy(); } catch (Throwable ignore) {}
            }
            se.join(300);

            lastError = timeout ? "执行超时" : "";
            Log.i(TAG, "exec[" + cmd + "] out=" + out.length() + "B timeout=" + timeout);
            return out.toString();
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.e(TAG, "exec failed: " + cmd, t);
            return null;
        } finally {
            if (rp != null) {
                try { rp.destroy(); } catch (Throwable ignore) {}
            }
        }
    }

    private static Thread readerThread(final ParcelFileDescriptor pfd, final StringBuilder sink,
                                       final String name) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                FileInputStream in = null;
                try {
                    if (pfd == null) return;
                    in = new FileInputStream(pfd.getFileDescriptor());
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        String s = new String(buf, 0, n, "UTF-8");
                        synchronized (sink) { sink.append(s); }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "read-" + name + " failed: " + t);
                } finally {
                    if (in != null) try { in.close(); } catch (Throwable ignore) {}
                    try { pfd.close(); } catch (Throwable ignore) {}
                }
            }
        }, "memwatch-shz-" + name);
        t.setDaemon(true);
        return t;
    }
}
