package com.dsh.memwatch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * 固定应用子悬浮窗：只显示被固定的应用，尺寸比主悬浮窗小，可单独拖动。
 */
public final class PinnedView extends View {

    private final Paint pBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private float density = 1f;
    private float pad;
    private float rowH;
    private float headerH;
    private int textSizeSp = 9;

    private final String[] names = new String[8];
    private final String[] vals = new String[8];
    private int rows;
    private Theme theme = Theme.ALL[0];
    private int bgAlpha = 78;
    private int textAlpha = 100;
    private int alertPct = 85;
    private boolean alert;

    public PinnedView(Context c) {
        super(c);
        density = getResources().getDisplayMetrics().density;
        pBg.setStyle(Paint.Style.FILL);
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

    public void setStyle(Theme t, int bgAlphaPct, int textAlphaPct, int sizeSp, int alertPct) {
        theme = t == null ? Theme.ALL[0] : t;
        bgAlpha = bgAlphaPct;
        textAlpha = textAlphaPct;
        textSizeSp = sizeSp;
        this.alertPct = alertPct;
        apply();
        requestLayout();
        invalidate();
    }

    public void bind(Snap s, String[] pinnedPkgs) {
        int n = 0;
        for (int i = 0; i < pinnedPkgs.length && n < names.length; i++) {
            String pkg = pinnedPkgs[i];
            if (pkg == null || pkg.length() == 0) continue;
            long kb = 0;
            boolean found = false;
            for (int j = 0; j < s.procs.size(); j++) {
                Proc p = s.procs.get(j);
                if (pkg.equals(p.pkg)) {
                    kb = p.kb();
                    found = true;
                    break;
                }
            }
            if (!found) kb = 0;
            names[n] = Labels.of(getContext(), pkg);
            vals[n] = kb > 0 ? Fmt.size(kb) : "—";
            n++;
        }
        rows = n;
        alert = s.usedPct() >= alertPct || s.lowMem;
        requestLayout();
        invalidate();
    }

    public int rowCount() {
        return rows;
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        float w = pDim.measureText("固定") + pad * 2;
        for (int i = 0; i < rows; i++) {
            float rw = pDim.measureText(names[i]) + pDim.measureText(vals[i]) + 26f * density + pad * 2;
            if (rw > w) w = rw;
        }
        if (w < 96f * density) w = 96f * density;
        float h = pad + headerH + rows * rowH + pad * 0.6f;
        setMeasuredDimension((int) Math.ceil(w), (int) Math.ceil(h));
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        float r = 8f * density;
        pBg.setColor(Theme.alpha(theme.bg, bgAlpha));
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, r, r, pBg);

        float x = pad;
        float y = pad + headerH * 0.8f;
        pDim.setColor(Theme.alpha(alert ? theme.alert : theme.dim, textAlpha));
        canvas.drawText("固定", x, y, pDim);

        float cy = pad + headerH;
        for (int i = 0; i < rows; i++) {
            pText.setColor(Theme.alpha(theme.text, textAlpha));
            canvas.drawText(names[i], x, cy + rowH * 0.72f, pText);
            float vw = pDim.measureText(vals[i]);
            pDim.setColor(Theme.alpha(theme.accent, textAlpha));
            canvas.drawText(vals[i], w - pad - vw, cy + rowH * 0.72f, pDim);
            cy += rowH;
        }
    }
}
