package com.dsh.memwatch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;

/**
 * 内存热力方块图：面积正比于占用，二分递归切块（squarify 的简化版），
 * 点方块回调包名。只在打开该页面时创建，不常驻。
 */
public final class TreemapView extends View {

    public interface OnPick { void onPick(String pkg, String label, long kb); }

    private static final int MAX = 24;

    private final Paint pFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final ArrayList<RectF> cells = new ArrayList<>(MAX);
    private final ArrayList<String> pkgs = new ArrayList<>(MAX);
    private final ArrayList<String> labels = new ArrayList<>(MAX);
    private final ArrayList<Long> sizes = new ArrayList<>(MAX);

    private Theme theme = Theme.ALL[4];
    private float density = 1f;
    private int textSizeSp = 10;
    private String emptyText = "暂无可显示的应用";
    private OnPick onPick;

    public TreemapView(Context c) {
        super(c);
        density = getResources().getDisplayMetrics().density;
        pStroke.setStyle(Paint.Style.STROKE);
        pStroke.setStrokeWidth(1.2f * density);
        pText.setTextSize(textSizeSp * density);
        pText.setTextAlign(Paint.Align.CENTER);
        pBg.setStyle(Paint.Style.FILL);
    }

    public void setTheme(Theme t, int textSize) {
        theme = t == null ? Theme.ALL[4] : t;
        textSizeSp = textSize;
        pText.setTextSize(textSizeSp * density);
        invalidate();
    }

    public void setOnPick(OnPick pick) {
        onPick = pick;
    }

    public void setData(Snap s, int topN) {
        cells.clear();
        pkgs.clear();
        labels.clear();
        sizes.clear();
        int n = Math.min(topN <= 0 ? MAX : topN, s.procs.size());
        if (n > MAX) n = MAX;
        for (int i = 0; i < n; i++) {
            Proc p = s.procs.get(i);
            if (p.kb() <= 0) continue;
            pkgs.add(p.pkg != null ? p.pkg : p.name);
            labels.add(p.self ? "本应用" : Labels.of(getContext(), p.pkg));
            sizes.add(p.kb());
        }
        layoutCells(getWidth(), getHeight());   // 尺寸没变也要重排，否则下一帧就是空图
        requestLayout();
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutCells(w, h);
    }

    private void layoutCells(float w, float h) {
        cells.clear();
        int n = sizes.size();
        if (n == 0 || w <= 0 || h <= 0) return;
        long total = 0;
        for (long v : sizes) total += v;
        split(0, n, 0, 0, w, h, total, true);
    }

    /** 二分递归：按累计权重中点切分，横竖交替，面积严格正比 */
    private void split(int from, int to, float x, float y, float w, float h,
                       long total, boolean horizontal) {
        if (from >= to || w <= 0 || h <= 0) return;
        if (to - from == 1) {
            RectF r = new RectF(x, y, x + w, y + h);
            cells.add(r);
            return;
        }
        long half = total / 2, acc = 0;
        int cut = from;
        for (int i = from; i < to - 1; i++) {
            acc += sizes.get(i);
            cut = i + 1;
            if (acc >= half) break;
        }
        long leftTotal = 0;
        for (int i = from; i < cut; i++) leftTotal += sizes.get(i);
        long rightTotal = total - leftTotal;

        if (horizontal) {
            float fw = w * leftTotal / (float) total;
            split(from, cut, x, y, fw, h, leftTotal, false);
            split(cut, to, x + fw, y, w - fw, h, rightTotal, false);
        } else {
            float fh = h * leftTotal / (float) total;
            split(from, cut, x, y, w, fh, leftTotal, true);
            split(cut, to, x, y + fh, w, h - fh, rightTotal, true);
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        pBg.setColor(Theme.alpha(theme.bg, 100));
        canvas.drawRect(0, 0, w, h, pBg);

        if (cells.isEmpty()) {
            pText.setColor(Theme.alpha(theme.dim, 100));
            canvas.drawText(emptyText, w / 2f, h / 2f, pText);
            return;
        }

        int n = Math.min(cells.size(), sizes.size());
        for (int i = 0; i < n; i++) {
            RectF r = cells.get(i);
            int color = shade(theme.accent, i);
            pFill.setColor(Theme.alpha(color, 235));
            canvas.drawRect(r, pFill);
            pStroke.setColor(Theme.alpha(theme.bg, 255));
            canvas.drawRect(r, pStroke);

            if (r.width() > 52 * density && r.height() > 26 * density) {
                pText.setColor(Theme.alpha(theme.text, 255));
                String label = labels.get(i);
                canvas.drawText(ellipsize(label, r.width() - 8 * density), r.centerX(),
                        r.centerY() - 2 * density, pText);
                pText.setColor(Theme.alpha(theme.dim, 255));
                canvas.drawText(Fmt.size(sizes.get(i)), r.centerX(), r.centerY() + 12 * density, pText);
            }
        }
    }

    private int shade(int base, int idx) {
        float f = 0.65f + 0.35f * ((idx % 5) / 4f);
        int a = (base >>> 24);
        int r = (int) (((base >> 16) & 0xFF) * f);
        int g = (int) (((base >> 8) & 0xFF) * f);
        int b = (int) ((base & 0xFF) * f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP && onPick != null) {
            float x = e.getX(), y = e.getY();
            for (int i = 0; i < cells.size() && i < pkgs.size(); i++) {
                if (cells.get(i).contains(x, y)) {
                    onPick.onPick(pkgs.get(i), labels.get(i), sizes.get(i));
                    return true;
                }
            }
        }
        return true;
    }

    private String ellipsize(String s, float max) {
        if (s == null) return "";
        if (pText.measureText(s) <= max) return s;
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (pText.measureText(s.substring(0, mid) + "…") <= max) lo = mid; else hi = mid - 1;
        }
        return lo <= 0 ? "…" : s.substring(0, lo) + "…";
    }
}
