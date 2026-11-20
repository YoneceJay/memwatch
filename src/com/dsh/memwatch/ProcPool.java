package com.dsh.memwatch;

import java.util.ArrayList;

/** Proc 对象池：每次采样复用同一批对象，避免高频刷新制造垃圾。 */
public final class ProcPool {

    private final ArrayList<Proc> items = new ArrayList<>(256);
    private int used;

    public void rewind() {
        used = 0;
    }

    public Proc obtain() {
        if (used < items.size()) {
            Proc p = items.get(used++);
            p.reset();
            return p;
        }
        Proc p = new Proc();
        items.add(p);
        used++;
        return p;
    }

    public int capacity() {
        return items.size();
    }
}
