package com.dsh.memwatch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/**
 * 固定应用子悬浮窗：0=圆球，1=详细列表。过渡动画同样只作用于 Canvas。
 */
public final class PinnedView extends View {

    public static final int MODE_BALL = 0;
    public static final int MODE_DETAIL = 1;
    private static final long ANIM_MS = 150L;

    private final Paint pBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private float density = 1f;
    private float pad;
    private float rowH;
    private float headerH;
    private int textSizeSp = 9;

    private final String[] names = new String[8];
    private final String[] vals = new String[8];
    private int rows;
    private int mode = MODE_DETAIL;
    private Theme theme = Theme.ALL[4];
    private int bgAlpha = 78;
    private int textAlpha = 100;
    private int alertPct = 85;
    private boolean alert;

    private boolean animating;
    private long animStart;
    private float animT = 1f;

    public PinnedView(Context c) {
        super(c);
        density = getResources().getDisplayMetrics().density;
        pBg.setStyle(Paint.Style.FILL);
        pRing.setStyle(Paint.Style.STROKE);
        pRing.setStrokeWidth(2.6f * density);
        apply();
    }

    private void apply() {
        float px = textSizeSp * density;
        pText.setTextSize(px);
        pDim.setTextSize(px * 0.92f);
        pad = 7f * density;
        rowH = px * 1.5f;
        headerH = px * 1.35f;
    }

    public void setMode(int m) {
        if (m != MODE_BALL) m = MODE_DETAIL;
        if (mode == m) return;
        mode = m;
        requestLayout();
        playTransition();
    }

    public int mode() {
        return mode;
    }

    public void setStyle(Theme t, int bgAlphaPct, int textAlphaPct, int sizeSp, int alertPct) {
        theme = t == null ? Theme.ALL[4] : t;
        bgAlpha = bgAlphaPct;
        textAlpha = textAlphaPct;
        textSizeSp = sizeSp;
        this.alertPct = alertPct;
        apply();
        requestLayout();
        invalidate();
    }

    public void playTransition() {
        animating = true;
        animStart = SystemClock.uptimeMillis();
        postInvalidateOnAnimation();
    }

    public void bind(Snap s, String[] pinnedPkgs) {
        int n = 0;
        for (int i = 0; i < pinnedPkgs.length && n < names.length; i++) {
            String pkg = pinnedPkgs[i];
            if (pkg == null || pkg.length() == 0) continue;
            long kb = 0;
            for (int j = 0; j < s.procs.size(); j++) {
                Proc p = s.procs.get(j);
                if (pkg.equals(p.pkg)) {
                    kb = p.kb();
                    break;
                }
            }
            names[n] = Labels.of(getContext(), pkg);
            vals[n] = kb > 0 ? Fmt.size(kb) : "未运行";
            n++;
        }
        boolean changed = rows != n;
        rows = n;
        alert = s.usedPct() >= alertPct || s.lowMem;
        if (changed) requestLayout();
        invalidate();
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        if (mode == MODE_BALL) {
            int d = (int) Math.max(46f * density, textSizeSp * density * 4.2f);
            setMeasuredDimension(d, d);
            return;
        }
        float valueW = pDim.measureText("000.0M");
        if (rows == 0) valueW = pDim.measureText("未运行");
        float nameW = pDim.measureText("固定");
        for (int i = 0; i < rows; i++) {
            float nw = pDim.measureText(names[i]);
            if (nw > nameW) nameW = nw;
        }
        if (nameW > 120f * density) nameW = 120f * density;   // 超长名字交给 ellipsize
        float w = pad * 2 + nameW + 10f * density + valueW;
        float minW = 112f * density;
        if (w < minW) w = minW;
        float h = pad + headerH + rows * rowH + pad * 0.7f;
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
                animT = 0.45f + 0.55f * e;
                postInvalidateOnAnimation();
            }
        }
        int w = getWidth(), h = getHeight();
        float scale = 0.90f + 0.10f * animT;
        int save = canvas.save();
        canvas.scale(scale, scale, w / 2f, h / 2f);
        int ta = Math.max(0, (int) (textAlpha * animT));
        int ba = Math.max(0, (int) (bgAlpha * animT));

        if (mode == MODE_BALL) {
            drawBall(canvas, w, h, ta, ba);
            canvas.restoreToCount(save);
            return;
        }

        float r = 8f * density;
        pBg.setColor(Theme.alpha(theme.bg, ba));
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, r, r, pBg);

        float x = pad;
        float valueW = pDim.measureText("000.0M");
        float nameMax = w - pad * 2 - valueW - 10f * density;

        // 表头
        pDim.setColor(Theme.alpha(alert ? theme.alert : theme.dim, ta));
        canvas.drawText("固定", x, pad + headerH * 0.68f, pDim);
        pDim.setColor(Theme.alpha(theme.dim, Math.min(ta, 150)));
        String count = String.valueOf(rows);
        canvas.drawText(count, w - pad - pDim.measureText(count), pad + headerH * 0.68f, pDim);

        // 行
        float top = pad + headerH;
        for (int i = 0; i < rows; i++) {
            float baseline = top + i * rowH + rowH * 0.5f + pText.getTextSize() * 0.35f;
            pText.setColor(Theme.alpha(theme.text, ta));
            pText.setTextSize(textSizeSp * density);
            canvas.drawText(ellipsize(names[i], nameMax, pText), x, baseline, pText);
            float vw = pDim.measureText(vals[i]);
            pDim.setColor(Theme.alpha(theme.accent, ta));
            canvas.drawText(vals[i], w - pad - vw, baseline, pDim);
        }
        canvas.restoreToCount(save);
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

    private void drawBall(Canvas canvas, int w, int h, int ta, int ba) {
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) / 2f - pRing.getStrokeWidth();
        pBg.setColor(Theme.alpha(theme.bg, ba));
        canvas.drawCircle(cx, cy, r, pBg);
        pRing.setColor(Theme.alpha(alert ? theme.alert : theme.accent, ta));
        canvas.drawCircle(cx, cy, r, pRing);
        pDim.setColor(Theme.alpha(theme.text, ta));
        String txt = String.valueOf(rows);
        float tw = pDim.measureText(txt);
        canvas.drawText(txt, cx - tw / 2f, cy + pDim.getTextSize() * 0.35f, pDim);
    }
}
