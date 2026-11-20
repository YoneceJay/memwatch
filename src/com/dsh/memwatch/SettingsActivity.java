package com.dsh.memwatch;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** 设置页：主题、透明度、悬浮窗参数、数据源、固定应用、Shizuku。 */
public final class SettingsActivity extends Activity {

    private static final int BG = 0xFFF4F6F9;
    private static final int CARD = 0xFFFFFFFF;
    private static final int TXT = 0xFF1B2330;
    private static final int DIM = 0xFF6B7785;
    private static final int ACCENT = 0xFF2F6FED;

    private LinearLayout root;
    private Sampler sampler;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sampler = Sampler.get(this);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 24));
        sv.addView(root);
        setContentView(sv);
        build();
    }

    private void build() {
        root.removeAllViews();

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.bottomMargin = Ui.dp(this, 12);
        titleRow.setLayoutParams(tlp);
        TextView t = Ui.text(this, "设置", 20, TXT, true);
        titleRow.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button back = Ui.button(this, "返回", Color.WHITE, ACCENT);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        titleRow.addView(back);
        root.addView(titleRow);

        // ---------- 外观
        LinearLayout look = Ui.card(this, CARD, 14);
        look.addView(Ui.text(this, "外观", 13, DIM, false));
        Button theme = rowButton(look, "主题：" + Theme.get(Prefs.themeId(this)).name);
        theme.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.choose(SettingsActivity.this, "主题", Theme.names(), ids(Theme.ALL.length),
                        Prefs.themeId(SettingsActivity.this), false, new Ui.OnInt() {
                            @Override public void on(int val) {
                                Prefs.setThemeId(SettingsActivity.this, val);
                                reload();
                                build();
                            }
                        });
            }
        });

        Button textAlpha = rowButton(look, "文字透明度：" + Prefs.textAlphaPct(this) + "%");
        textAlpha.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.choose(SettingsActivity.this, "文字透明度", pctLabels(), pcts(),
                        Prefs.textAlphaPct(SettingsActivity.this), true, new Ui.OnInt() {
                            @Override public void on(int val) {
                                Prefs.setTextAlphaPct(SettingsActivity.this, val);
                                reload();
                                build();
                            }
                        });
            }
        });

        Button bgAlpha = rowButton(look, "背景透明度：" + Prefs.bgAlphaPct(this) + "%");
        bgAlpha.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.choose(SettingsActivity.this, "背景透明度", pctLabels(), pcts(),
                        Prefs.bgAlphaPct(SettingsActivity.this), true, new Ui.OnInt() {
                            @Override public void on(int val) {
                                Prefs.setBgAlphaPct(SettingsActivity.this, val);
                                reload();
                                build();
                            }
                        });
            }
        });

        Button size = rowButton(look, "字号：" + Prefs.textSizeSp(this) + "sp");
        size.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.choose(SettingsActivity.this, "字号", new String[]{"9sp", "10sp", "11sp", "12sp", "14sp"},
                        new int[]{9, 10, 11, 12, 14}, Prefs.textSizeSp(SettingsActivity.this), true, new Ui.OnInt() {
                            @Override public void on(int val) {
                                Prefs.setTextSizeSp(SettingsActivity.this, Math.max(6, val));
                                reload();
                                build();
                            }
                        });
            }
        });
        root.addView(look);

        // ---------- 悬浮窗
        LinearLayout win = Ui.card(this, CARD, 14);
        win.addView(Ui.text(this, "悬浮窗", 13, DIM, false));

        Button interval = rowButton(win, "刷新间隔：" + Prefs.INTERVAL_NAMES[idx(Prefs.INTERVALS, Prefs.interval(this))]);
        interval.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.choose(SettingsActivity.this, "刷新间隔", Prefs.INTERVAL_NAMES, Prefs.INTERVALS,
                        Prefs.interval(SettingsActivity.this), false, new Ui.OnInt() {
                            @Override public void on(int val) {
                                Prefs.setInterval(SettingsActivity.this, val);
                                sampler.kick();
                                reload();
                                build();
                            }
                        });
            }
        });

        Button topN = rowButton(win, "显示行数：" + Prefs.TOPN_NAMES[idx(Prefs.TOPNS, Prefs.topN(this))]);
        topN.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.choose(SettingsActivity.this, "悬浮窗显示行数", Prefs.TOPN_NAMES, Prefs.TOPNS,
                        Prefs.topN(SettingsActivity.this), false, new Ui.OnInt() {
                            @Override public void on(int val) {
                                Prefs.setTopN(SettingsActivity.this, val);
                                reload();
                                build();
                            }
                        });
            }
        });

        Button alert = rowButton(win, "告警阈值：" + Prefs.alertPct(this) + "%");
        alert.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.choose(SettingsActivity.this, "告警阈值", Prefs.ALERT_NAMES, Prefs.ALERTS,
                        Prefs.alertPct(SettingsActivity.this), true, new Ui.OnInt() {
                            @Override public void on(int val) {
                                Prefs.setAlertPct(SettingsActivity.this, Math.max(30, Math.min(100, val)));
                                reload();
                                build();
                            }
                        });
            }
        });

        win.addView(Ui.switchRow(this, "固定应用子悬浮窗（独立开关）", Prefs.subOn(this), TXT,
                new Ui.OnBool() {
                    @Override public void on(boolean v) {
                        Prefs.setSubOn(SettingsActivity.this, v);
                        applyWindows();
                    }
                }));
        Button mainMode = rowButton(win, "主悬浮窗形态：" + modeName(Prefs.mainMode(this)));
        mainMode.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.choose(SettingsActivity.this, "主悬浮窗形态",
                        new String[]{"圆球", "简略", "详细"}, new int[]{0, 1, 2},
                        Prefs.mainMode(SettingsActivity.this), false, new Ui.OnInt() {
                            @Override public void on(int val) {
                                Prefs.setMainMode(SettingsActivity.this, val);
                                reload();
                                build();
                            }
                        });
            }
        });
        Button subMode = rowButton(win, "固定小窗形态：" + (Prefs.subMode(this) == 0 ? "圆球" : "详细"));
        subMode.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.choose(SettingsActivity.this, "固定小窗形态", new String[]{"圆球", "详细"},
                        new int[]{0, 1}, Prefs.subMode(SettingsActivity.this), false, new Ui.OnInt() {
                            @Override public void on(int val) {
                                Prefs.setSubMode(SettingsActivity.this, val);
                                reload();
                                build();
                            }
                        });
            }
        });
        Button battery = rowButton(win, "电池优化白名单（防被系统杀后台）");
        battery.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Throwable t) {
                    Toast.makeText(SettingsActivity.this, "请在系统设置里手动允许后台运行", Toast.LENGTH_LONG).show();
                }
            }
        });
        win.addView(Ui.switchRow(this, "主题跟随系统深色模式", Prefs.followSystem(this), TXT,
                new Ui.OnBool() {
                    @Override public void on(boolean v) {
                        Prefs.setFollowSystem(SettingsActivity.this, v);
                        reload();
                    }
                }));
        root.addView(win);

        // ---------- 数据源
        LinearLayout data = Ui.card(this, CARD, 14);
        data.addView(Ui.text(this, "数据源", 13, DIM, false));
        data.addView(Ui.switchRow(this, "精确 PSS（dumpsys，较慢）", Prefs.precise(this), TXT,
                new Ui.OnBool() {
                    @Override public void on(boolean v) { Prefs.setPrecise(SettingsActivity.this, v); sampler.kick(); }
                }));
        data.addView(Ui.switchRow(this, "显示系统进程", Prefs.showSystem(this), TXT,
                new Ui.OnBool() {
                    @Override public void on(boolean v) { Prefs.setShowSystem(SettingsActivity.this, v); sampler.kick(); }
                }));
        data.addView(Ui.switchRow(this, "超过阈值发通知", Prefs.alertNotify(this), TXT,
                new Ui.OnBool() {
                    @Override public void on(boolean v) { Prefs.setAlertNotify(SettingsActivity.this, v); }
                }));
        data.addView(Ui.switchRow(this, "息屏时停止采样", Prefs.screenOnly(this), TXT,
                new Ui.OnBool() {
                    @Override public void on(boolean v) { Prefs.setScreenOnly(SettingsActivity.this, v); }
                }));
        data.addView(Ui.switchRow(this, "显示占用进度条", Prefs.showBar(this), TXT,
                new Ui.OnBool() {
                    @Override public void on(boolean v) { Prefs.setShowBar(SettingsActivity.this, v); reload(); }
                }));
        data.addView(Ui.switchRow(this, "显示占用曲线", Prefs.showCurve(this), TXT,
                new Ui.OnBool() {
                    @Override public void on(boolean v) { Prefs.setShowCurve(SettingsActivity.this, v); reload(); }
                }));
        root.addView(data);

        // ---------- Shizuku
        LinearLayout shz = Ui.card(this, CARD, 14);
        shz.addView(Ui.text(this, "Shizuku（免 ROOT 读全部应用）", 13, DIM, false));
        TextView state = Ui.text(this, shizukuState(), 12, DIM, false);
        state.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 8));
        shz.addView(state);
        Button grant = Ui.button(this, "授予 / 检查 Shizuku 权限", Color.WHITE, ACCENT);
        grant.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { ShizukuShell.init(); } catch (Throwable ignore) {}
                if (!ShizukuShell.isInstalled(SettingsActivity.this)) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")));
                    } catch (Throwable ignore) {}
                    return;
                }
                if (ShizukuShell.hasPermission()) {
                    Toast.makeText(SettingsActivity.this, "已授权", Toast.LENGTH_SHORT).show();
                } else if (!ShizukuShell.isBinderReady()) {
                    Toast.makeText(SettingsActivity.this, "Shizuku 服务未运行，请先打开 Shizuku", Toast.LENGTH_LONG).show();
                } else {
                    ShizukuShell.requestPermission();
                }
                state.setText(shizukuState());
            }
        });
        shz.addView(grant);
        root.addView(shz);

        // ---------- 固定应用
        LinearLayout pinCard = Ui.card(this, CARD, 14);
        pinCard.addView(Ui.text(this, "固定应用（子悬浮窗）", 13, DIM, false));
        String[] pins = Prefs.pinned(this);
        if (pins.length == 0) {
            TextView hint = Ui.text(this, "还没有固定。回到主界面长按任意应用即可固定/取消。", 12, DIM, false);
            hint.setPadding(0, Ui.dp(this, 6), 0, 0);
            pinCard.addView(hint);
        } else {
            for (final String pkg : pins) {
                LinearLayout line = new LinearLayout(this);
                line.setGravity(Gravity.CENTER_VERTICAL);
                line.setPadding(0, Ui.dp(this, 6), 0, 0);
                TextView name = Ui.text(this, Labels.of(this, pkg), 13, TXT, false);
                TextView sub = Ui.text(this, "  " + pkg, 11, DIM, false);
                line.addView(name);
                line.addView(sub, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                Button rm = Ui.button(this, "移除", Color.WHITE, 0xFFB03A3A);
                rm.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Prefs.removePin(SettingsActivity.this, pkg);
                        reload();
                        build();
                    }
                });
                line.addView(rm);
                pinCard.addView(line);
            }
        }
        root.addView(pinCard);
    }

    private Button rowButton(LinearLayout parent, String label) {
        Button b = Ui.button(this, label, TXT, 0xFFEDF1F7);
        parent.addView(b, Ui.full(8, this));
        return b;
    }

    private void reload() {
        if (!Prefs.overlayOn(this) && !Prefs.subOn(this)) return;
        startService(new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_RELOAD));
    }

    /** 子窗开关变化：服务没在跑时按需拉起，两个都关时停掉 */
    private void applyWindows() {
        boolean any = Prefs.overlayOn(this) || Prefs.subOn(this);
        if (any) startService(new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_RELOAD));
        else stopService(new Intent(this, OverlayService.class));
    }

    private String shizukuState() {
        boolean installed = ShizukuShell.isInstalled(this);
        boolean ready = ShizukuShell.isBinderReady();
        boolean perm = ShizukuShell.hasPermission();
        if (perm) return "已授权 ✓ 可读取全部应用";
        if (!installed) return "未安装 Shizuku";
        if (!ready) return "已安装，但服务未运行";
        return "已运行，等待授权";
    }

    private static int[] ids(int n) {
        int[] v = new int[n];
        for (int i = 0; i < n; i++) v[i] = i;
        return v;
    }

    private static int[] pcts() {
        return new int[]{20, 40, 60, 80, 100};
    }

    private static String[] pctLabels() {
        return new String[]{"20%", "40%", "60%", "80%", "100%"};
    }

    private static String modeName(int m) {
        return m == 0 ? "圆球" : (m == 1 ? "简略" : "详细");
    }

    private static int idx(int[] arr, int v) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return 0;
    }
}
