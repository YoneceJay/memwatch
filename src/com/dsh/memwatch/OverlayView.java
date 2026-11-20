package com.dsh.memwatch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/**
 * 悬浮窗自绘视图。
 *
 * 形态：0=圆球 1=简略（一排数据） 2=详细（带 Top N 列表）。
 * 过渡动画**只作用于 Canvas**（缩放 + 透明度），不碰窗口属性，
 * 因此不会出现窗口闪烁，也不会拖慢点击响应。
 */
public final class OverlayView extends View {

    public static final int MODE_BALL = 0;
    public static final int MODE_COMPACT = 1;
    public static final int MODE_DETAIL = 2;

    private static final long ANIM_MS = 150L;

    private final Paint pBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBig = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCurve = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private float density = 1f;
    private float pad = 9f;
    private float lineH = 24f;
    private float rowH = 20f;
    private int textSizeSp = 11;

    private Theme theme = Theme.ALL[4];
    private int bgAlphaPct = 78;
    private int textAlphaPct = 100;

    private int mode = MODE_DETAIL;
    private int usedPct;
    private String pctText = "0%";
    private String capText = "";
    private String line2 = "";
    private final String[] names = new String[24];
    private final String[] vals = new String[24];
    private int rows;
    private boolean alert;
    private boolean showBar = true;
    private boolean showCurve = true;
    private int alertPct = 85;
    private final float[] curve = new float[90];
    private int curveSize;
    private int curveHead;

    // 过渡动画（Canvas 级）
    private boolean animating;
    private long animStart;
    private float animT = 1f;

    private String lastKey = "";

    public OverlayView(Context c) {
        super(c);
        density = getResources().getDisplayMetrics().density;
        pBg.setStyle(Paint.Style.FILL);
        pBar.setStyle(Paint.Style.FILL);
        pTrack.setStyle(Paint.Style.FILL);
        pCurve.setStyle(Paint.Style.STROKE);
        pRing.setStyle(Paint.Style.STROKE);
        pRing.setStrokeWidth(3f * density);
        pBig.setFakeBoldText(true);
        apply();
    }

    private void apply() {
        float px = textSizeSp * density;
        pBig.setTextSize(px * 1.35f);
        pText.setTextSize(px * 1.02f);
        pDim.setTextSize(px * 0.92f);
        pCurve.setStrokeWidth(1.4f * density);
        pad = 9f * density;
        lineH = px * 1.7f;
        rowH = px * 1.42f;
    }

    public void setMode(int m) {
        if (m < 0 || m > 2) m = 2;
        if (mode == m) return;
        mode = m;
        lastKey = "";
        requestLayout();
        playTransition();
    }

    public int mode() {
        return mode;
    }

    public void setStyle(Theme t, int bgAlpha, int textAlpha, int sizeSp, int alertPct,
                         boolean showBar, boolean showCurve) {
        theme = t == null ? Theme.ALL[4] : t;
        bgAlphaPct = bgAlpha;
        textAlphaPct = textAlpha;
        textSizeSp = sizeSp;
        this.alertPct = alertPct;
        this.showBar = showBar;
        this.showCurve = showCurve;
        apply();
        requestLayout();
        invalidate();
    }

    /** 形态切换后的入场动画：150ms 缓出，Canvas 缩放 + 淡入 */
    public void playTransition() {
        animating = true;
        animStart = SystemClock.uptimeMillis();
        postInvalidateOnAnimation();
    }

    public void bind(Snap s, int topN) {
        int pct = s.usedPct();
        int limit = mode == MODE_DETAIL ? (topN <= 0 ? s.procs.size() : Math.min(topN, s.procs.size())) : 0;
        if (limit > names.length) limit = names.length;

        StringBuilder key = new StringBuilder(64);
        key.append(pct).append('|').append(s.procCount).append('|').append(limit).append('|').append(mode);
        for (int i = 0; i < limit; i++) key.append('|').append(s.procs.get(i).kb());
        String k = key.toString();
        if (k.equals(lastKey) && curveSize == s.histSize) return;
        lastKey = k;

        usedPct = pct;
        alert = usedPct >= alertPct || s.lowMem;
        pctText = usedPct + "%";
        capText = Fmt.size(s.usedKb) + " / " + Fmt.size(s.totalKb);
        line2 = "本应用 " + Fmt.size(s.selfPss) + " · 进程 " + s.procCount;

        rows = limit;
        for (int i = 0; i < limit; i++) {
            Proc pr = s.procs.get(i);
            names[i] = shortName(pr);
            vals[i] = Fmt.size(pr.kb());
        }
        for (int i = limit; i < names.length; i++) {
            names[i] = null;
            vals[i] = null;
        }

        curveSize = s.histSize;
        curveHead = s.histHead;
        System.arraycopy(s.hist, 0, curve, 0, curve.length);
        invalidate();
    }

    private String shortName(Proc pr) {
        if (pr.self) return "本应用";
        String label = pr.label != null && pr.label.length() > 0 ? pr.label : pr.pkg;
        if (pr.leak) return "↑ " + (label == null ? "" : label);
        if (label != null && label.length() > 0) return label;
        if (!pr.app) return pr.name == null ? "" : pr.name;
        return pr.pkg == null ? "" : pr.pkg;
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        if (mode == MODE_BALL) {
            int d = (int) Math.max(54f * density, textSizeSp * density * 4.4f);
            setMeasuredDimension(d, d);
            return;
        }
        float w = pBig.measureText(pctText) + 8f * density + pDim.measureText(capText);
        float w2 = pDim.measureText(line2);
        if (w2 > w) w = w2;
        if (mode == MODE_DETAIL && rows > 0) {
            for (int i = 0; i < rows; i++) {
                float rw = pDim.measureText(names[i] == null ? "" : names[i])
                        + pDim.measureText(vals[i] == null ? "" : vals[i]) + 22f * density;
                if (rw > w) w = rw;
            }
        }
        w += pad * 2;
        float minW = 138f * density;
        if (w < minW) w = minW;

        float h = pad + lineH;
        if (showBar) h += 9f * density;
        h += lineH * 0.8f;
        if (mode == MODE_DETAIL && rows > 0) h += 6f * density + rows * rowH;
        if (showCurve && mode != MODE_COMPACT) h += 20f * density;
        h += pad * 0.8f;
        setMeasuredDimension((int) Math.ceil(w), (int) Math.ceil(h));
    }

    @Override protected void onDraw(Canvas canvas) {
        if (animating) {
            float t = (SystemClock.uptimeMillis() - animStart) / (float) ANIM_MS;
            if (t >= 1f) {
                animating = false;
                animT = 1f;
            } else {
                float e = 1f - (1f - t) * (1f - t);
                animT = 0.45f + 0.55f * e;          // 透明度 45% -> 100%
                postInvalidateOnAnimation();
            }
        }
        float scale = 0.90f + 0.10f * animT;
        int w = getWidth(), h = getHeight();
        int save = canvas.save();
        canvas.scale(scale, scale, w / 2f, h / 2f);

        int ta = Math.max(0, (int) (textAlphaPct * animT));
        int ba = Math.max(0, (int) (bgAlphaPct * animT));

        if (mode == MODE_BALL) {
            drawBall(canvas, w, h, ta, ba);
        } else {
            drawPanel(canvas, w, h, ta, ba);
        }
        canvas.restoreToCount(save);
    }

    private void drawBall(Canvas canvas, int w, int h, int ta, int ba) {
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) / 2f - pRing.getStrokeWidth();
        pBg.setColor(Theme.alpha(theme.bg, ba));
        canvas.drawCircle(cx, cy, r, pBg);
        pTrack.setColor(Theme.alpha(theme.track, ta));
        canvas.drawCircle(cx, cy, r, pTrack);

        int c = alert ? theme.alert : (usedPct >= alertPct - 12 ? theme.warn : theme.ok);
        pRing.setColor(Theme.alpha(c, ta));
        float sweep = 360f * Math.min(100, Math.max(0, usedPct)) / 100f;
        canvas.drawArc(new RectF(cx - r, cy - r, cx + r, cy + r), -90f, sweep, false, pRing);

        pBig.setColor(Theme.alpha(alert ? theme.alert : theme.text, ta));
        pBig.setTextSize(textSizeSp * density * 1.05f);
        String txt = usedPct + "%";
        float tw = pBig.measureText(txt);
        canvas.drawText(txt, cx - tw / 2f, cy + pBig.getTextSize() * 0.35f, pBig);
    }

    private void drawPanel(Canvas canvas, int w, int h, int ta, int ba) {
        float r = 10f * density;
        pBg.setColor(Theme.alpha(theme.bg, ba));
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, r, r, pBg);

        float x = pad;
        float y = pad + lineH * 0.72f;

        pBig.setColor(Theme.alpha(alert ? theme.alert : theme.text, ta));
        float pw = pBig.measureText(pctText);
        canvas.drawText(pctText, x, y, pBig);
        if (mode != MODE_COMPACT) {
            pDim.setColor(Theme.alpha(theme.dim, ta));
            canvas.drawText(capText, x + pw + 8f * density, y, pDim);
        }

        float cy = pad + lineH;
        if (showBar) {
            drawBar(canvas, x, cy, w - pad * 2, ta);
            cy += 9f * density;
        }

        pDim.setColor(Theme.alpha(theme.dim, ta));
        canvas.drawText(line2, x, cy + lineH * 0.62f, pDim);
        cy += lineH * 0.8f + 1f * density;

        if (mode == MODE_DETAIL && rows > 0) {
            pTrack.setColor(Theme.alpha(theme.track, ta));
            canvas.drawRect(x, cy + 1f * density, w - pad, cy + 1.6f * density, pTrack);
            cy += 6f * density;
            for (int i = 0; i < rows; i++) {
                String name = names[i] == null ? "" : names[i];
                String val = vals[i] == null ? "" : vals[i];
                float vw = pDim.measureText(val);
                float maxName = (w - pad * 2) - vw - 10f * density;
                pText.setColor(Theme.alpha(theme.text, ta));
                canvas.drawText(ellipsize(name, maxName, pText), x, cy + rowH * 0.74f, pText);
                pDim.setColor(Theme.alpha(theme.accent, ta));
                canvas.drawText(val, w - pad - vw, cy + rowH * 0.74f, pDim);
                cy += rowH;
            }
        }

        if (showCurve && mode == MODE_DETAIL && curveSize > 1) {
            pCurve.setColor(Theme.alpha(theme.curve, ta));
            drawCurve(canvas, x, cy + 3f * density, w - pad * 2, 15f * density);
        }
    }

    private void drawBar(Canvas canvas, float x, float y, float w, int ta) {
        float hBar = 4.5f * density;
        rect.set(x, y, x + w, y + hBar);
        pTrack.setColor(Theme.alpha(theme.track, ta));
        canvas.drawRoundRect(rect, hBar, hBar, pTrack);
        float fill = w * usedPct / 100f;
        if (fill > 0) {
            rect.set(x, y, x + Math.max(hBar, fill), y + hBar);
            int c = alert ? theme.alert : (usedPct >= alertPct - 12 ? theme.warn : theme.ok);
            pBar.setColor(Theme.alpha(c, ta));
            canvas.drawRoundRect(rect, hBar, hBar, pBar);
        }
    }

    private void drawCurve(Canvas canvas, float x, float y, float w, float h) {
        if (curveSize < 2) return;
        float step = w / (curve.length - 1);
        float lastX = x, lastY = 0;
        boolean first = true;
        int idx = (curveHead - curveSize + curve.length) % curve.length;
        for (int i = 0; i < curveSize; i++) {
            int p = (idx + i) % curve.length;
            float v = curve[p];
            float px = x + i * step;
            float py = y + h - (h * Math.min(100f, Math.max(0f, v)) / 100f);
            if (!first) canvas.drawLine(lastX, lastY, px, py, pCurve);
            lastX = px;
            lastY = py;
            first = false;
        }
    }

    private String ellipsize(String s, float max, Paint p) {
        if (s == null || max <= 0) return "";
        if (p.measureText(s) <= max) return s;
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (p.measureText(s.substring(0, mid) + "…") <= max) lo = mid; else hi = mid - 1;
        }
        return lo <= 0 ? "…" : s.substring(0, lo) + "…";
    }
}
