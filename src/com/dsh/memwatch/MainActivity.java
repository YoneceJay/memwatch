package com.dsh.memwatch;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/** 主界面：总览 + 悬浮窗开关 + 六个常用开关 + 应用排行（长按固定）。 */
public final class MainActivity extends Activity implements Sampler.Listener {

    private static final int BG = 0xFFF4F6F9;
    private static final int CARD = 0xFFFFFFFF;
    private static final int TXT = 0xFF1B2330;
    private static final int DIM = 0xFF6B7785;
    private static final int ACCENT = 0xFF2F6FED;

    private ListView list;
    private Adapter adapter;
    private final Rows rows = new Rows();
    private Sampler sampler;

    private TextView tvPct, tvCap, tvDetail, tvSelfBig, tvSelfDetail, tvStatus, tvHint, tvRankTitle;
    private ProgressBar bar;
    private Button btnMain, btnSub;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sampler = Sampler.get(this);
        try { ShizukuShell.init(); } catch (Throwable ignore) {}
        try { ShizukuShell.setPermissionCallback(new Runnable() {
            @Override public void run() { sampler.kick(); }
        }); } catch (Throwable ignore) {}

        list = new ListView(this);
        list.setBackgroundColor(BG);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.addHeaderView(buildHeader(), null, false);
        list.addFooterView(buildFooter(), null, false);
        adapter = new Adapter();
        list.setAdapter(adapter);
        setContentView(list);

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 11);
        }
    }

    // ------------------------------------------------------------------ 头部

    private View buildHeader() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12), 0);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(Ui.text(this, "内存监视器", 20, TXT, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout rightCol = new LinearLayout(this);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        Button btnSettings = Ui.button(this, "设置", Color.WHITE, ACCENT);
        btnSettings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        rightCol.addView(btnSettings);
        Button btnRefresh = Ui.button(this, "刷新", TXT, 0xFFE6ECF5);
        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                RootReader.invalidate();
                try { ShizukuShell.init(); } catch (Throwable ignore) {}
                sampler.kick();
                Toast.makeText(MainActivity.this, "已重新加载", Toast.LENGTH_SHORT).show();
            }
        });
        rightCol.addView(btnRefresh, Ui.full(4, this));
        titleRow.addView(rightCol);
        root.addView(titleRow);

        // 系统内存卡
        LinearLayout sys = Ui.card(this, CARD, 14);
        sys.addView(Ui.text(this, "系统内存", 13, DIM, false));
        LinearLayout bigRow = new LinearLayout(this);
        bigRow.setGravity(Gravity.BOTTOM);
        tvPct = Ui.text(this, "--%", 34, TXT, true);
        bigRow.addView(tvPct);
        tvCap = Ui.text(this, "  -- / --", 14, DIM, false);
        bigRow.addView(tvCap);
        sys.addView(bigRow);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        sys.addView(bar, Ui.full(8, this));

        tvDetail = Ui.text(this, "", 12, DIM, false);
        tvDetail.setPadding(0, Ui.dp(this, 8), 0, 0);
        sys.addView(tvDetail);
        root.addView(sys);

        // 本应用卡
        LinearLayout self = Ui.card(this, CARD, 14);
        self.addView(Ui.text(this, "本应用占用", 13, DIM, false));
        tvSelfBig = Ui.text(this, "--", 26, TXT, true);
        self.addView(tvSelfBig);
        tvSelfDetail = Ui.text(this, "", 12, DIM, false);
        self.addView(tvSelfDetail);
        tvStatus = Ui.text(this, "", 12, DIM, false);
        tvStatus.setPadding(0, Ui.dp(this, 6), 0, 0);
        self.addView(tvStatus);
        root.addView(self);

        // 两个相互独立的悬浮窗开关
        LinearLayout overlayCard = Ui.card(this, CARD, 14);
        overlayCard.addView(Ui.text(this, "悬浮窗（互相独立）", 13, DIM, false));
        btnMain = Ui.button(this, "主悬浮窗", Color.WHITE, ACCENT);
        btnMain.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!Prefs.overlayOn(MainActivity.this) && !ensureOverlayPermission()) return;
                Prefs.setOverlayOn(MainActivity.this, !Prefs.overlayOn(MainActivity.this));
                ensureService();
            }
        });
        overlayCard.addView(btnMain, Ui.full(8, this));
        btnSub = Ui.button(this, "固定应用小窗", Color.WHITE, 0xFF3D7BD8);
        btnSub.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!Prefs.subOn(MainActivity.this)) {
                    if (Prefs.pinned(MainActivity.this).length == 0) {
                        Toast.makeText(MainActivity.this, "先长按列表里的应用把它固定", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!ensureOverlayPermission()) return;
                }
                Prefs.setSubOn(MainActivity.this, !Prefs.subOn(MainActivity.this));
                ensureService();
            }
        });
        overlayCard.addView(btnSub, Ui.full(6, this));
        overlayCard.addView(Ui.switchRow(this, "迷你圆球模式", Prefs.ball(this), TXT,
                new Ui.OnBool() {
                    @Override public void on(boolean v) {
                        Prefs.setBall(MainActivity.this, v);
                        reloadOverlay();
                    }
                }));
        root.addView(overlayCard);

        // 六个常用开关
        LinearLayout sw = Ui.card(this, CARD, 14);
        sw.addView(Ui.text(this, "显示与采样", 13, DIM, false));
        sw.addView(Ui.switchRow(this, "精确 PSS（dumpsys）", Prefs.precise(this), TXT,
                new Ui.OnBool() {
                    @Override public void on(boolean v) { Prefs.setPrecise(MainActivity.this, v); sampler.kick(); }
                }));
        sw.addView(Ui.switchRow(this, "显示系统进程", Prefs.showSystem(this), TXT,
                new Ui.OnBool() {
                    @Override public void on(boolean v) { Prefs.setShowSystem(MainActivity.this, v); sampler.kick(); }
                }));
        sw.addView(Ui.switchRow(this, "超过阈值发通知", Prefs.alertNotify(this), TXT,
                new Ui.OnBool() {
                    @Override public void on(boolean v) { Prefs.setAlertNotify(MainActivity.this, v); }
                }));
        sw.addView(Ui.switchRow(this, "息屏时停止采样", Prefs.screenOnly(this), TXT,
                new Ui.OnBool() {
                    @Override public void on(boolean v) { Prefs.setScreenOnly(MainActivity.this, v); }
                }));
        sw.addView(Ui.switchRow(this, "显示占用进度条", Prefs.showBar(this), TXT,
                new Ui.OnBool() {
                    @Override public void on(boolean v) { Prefs.setShowBar(MainActivity.this, v); reloadOverlay(); }
                }));
        sw.addView(Ui.switchRow(this, "显示占用曲线", Prefs.showCurve(this), TXT,
                new Ui.OnBool() {
                    @Override public void on(boolean v) { Prefs.setShowCurve(MainActivity.this, v); reloadOverlay(); }
                }));
        root.addView(sw);

        // 数据来源
        LinearLayout hint = Ui.card(this, CARD, 14);
        hint.addView(Ui.text(this, "数据来源", 13, DIM, false));
        tvHint = Ui.text(this, "", 12, DIM, false);
        tvHint.setPadding(0, Ui.dp(this, 4), 0, 0);
        hint.addView(tvHint);
        root.addView(hint);

        tvRankTitle = Ui.text(this, "应用内存排行", 15, TXT, true);
        LinearLayout rankRow = new LinearLayout(this);
        rankRow.setPadding(Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 6));
        rankRow.addView(tvRankTitle);
        root.addView(rankRow);

        return root;
    }

    private View buildFooter() {
        TextView t = Ui.text(this, "提示：长按任意一行可固定到小子悬浮窗。Android 8 起普通应用无法读取其它进程内存，"
                + "需要 ROOT 或 Shizuku；两者都没有时仍可精确显示系统总览与自身占用。", 11, DIM, false);
        t.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 24));
        return t;
    }

    /** 返回 true 表示已有悬浮窗权限 */
    private boolean ensureOverlayPermission() {
        if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)) return true;
        Toast.makeText(this, "请先允许「显示在其他应用上层」", Toast.LENGTH_LONG).show();
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开授权页", Toast.LENGTH_LONG).show();
        }
        return false;
    }

    private void ensureService() {
        boolean any = Prefs.overlayOn(this) || Prefs.subOn(this);
        Intent i = new Intent(this, OverlayService.class);
        if (any) startService(i.setAction(OverlayService.ACTION_RELOAD));
        else stopService(i);
        updateButtons();
    }

    private void updateButtons() {
        if (btnMain == null) return;
        boolean m = Prefs.overlayOn(this);
        boolean s = Prefs.subOn(this);
        btnMain.setText(m ? "主悬浮窗：开" : "主悬浮窗：关");
        btnMain.setBackgroundColor(0);
        btnSub.setText(s ? "固定应用小窗：开" : "固定应用小窗：关");
        btnSub.setBackgroundColor(0);
        Ui.paintButton(btnMain, m ? 0xFF2F6FED : 0xFF9AA6B5);
        Ui.paintButton(btnSub, s ? 0xFF3D7BD8 : 0xFF9AA6B5);
    }

    private void reloadOverlay() {
        if (!Prefs.overlayOn(this) && !Prefs.subOn(this)) return;
        startService(new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_RELOAD));
    }

    // ------------------------------------------------------------------ 生命周期

    @Override protected void onResume() {
        super.onResume();
        sampler.addListener(this);
        updateButtons();
        onSample(sampler.snapshot());
    }

    @Override protected void onPause() {
        sampler.removeListener(this);
        super.onPause();
    }

    @Override public void onSample(Snap s) {
        int pct = s.usedPct();
        tvPct.setText(pct + "%");
        tvPct.setTextColor(pct >= Prefs.alertPct(this) || s.lowMem ? 0xFFE04141 : TXT);
        bar.setProgress(pct);
        tvCap.setText("  " + Fmt.size(s.usedKb) + " / " + Fmt.size(s.totalKb));
        tvDetail.setText("已用 " + Fmt.size(s.usedKb)
                + " · 可用 " + Fmt.size(s.availKb)
                + " · 低内存阈值 " + Fmt.size(s.thresholdKb)
                + (s.lowMem ? " · 系统已进入低内存" : ""));

        tvSelfBig.setText(Fmt.size(s.selfPss));
        tvSelfDetail.setText("Java " + Fmt.size(s.selfJava)
                + " · Native " + Fmt.size(s.selfNative)
                + " · Code " + Fmt.size(s.selfCode)
                + (s.selfSwap > 0 ? " · Swap " + Fmt.size(s.selfSwap) : ""));

        StringBuilder sb = new StringBuilder(64);
        sb.append("采样 ").append(s.costMs).append("ms · 间隔 ").append(s.intervalMs).append("ms")
          .append(" · 进程 ").append(s.procCount);
        if (s.precise) sb.append(" · PSS");
        tvStatus.setText(sb.toString());

        tvHint.setText(s.root
                ? (s.shellKind == 2 ? "Shizuku" : "ROOT") + " 通道 · " + s.backend
                  + " · 共 " + s.procCount + " 个进程" + (s.precise ? " · PSS 精确数据" : "")
                : "未获得 ROOT / Shizuku，只能显示系统总览与自身。"
                  + "装上 Shizuku 并授权即可读取全部应用（无需 ROOT）。"
                  + (s.shellError.length() > 0 ? "\n通道诊断：" + s.shellError : ""));

        rows.fill(s, Prefs.pinned(this));
        adapter.notifyDataSetChanged();
        tvRankTitle.setText(s.root
                ? "应用内存排行（" + rows.size + "）" + (rows.leakCount > 0 ? " · 疑似增长 " + rows.leakCount : "")
                : "应用列表不可用（需要 ROOT / Shizuku）");
    }

    // ------------------------------------------------------------------ 列表

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return rows.size; }
        @Override public Object getItem(int i) { return rows.kb[i]; }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(final int pos, View convert, ViewGroup parent) {
            LinearLayout row;
            if (convert == null) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Ui.dp(MainActivity.this, 14), Ui.dp(MainActivity.this, 9),
                        Ui.dp(MainActivity.this, 14), Ui.dp(MainActivity.this, 9));
                row.setBackgroundColor(CARD);

                LinearLayout col = new LinearLayout(MainActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                TextView name = Ui.text(MainActivity.this, "", 13, TXT, false);
                TextView sub = Ui.text(MainActivity.this, "", 11, DIM, false);
                col.addView(name);
                col.addView(sub);
                row.addView(col, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                TextView val = Ui.text(MainActivity.this, "", 13, TXT, true);
                row.addView(val);
                row.setTag(new Object[]{name, sub, val});
            } else {
                row = (LinearLayout) convert;
            }
            Object[] h = (Object[]) row.getTag();
            TextView name = (TextView) h[0];
            TextView sub = (TextView) h[1];
            TextView val = (TextView) h[2];
            name.setText(rows.name[pos]);
            String s = rows.sub[pos];
            sub.setVisibility(s == null || s.length() == 0 ? View.GONE : View.VISIBLE);
            sub.setText(s);
            val.setText(Fmt.size(rows.kb[pos]));
            val.setTextColor(rows.pinned[pos] ? 0xFFE08A16 : (rows.accent[pos] ? ACCENT : TXT));

            final String pkg = rows.pkg[pos];
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    if (pkg == null || pkg.length() == 0) return false;
                    final boolean pinnedNow = Prefs.isPinned(MainActivity.this, pkg);
                    new android.app.AlertDialog.Builder(MainActivity.this)
                            .setTitle(Labels.of(MainActivity.this, pkg))
                            .setMessage(pinnedNow ? "已固定到子悬浮窗" : "固定后会显示在右上角的小悬浮窗里")
                            .setPositiveButton(pinnedNow ? "取消固定" : "固定", new android.content.DialogInterface.OnClickListener() {
                                @Override public void onClick(android.content.DialogInterface d, int w) {
                                    Prefs.togglePin(MainActivity.this, pkg);
                                    reloadOverlay();
                                    sampler.kick();
                                }
                            })
                            .setNegativeButton("关闭", null)
                            .show();
                    return true;
                }
            });
            return row;
        }
    }

    /** 每轮把快照拷成数组，避免 Adapter 持有可能被下一轮复用的 Proc 对象 */
    private static final class Rows {
        String[] name = new String[64];
        String[] sub = new String[64];
        String[] pkg = new String[64];
        long[] kb = new long[64];
        boolean[] accent = new boolean[64];
        boolean[] pinned = new boolean[64];
        int size;
        int leakCount;

        void fill(Snap s, String[] pins) {
            int n = s.procs.size();
            ensure(n);
            size = n;
            leakCount = 0;
            for (int i = 0; i < n; i++) {
                Proc p = s.procs.get(i);
                pkg[i] = p.pkg;
                if (p.app) {
                    String label = p.label != null && p.label.length() > 0 ? p.label : p.pkg;
                    name[i] = p.self ? "内存监视器（本应用）" : (p.leak ? "↑ " + label : label);
                    StringBuilder sb = new StringBuilder(48);
                    sb.append(p.pkg);
                    if (p.procCount > 1) sb.append(" · ").append(p.procCount).append(" 个进程");
                    sub[i] = sb.toString();
                    accent[i] = true;
                    pinned[i] = isPinned(p.pkg, pins);
                    if (p.leak) leakCount++;
                } else {
                    name[i] = p.name;
                    sub[i] = "系统进程";
                    accent[i] = false;
                    pinned[i] = false;
                }
                kb[i] = p.kb();
            }
        }

        private static boolean isPinned(String pkg, String[] pins) {
            if (pkg == null || pins == null) return false;
            for (String p : pins) if (pkg.equals(p)) return true;
            return false;
        }

        private void ensure(int n) {
            if (name.length >= n) return;
            int cap = Math.max(n, name.length * 2);
            name = new String[cap];
            sub = new String[cap];
            pkg = new String[cap];
            kb = new long[cap];
            accent = new boolean[cap];
            pinned = new boolean[cap];
        }
    }
}
