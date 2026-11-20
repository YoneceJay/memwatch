package com.dsh.memwatch;

import java.util.ArrayList;

/** 一次采样的完整快照。复用同一实例，字段整体覆盖。 */
public final class Snap {

    // 系统内存
    public long totalKb;
    public long availKb;
    public long usedKb;
    public long thresholdKb;
    public boolean lowMem;

    // 自身进程
    public long selfPss;
    public long selfJava;
    public long selfNative;
    public long selfCode;
    public long selfStack;
    public long selfGfx;
    public long selfOther;
    public long selfSwap;
    public long selfPrivate;

    // 进程表
    public int procCount;
    public long procSumKb;
    public final ArrayList<Proc> procs = new ArrayList<>(64);
    public Proc topSelf;                       // 指向自身条目（可能为 null）

    // 元信息
    public int shellKind;                      // 0 无 / 1 root / 2 shizuku
    public boolean root;                       // 是否有 shell 级能力（root 或 shizuku）
    public boolean precise;                    // 本次是否为 PSS 精确数据
    public String backend = "系统接口";
    public String shellError = "";
    public long ts;
    public long costMs;
    public int intervalMs = 2000;
    public long suCostMs;
    public int failCount;

    // 历史（占用率曲线，环形缓冲，固定 90 点）
    public final float[] hist = new float[90];
    public int histHead;
    public int histSize;

    public void begin() {
        procs.clear();
        topSelf = null;
        procCount = 0;
        procSumKb = 0;
    }

    public int usedPct() {
        return Fmt.pct(usedKb, totalKb);
    }

    public void pushHist(float v) {
        hist[histHead] = v;
        histHead = (histHead + 1) % hist.length;
        if (histSize < hist.length) histSize++;
    }
}
