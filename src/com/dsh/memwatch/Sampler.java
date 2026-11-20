package com.dsh.memwatch;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * 采样引擎（单例）。
 *
 * 线程模型：
 *  - 主线程：ticker 定时投递一次采样任务；负责回调监听者。
 *  - 采样线程：唯一一条，串行执行，绝不并发改 Snap。
 *  - 用 inFlight 闸门保证「上一次结果被主线程消费完」才投下一次，
 *    因此监听者在 onSample 里读 Snap 是安全的（回调结束前不允许下一轮改写）。
 *
 * 降级策略：
 *  - 有 root：ps 拿全部进程 RSS；按需 dumpsys meminfo 拿 PSS（更准、更慢）。
 *  - 无 root：ActivityManager 只能拿到自身进程，界面会明确提示"受限"。
 */
public final class Sampler {

    public interface Listener {
        /** 在主线程回调；请在回调内把需要的数据拷贝走，回调结束后 Snap 可能被下一轮改写。 */
        void onSample(Snap s);
    }

    private static Sampler INSTANCE;

    public static synchronized Sampler get(Context c) {
        if (INSTANCE == null) INSTANCE = new Sampler(c.getApplicationContext());
        return INSTANCE;
    }

    private final Context ctx;
    private final ActivityManager am;
    private final PowerManager power;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final Snap snap = new Snap();
    private final ProcPool rawPool = new ProcPool();
    private final ProcPool groupPool = new ProcPool();
    private final ArrayList<Proc> raw = new ArrayList<>(1200);
    private final ArrayList<Proc> grouped = new ArrayList<>(256);
    private final HashMap<String, Proc> byPkg = new HashMap<>(256);
    private final HashMap<Integer, Long> pssByPid = new HashMap<>(256);
    private final ArrayList<Listener> listeners = new ArrayList<>(3);

    private final ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
    private final Debug.MemoryInfo selfInfo = new Debug.MemoryInfo();
    private final int selfPid = Process.myPid();

    private final ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<>(1);
    private static final Object STOP = new Object();
    private final Thread worker;

    private volatile boolean running;
    private volatile boolean inFlight;
    private int tick;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!running) return;
            int interval = Prefs.interval(ctx);
            boolean screenOn = !Prefs.screenOnly(ctx) || power.isInteractive();
            if (screenOn) queue.offer(STOP_MARK);      // 队列满即丢弃本轮，绝不排队
            main.postDelayed(this, Math.max(200, interval));
        }
    };

    private static final Object STOP_MARK = new Object();

    private final Runnable notifier = new Runnable() {
        @Override public void run() {
            try {
                for (int i = 0; i < listeners.size(); i++) {
                    try { listeners.get(i).onSample(snap); } catch (Throwable ignore) {}
                }
                snap.pushHist(snap.usedPct());
            } finally {
                inFlight = false;
            }
        }
    };

    private Sampler(Context c) {
        ctx = c;
        am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        power = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        worker = new Thread(new Runnable() {
            @Override public void run() {
                while (true) {
                    Object o;
                    try { o = queue.take(); } catch (InterruptedException e) { return; }
                    if (o == STOP) return;
                    try {
                        sample();
                    } catch (Throwable t) {
                        snap.failCount++;
                    }
                    main.post(notifier);
                }
            }
        }, "memwatch-sampler");
        worker.setDaemon(true);
        worker.start();
    }

    public Snap snapshot() {
        return snap;
    }

    public synchronized void addListener(Listener l) {
        if (l == null || listeners.contains(l)) return;
        listeners.add(l);
        if (listeners.size() == 1) start();
    }

    public synchronized void removeListener(Listener l) {
        listeners.remove(l);
        if (listeners.isEmpty()) stop();
    }

    /** 立即采样一次（切换到后台/打开界面时用） */
    public void kick() {
        if (running && !inFlight) offer();
    }

    private void start() {
        running = true;
        tick = 0;
        main.removeCallbacks(ticker);
        main.post(ticker);
        offer();
    }

    private void stop() {
        running = false;
        main.removeCallbacks(ticker);
    }

    private void offer() {
        if (inFlight) return;
        inFlight = true;
        if (!queue.offer(STOP_MARK)) inFlight = false;
    }

    // ------------------------------------------------------------------ 采样

    private void sample() {
        long t0 = SystemClock.elapsedRealtime();
        Snap s = snap;
        s.failCount = 0;

        // 1. 系统内存：全版本可用，无权限要求
        try {
            am.getMemoryInfo(memInfo);
            s.totalKb = memInfo.totalMem / 1024L;
            s.availKb = memInfo.availMem / 1024L;
            s.thresholdKb = memInfo.threshold / 1024L;
            s.lowMem = memInfo.lowMemory;
        } catch (Throwable t) {
            s.lowMem = false;
        }
        if (s.usedKb <= 0 || s.totalKb > 0) {
            s.usedKb = s.totalKb > s.availKb ? s.totalKb - s.availKb : 0;
        }

        // 2. 自身进程明细
        readSelf(s);

        // 3. 进程表
        s.begin();
        rawPool.rewind();
        groupPool.rewind();
        byPkg.clear();
        raw.clear();
        grouped.clear();     // ← 必须清：否则每轮都把上一轮结果再追加一遍，界面出现大量重复行并持续吃内存

        boolean root = RootReader.available();
        boolean shz = false;
        if (!root) {
            try { shz = ShizukuShell.available(); } catch (Throwable ignore) {}
        }
        if (!root && !shz) {
            try {
                if (ShizukuShell.hasPermission()) {
                    s.shellError = "已授权但通道不可用：" + ShizukuShell.lastError();
                }
            } catch (Throwable ignore) {}
        }
        boolean shell = root || shz;
        s.shellKind = root ? 1 : (shz ? 2 : 0);
        Log.i("MemWatch", "sample: root=" + root + " shizuku=" + shz + " kind=" + s.shellKind);
        s.root = shell;
        s.precise = false;
        tick++;

        if (shell) {
            s.backend = root ? "su" : "shizuku";
            String out = exec(shell, "ps -A -o PID,RSS,NAME", 4000);
            int n = Parser.parsePs(out, raw, rawPool);
            Log.i("MemWatch", "ps out=" + (out == null ? -1 : out.length()) + "B parsed=" + n + "/" + raw.size());
            if (n == 0) {
                s.shellError = out == null ? ("shizuku: " + ShizukuShell.lastError()) : "ps 输出为空";
                // ps 格式不兼容时退回受限路径
                shell = false;
                s.root = false;
                s.shellKind = 0;
            } else {
                s.procCount = n;
            }
        }

        if (shell) {
            int interval = Math.max(200, Prefs.interval(ctx));
            int every = Math.max(3, 8000 / interval);
            if (Prefs.precise(ctx) && (tick % every == 1 || tick == 1)) {
                pssByPid.clear();
                long pt0 = SystemClock.elapsedRealtime();
                String dump = exec(shell, "dumpsys meminfo", 6000);
                int pn = Parser.parseDumpsysPss(dump, pssByPid);
                s.suCostMs = SystemClock.elapsedRealtime() - pt0;
                if (pn > 0) {
                    s.precise = true;
                    s.backend = (s.shellKind == 1 ? "su" : "shizuku") + "/PSS";
                    for (int i = 0; i < raw.size(); i++) {
                        Proc p = raw.get(i);
                        Long v = pssByPid.get(p.pid);
                        if (v != null) p.pssKb = v;
                    }
                }
            }
            aggregate(s, Prefs.showSystem(ctx));
        } else {
            s.backend = "自身";
            readRestricted(s);
        }

        // 4. 收尾
        s.ts = System.currentTimeMillis();
        s.costMs = SystemClock.elapsedRealtime() - t0;
        s.intervalMs = Prefs.interval(ctx);
        for (int i = 0; i < s.procs.size(); i++) {
            Proc p = s.procs.get(i);
            if (p.app && p.pkg != null) p.leak = leaks.update(p.pkg, p.kb(), s.precise);
        }

        String[] pins = Prefs.pinned(ctx);
        if (pins.length > 0) {
            pinRank.clear();
            for (int i = 0; i < pins.length; i++) pinRank.put(pins[i], i);
            Collections.sort(s.procs, BY_PIN);
        } else {
            Collections.sort(s.procs, BY_MEM);
        }
    }

    /** 有 root 用 root，否则走 Shizuku，两条通道输出格式一致 */
    private static String exec(boolean dummyRoot, String cmd, int timeoutMs) {
        String out = RootReader.available() ? RootReader.exec(cmd, timeoutMs) : null;
        if (out == null || out.length() == 0) {
            try { out = ShizukuShell.exec(cmd, timeoutMs); } catch (Throwable ignore) {}
        }
        return out;
    }

    private final HashMap<String, Integer> pinRank = new HashMap<>(8);
    private final LeakTracker leaks = new LeakTracker();

    private final Comparator<Proc> BY_PIN = new Comparator<Proc>() {
        @Override public int compare(Proc a, Proc b) {
            int ra = rankOf(a), rb = rankOf(b);
            if (ra != rb) return ra - rb;
            long ka = a.kb(), kb = b.kb();
            if (ka != kb) return ka > kb ? -1 : 1;
            return a.name.compareTo(b.name);
        }

        private int rankOf(Proc p) {
            if (p.pkg == null) return Integer.MAX_VALUE;
            Integer r = pinRank.get(p.pkg);
            return r == null ? Integer.MAX_VALUE : r;
        }
    };

    private static final Comparator<Proc> BY_MEM = new Comparator<Proc>() {
        @Override public int compare(Proc a, Proc b) {
            long ka = a.kb(), kb = b.kb();
            if (ka != kb) return ka > kb ? -1 : 1;
            return a.name.compareTo(b.name);
        }
    };

    private void readSelf(Snap s) {
        try {
            Debug.getMemoryInfo(selfInfo);
            s.selfPss = selfInfo.getTotalPss();
            s.selfPrivate = selfInfo.getTotalPrivateDirty() + selfInfo.getTotalPrivateClean();
        } catch (Throwable ignore) {}

        if (Build.VERSION.SDK_INT >= 23) {
            s.selfJava = statOf(selfInfo, "summary.java-heap");
            s.selfNative = statOf(selfInfo, "summary.native-heap");
            s.selfCode = statOf(selfInfo, "summary.code");
            s.selfStack = statOf(selfInfo, "summary.stack");
            s.selfGfx = statOf(selfInfo, "summary.graphics");
            s.selfOther = statOf(selfInfo, "summary.private-other");
            s.selfSwap = statOf(selfInfo, "summary.total-swap");
        } else {
            s.selfJava = selfInfo.dalvikPss;
            s.selfNative = selfInfo.nativePss;
            s.selfOther = selfInfo.otherPss;
            s.selfCode = 0;
            s.selfStack = 0;
            s.selfGfx = 0;
            s.selfSwap = 0;
        }
    }

    private static long statOf(Debug.MemoryInfo mi, String key) {
        try {
            String v = mi.getMemoryStat(key);
            if (v == null) return 0;
            return Long.parseLong(v.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    /** root 路径：按包名合并多进程，生成展示用列表 */
    private void aggregate(Snap s, boolean showSystem) {
        long sum = 0;
        for (int i = 0; i < raw.size(); i++) {
            Proc p = raw.get(i);
            sum += p.kb();
            boolean isSelf = p.pid == selfPid;
            String pkg = Proc.pkgOf(p.name);
            if (pkg == null) {
                if (!showSystem) continue;
                Proc g = groupPool.obtain();
                g.pid = p.pid;
                g.name = p.name;
                g.rssKb = p.rssKb;
                g.pssKb = p.pssKb;
                g.app = false;
                g.self = isSelf;
                grouped.add(g);
                continue;
            }
            Proc g = byPkg.get(pkg);
            if (g == null) {
                g = groupPool.obtain();
                g.pkg = pkg;
                g.name = pkg;
                g.label = Labels.of(ctx, pkg);
                g.app = true;
                byPkg.put(pkg, g);
                grouped.add(g);
            }
            g.rssKb += p.rssKb;
            g.pssKb += p.pssKb;
            g.procCount++;
            if (isSelf) g.self = true;
        }
        s.procSumKb = sum;
        s.procs.clear();
        s.procs.addAll(grouped);
        s.topSelf = byPkg.get(ctx.getPackageName());
    }

    /** 无 root：只能拿到自己，尽量多给点信息，但绝不编造别的应用内存 */
    private void readRestricted(Snap s) {
        int count = 1;
        Proc self = groupPool.obtain();
        self.pid = selfPid;
        self.name = ctx.getPackageName();
        self.pkg = ctx.getPackageName();
        self.self = true;
        self.app = true;
        self.label = Labels.of(ctx, ctx.getPackageName());
        self.pssKb = s.selfPss;
        self.rssKb = s.selfPss;

        try {
            List<ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
            if (list != null) {
                count = list.size();
                int[] pids = new int[list.size()];
                for (int i = 0; i < list.size(); i++) pids[i] = list.get(i).pid;
                Debug.MemoryInfo[] infos = am.getProcessMemoryInfo(pids);
                long sum = 0;
                for (int i = 0; i < infos.length; i++) {
                    long pss = infos[i] == null ? 0 : infos[i].getTotalPss();
                    sum += pss;
                    if (pids[i] == selfPid) {
                        self.pssKb = pss;
                        self.rssKb = pss;
                    }
                }
                s.procSumKb = sum;
            }
        } catch (Throwable ignore) {}

        s.procCount = count;
        if (s.procSumKb <= 0) s.procSumKb = s.selfPss;
        s.procs.clear();
        s.procs.add(self);
        s.topSelf = self;
    }
}
