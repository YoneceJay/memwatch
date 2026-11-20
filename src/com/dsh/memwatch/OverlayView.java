package com.dsh.memwatch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * 悬浮窗自绘视图：一次 Canvas 画完，没有子 View、没有布局、没有图片资源。
 * 主题色、文字透明度、背景透明度都由 Prefs 驱动。
 */
public final class OverlayView extends View {

    private final Paint pBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBig = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCurve = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private float density = 1f;
    private float pad = 9f;
    private float lineH = 24f;
    private float rowH = 20f;
    private int textSizeSp = 11;

    private Theme theme = Theme.ALL[0];
    private int bgAlphaPct = 78;
    private int textAlphaPct = 100;

    private int usedPct;
    private String pctText = "0%";
    private String capText = "";
    private String line2 = "";
    private final String[] names = new String[24];
    private final String[] vals = new String[24];
    private int rows;
    private boolean expanded = true;
    private boolean alert;
    private boolean showBar = true;
    private boolean showCurve = true;
    private int alertPct = 85;
    private final float[] curve = new float[90];
    private int curveSize;
    private int curveHead;

    private String lastKey = "";
    private boolean ball;
    private final Paint pRing = new Paint(Paint.ANTI_ALIAS_FLAG);

    public OverlayView(Context c) {
        super(c);
        density = getResources().getDisplayMetrics().density;
        pBg.setStyle(Paint.Style.FILL);
        pBar.setStyle(Paint.Style.FILL);
        pTrack.setStyle(Paint.Style.FILL);
        pCurve.setStyle(Paint.Style.STROKE);
        pBig.setFakeBoldText(true);
        apply();
    }

    private void apply() {
        float px = textSizeSp * density;
        pBig.setTextSize(px * 1.35f);
        pText.setTextSize(px * 1.02f);
        pDim.setTextSize(px * 0.92f);
        pCurve.setStrokeWidth(1.4f * density);
        pRing.setStyle(Paint.Style.STROKE);
        pRing.setStrokeWidth(3f * density);
        pad = 9f * density;
        lineH = px * 1.7f;
        rowH = px * 1.42f;
    }

    public void setBall(boolean b) {
        if (ball == b) return;
        ball = b;
        lastKey = "";
        requestLayout();
        invalidate();
    }

    public void setStyle(Theme t, int bgAlpha, int textAlpha, int sizeSp, int alertPct,
                         boolean expanded, boolean showBar, boolean showCurve) {
        theme = t == null ? Theme.ALL[0] : t;
        bgAlphaPct = bgAlpha;
        textAlphaPct = textAlpha;
        textSizeSp = sizeSp;
        this.alertPct = alertPct;
        this.expanded = expanded;
        this.showBar = showBar;
        this.showCurve = showCurve;
        apply();
        requestLayout();
        invalidate();
    }

    /** 主线程回调里调用；内容没变就不重绘、不重排 */
    public void bind(Snap s, int topN) {
        int pct = s.usedPct();
        int limit = topN <= 0 ? s.procs.size() : Math.min(topN, s.procs.size());
        if (limit > names.length) limit = names.length;

        StringBuilder key = new StringBuilder(96);
        key.append(pct).append('|').append(Fmt.size(s.usedKb)).append('|').append(Fmt.size(s.totalKb))
           .append('|').append(Fmt.size(s.selfPss)).append('|').append(s.procCount)
           .append('|').append(limit).append('|').append(expanded ? 1 : 0);
        for (int i = 0; i < limit; i++) {
            Proc pr = s.procs.get(i);
            key.append('|').append(shortName(pr)).append('=').append(pr.kb());
        }
        String k = key.toString();
        if (k.equals(lastKey) && curveSize == s.histSize) {
            return;                              // 数据没变化，省掉一次重排+重绘
        }
        lastKey = k;

        usedPct = pct;
        alert = usedPct >= alertPct || s.lowMem;
        pctText = usedPct + "%";
        capText = Fmt.size(s.usedKb) + " / " + Fmt.size(s.totalKb);
        line2 = "本应用 " + Fmt.size(s.selfPss) + " · 进程 " + s.procCount
                + (s.costMs > 0 ? " · " + s.costMs + "ms" : "");

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
        requestLayout();
        invalidate();
    }

    private String shortName(Proc pr) {
        if (pr.self) return "本应用";
        if (pr.leak) {
            String base = pr.label != null && pr.label.length() > 0 ? pr.label : pr.pkg;
            return "↑ " + (base == null ? "" : base);
        }
        if (pr.label != null && pr.label.length() > 0) return pr.label;
        if (!pr.app) return pr.name == null ? "" : pr.name;
        String pkg = pr.pkg == null ? "" : pr.pkg;
        int dot = pkg.lastIndexOf('.');
        return dot > 0 && dot + 1 < pkg.length() ? pkg.substring(dot + 1) : pkg;
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        if (ball) {
            int d = (int) Math.max(52f * density, textSizeSp * density * 4.2f);
            setMeasuredDimension(d, d);
            return;
        }
        float w = pBig.measureText(pctText) + 8f * density + pDim.measureText(capText);
        float w2 = pDim.measureText(line2);
        if (w2 > w) w = w2;
        if (expanded && rows > 0) {
            for (int i = 0; i < rows; i++) {
                float rw = pDim.measureText(names[i] == null ? "" : names[i])
                        + pDim.measureText(vals[i] == null ? "" : vals[i])
                        + 22f * density;
                if (rw > w) w = rw;
            }
        }
        w += pad * 2;
        float minW = 138f * density;
        if (w < minW) w = minW;

        float h = pad + lineH;
        if (showBar) h += 9f * density;
        h += lineH * 0.8f;
        if (expanded && rows > 0) h += 6f * density + rows * rowH;
        if (showCurve) h += 20f * density;
        h += pad * 0.8f;
        setMeasuredDimension((int) Math.ceil(w), (int) Math.ceil(h));
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (ball) {
            drawBall(canvas, w, h);
            return;
        }
        float r = 10f * density;

        pBg.setColor(Theme.alpha(theme.bg, bgAlphaPct));
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, r, r, pBg);

        float x = pad;
        float y = pad + lineH * 0.72f;

        pBig.setColor(Theme.alpha(alert ? theme.alert : theme.text, textAlphaPct));
        float pw = pBig.measureText(pctText);
        canvas.drawText(pctText, x, y, pBig);
        pDim.setColor(Theme.alpha(theme.dim, textAlphaPct));
        canvas.drawText(capText, x + pw + 8f * density, y, pDim);

        float cy = pad + lineH;
        if (showBar) {
            drawBar(canvas, x, cy, w - pad * 2);
            cy += 9f * density;
        }

        canvas.drawText(line2, x, cy + lineH * 0.62f, pDim);
        cy += lineH * 0.8f + 1f * density;

        if (expanded && rows > 0) {
            pTrack.setColor(Theme.alpha(theme.track, textAlphaPct));
            canvas.drawRect(x, cy + 1f * density, w - pad, cy + 1.6f * density, pTrack);
            cy += 6f * density;
            for (int i = 0; i < rows; i++) {
                String name = names[i] == null ? "" : names[i];
                String val = vals[i] == null ? "" : vals[i];
                float vw = pDim.measureText(val);
                float maxName = (w - pad * 2) - vw - 10f * density;
                pText.setColor(Theme.alpha(theme.text, textAlphaPct));
                canvas.drawText(ellipsize(name, maxName, pText), x, cy + rowH * 0.74f, pText);
                pDim.setColor(Theme.alpha(theme.accent, textAlphaPct));
                canvas.drawText(val, w - pad - vw, cy + rowH * 0.74f, pDim);
                cy += rowH;
            }
        }

        if (showCurve && curveSize > 1) {
            pCurve.setColor(Theme.alpha(theme.curve, textAlphaPct));
            drawCurve(canvas, x, cy + 3f * density, w - pad * 2, 15f * density);
        }
    }

    private void drawBall(Canvas canvas, int w, int h) {
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) / 2f - pRing.getStrokeWidth();
        pBg.setColor(Theme.alpha(theme.bg, bgAlphaPct));
        canvas.drawCircle(cx, cy, r, pBg);

        pTrack.setColor(Theme.alpha(theme.track, textAlphaPct));
        canvas.drawCircle(cx, cy, r, pTrack);

        int c = alert ? theme.alert : (usedPct >= alertPct - 12 ? theme.warn : theme.ok);
        pRing.setColor(Theme.alpha(c, textAlphaPct));
        float sweep = 360f * Math.min(100, Math.max(0, usedPct)) / 100f;
        canvas.drawArc(new RectF(cx - r, cy - r, cx + r, cy + r), -90f, sweep, false, pRing);

        pBig.setColor(Theme.alpha(alert ? theme.alert : theme.text, textAlphaPct));
        String txt = usedPct + "%";
        pBig.setTextSize(textSizeSp * density * 1.1f);
        float tw = pBig.measureText(txt);
        canvas.drawText(txt, cx - tw / 2f, cy + pBig.getTextSize() * 0.35f, pBig);
    }

    private void drawBar(Canvas canvas, float x, float y, float w) {
        float hBar = 4.5f * density;
        rect.set(x, y, x + w, y + hBar);
        pTrack.setColor(Theme.alpha(theme.track, textAlphaPct));
        canvas.drawRoundRect(rect, hBar, hBar, pTrack);
        float fill = w * usedPct / 100f;
        if (fill > 0) {
            rect.set(x, y, x + Math.max(hBar, fill), y + hBar);
            int c = alert ? theme.alert : (usedPct >= alertPct - 12 ? theme.warn : theme.ok);
            pBar.setColor(Theme.alpha(c, textAlphaPct));
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
