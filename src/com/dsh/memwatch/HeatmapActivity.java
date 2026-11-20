package com.dsh.memwatch;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** 内存热力方块图页面：面积正比于占用，点方块固定/取消固定。 */
public final class HeatmapActivity extends Activity implements Sampler.Listener {

    private static final int BG = 0xFFF4F6F9;
    private static final int TXT = 0xFF1B2330;
    private static final int DIM = 0xFF6B7785;
    private static final int ACCENT = 0xFF2F6FED;

    private Sampler sampler;
    private TreemapView map;
    private TextView title;
    private int topN = 16;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sampler = Sampler.get(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 10));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        title = Ui.text(this, "内存热力方块图", 17, TXT, true);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button back = Ui.button(this, "返回", Color.WHITE, ACCENT);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        bar.addView(back);
        root.addView(bar);

        TextView tip = Ui.text(this, "面积 = 内存占用 · 点方块可固定到小悬浮窗", 11, DIM, false);
        tip.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        root.addView(tip);

        map = new TreemapView(this);
        map.setTheme(Theme.effective(this), Prefs.textSizeSp(this) + 1);
        map.setOnPick(new TreemapView.OnPick() {
            @Override public void onPick(String pkg, String label, long kb) {
                if (pkg == null || pkg.length() == 0 || pkg.indexOf('.') < 0) {
                    Toast.makeText(HeatmapActivity.this, "系统进程无法固定", Toast.LENGTH_SHORT).show();
                    return;
                }
                Prefs.togglePin(HeatmapActivity.this, pkg);
                boolean on = Prefs.isPinned(HeatmapActivity.this, pkg);
                Toast.makeText(HeatmapActivity.this,
                        (on ? "已固定：" : "已取消：") + label + " · " + Fmt.size(kb), Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(map, mlp);

        setContentView(root);
        map.setData(sampler.snapshot(), topN);
    }

    @Override protected void onResume() {
        super.onResume();
        sampler.addListener(this);
    }

    @Override protected void onPause() {
        sampler.removeListener(this);
        super.onPause();
    }

    @Override public void onSample(Snap s) {
        map.setData(s, topN);
        title.setText("内存热力方块图 · 共 " + s.procs.size() + " 项");
    }
}
