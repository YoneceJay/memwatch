package com.dsh.memwatch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

/** 悬浮窗前台服务：主悬浮窗 + 固定应用子悬浮窗，共用一个采样引擎。 */
public final class OverlayService extends Service implements Sampler.Listener {

    public static final String ACTION_STOP = "com.dsh.memwatch.action.STOP";
    public static final String ACTION_RELOAD = "com.dsh.memwatch.action.RELOAD";

    private static final int NOTIF_ID = 1001;
    private static final String CHANNEL = "memwatch_overlay";

    private WindowManager wm;
    private OverlayView view;
    private WindowManager.LayoutParams lp;
    private PinnedView pinned;
    private WindowManager.LayoutParams pinnedLp;
    private boolean pinnedAttached;

    private Sampler sampler;
    private final Handler main = new Handler(Looper.getMainLooper());

    private float downRawX, downRawY;
    private int startX, startY;
    private boolean dragging;
    private long downAt;
    private boolean longFired;
    private int slop;
    private boolean attached;
    private boolean wasAlert;
    private long lastAlertAt;
    private boolean targetPinned;

    private final Runnable longPress = new Runnable() {
        @Override public void run() {
            if (dragging) return;
            longFired = true;
            Toast.makeText(OverlayService.this, "已关闭悬浮窗，可从应用内重新开启", Toast.LENGTH_SHORT).show();
            stopSelf();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        slop = (int) (6 * getResources().getDisplayMetrics().density);
        sampler = Sampler.get(this);
        try { ShizukuShell.init(); } catch (Throwable ignore) {}
        Prefs.setOverlayOn(this, true);
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        startForeground(NOTIF_ID, buildNotification());
        if (!canOverlay()) {
            Toast.makeText(this, "没有悬浮窗权限，请在设置里授权", Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }
        addOverlay();
        sampler.addListener(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_RELOAD.equals(intent.getAction())) {
            applyPrefs();
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public void onDestroy() {
        main.removeCallbacks(longPress);
        if (sampler != null) sampler.removeListener(this);
        if (view != null && attached) {
            try { wm.removeView(view); } catch (Throwable ignore) {}
        }
        if (pinned != null && pinnedAttached) {
            try { wm.removeView(pinned); } catch (Throwable ignore) {}
        }
        attached = false;
        pinnedAttached = false;
        view = null;
        pinned = null;
        Prefs.setOverlayOn(this, false);
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        clamp(lp, view);
        clamp(pinnedLp, pinned);
    }

    // ------------------------------------------------------------------ 窗口

    private boolean canOverlay() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
    }

    private int overlayType() {
        return Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private WindowManager.LayoutParams baseParams(int x, int y) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = x;
        p.y = y;
        return p;
    }

    private void addOverlay() {
        view = new OverlayView(this);
        applyStyle(view);
        view.bind(sampler.snapshot(), Prefs.topN(this));

        int px = Prefs.overlayX(this), py = Prefs.overlayY(this);
        if (px == Integer.MIN_VALUE) px = (int) (12 * getResources().getDisplayMetrics().density);
        if (py == Integer.MIN_VALUE) py = (int) (110 * getResources().getDisplayMetrics().density);
        lp = baseParams(px, py);
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                return handleTouch(e, false);
            }
        });
        try {
            wm.addView(view, lp);
            attached = true;
        } catch (Throwable t) {
            Toast.makeText(this, "悬浮窗添加失败：" + t.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }
        main.post(new Runnable() {
            @Override public void run() { clamp(lp, view); }
        });
    }

    private void ensurePinned() {
        String[] pkgs = Prefs.pinned(this);
        boolean want = Prefs.subOverlay(this) && pkgs.length > 0;
        if (want && !pinnedAttached) {
            pinned = new PinnedView(this);
            applyStyle(pinned);
            int px = Prefs.subX(this), py = Prefs.subY(this);
            int screenW = getResources().getDisplayMetrics().widthPixels;
            if (px == Integer.MIN_VALUE) px = screenW - (int) (110 * getResources().getDisplayMetrics().density);
            if (py == Integer.MIN_VALUE) py = (int) (300 * getResources().getDisplayMetrics().density);
            pinnedLp = baseParams(px, py);
            pinned.setOnTouchListener(new View.OnTouchListener() {
                @Override public boolean onTouch(View v, MotionEvent e) {
                    return handleTouch(e, true);
                }
            });
            try {
                wm.addView(pinned, pinnedLp);
                pinnedAttached = true;
                main.post(new Runnable() {
                    @Override public void run() { clamp(pinnedLp, pinned); }
                });
            } catch (Throwable ignore) {
                pinnedAttached = false;
            }
        } else if (!want && pinnedAttached) {
            try { wm.removeView(pinned); } catch (Throwable ignore) {}
            pinnedAttached = false;
            pinned = null;
        }
    }

    private void applyStyle(OverlayView v) {
        Theme t = Theme.get(Prefs.themeId(this));
        v.setStyle(t, Prefs.bgAlphaPct(this), Prefs.textAlphaPct(this), Prefs.textSizeSp(this),
                Prefs.alertPct(this), Prefs.expanded(this), Prefs.showBar(this), Prefs.showCurve(this));
    }

    private void applyStyle(PinnedView v) {
        Theme t = Theme.get(Prefs.themeId(this));
        v.setStyle(t, Prefs.bgAlphaPct(this), Prefs.textAlphaPct(this),
                Math.max(8, Prefs.textSizeSp(this) - 2), Prefs.alertPct(this));
    }

    private void applyPrefs() {
        if (view != null) {
            applyStyle(view);
            view.bind(sampler.snapshot(), Prefs.topN(this));
        }
        if (pinned != null) applyStyle(pinned);
    }

    private boolean handleTouch(MotionEvent e, boolean isPinned) {
        WindowManager.LayoutParams p = isPinned ? pinnedLp : lp;
        View v = isPinned ? pinned : view;
        targetPinned = isPinned;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                startX = p.x;
                startY = p.y;
                dragging = false;
                longFired = false;
                downAt = System.currentTimeMillis();
                if (!isPinned) main.postDelayed(longPress, 700);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getRawX() - downRawX;
                float dy = e.getRawY() - downRawY;
                if (!dragging && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                    dragging = true;
                    main.removeCallbacks(longPress);
                }
                if (dragging) {
                    p.x = startX + (int) dx;
                    p.y = startY + (int) dy;
                    try { wm.updateViewLayout(v, p); } catch (Throwable ignore) {}
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                main.removeCallbacks(longPress);
                if (dragging) {
                    if (isPinned) {
                        clamp(p, v);
                        Prefs.setSubPos(this, p.x, p.y);
                    } else {
                        snapToEdge();
                        Prefs.setOverlayPos(this, lp.x, lp.y);
                    }
                } else if (!longFired && System.currentTimeMillis() - downAt < 400) {
                    if (isPinned) {
                        Intent i = new Intent(this, MainActivity.class);
                        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        try { startActivity(i); } catch (Throwable ignore) {}
                    } else {
                        boolean exp = !Prefs.expanded(this);
                        Prefs.setExpanded(this, exp);
                        applyPrefs();
                    }
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                main.removeCallbacks(longPress);
                return true;
            default:
                return false;
        }
    }

    private void snapToEdge() {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int w = view.getWidth();
        int mid = screenW / 2;
        lp.x = lp.x + w / 2 < mid ? (int) (8 * getResources().getDisplayMetrics().density)
                                  : screenW - w - (int) (8 * getResources().getDisplayMetrics().density);
        clamp(lp, view);
        Prefs.setOverlayPos(this, lp.x, lp.y);
    }

    private void clamp(WindowManager.LayoutParams p, View v) {
        if (p == null || v == null) return;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int w = Math.max(v.getWidth(), 1);
        int h = Math.max(v.getHeight(), 1);
        if (p.x < 0) p.x = 0;
        if (p.y < 0) p.y = 0;
        if (p.x > screenW - w) p.x = Math.max(0, screenW - w);
        if (p.y > screenH - h) p.y = Math.max(0, screenH - h);
        try { wm.updateViewLayout(v, p); } catch (Throwable ignore) {}
    }

    // ------------------------------------------------------------------ 采样回调

    @Override public void onSample(Snap s) {
        if (view != null) view.bind(s, Prefs.topN(this));

        ensurePinned();
        if (pinned != null && pinnedAttached) {
            pinned.bind(s, Prefs.pinned(this));
        }

        boolean a = s.usedPct() >= Prefs.alertPct(this) || s.lowMem;
        long now = System.currentTimeMillis();
        if (a && !wasAlert && Prefs.alertNotify(this) && now - lastAlertAt > 300_000L) {
            lastAlertAt = now;
            notifyAlert(s);
        }
        wasAlert = a;
    }

    private void notifyAlert(Snap s) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            String ch = "memwatch_alert";
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(ch) == null) {
                nm.createNotificationChannel(new NotificationChannel(ch, "内存告警",
                        NotificationManager.IMPORTANCE_DEFAULT));
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, ch) : new Notification.Builder(this);
            Intent open = new Intent(this, MainActivity.class)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            b.setContentTitle("内存占用 " + s.usedPct() + "%")
                    .setContentText("已用 " + Fmt.size(s.usedKb) + " / " + Fmt.size(s.totalKb))
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(this, 2, open, piFlags()));
            nm.notify(2001, b.build());
        } catch (Throwable ignore) {}
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags());

        Intent stop = new Intent(this, OverlayService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, piFlags());

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "悬浮窗", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                ch.setSound(null, null);
                nm.createNotificationChannel(ch);
            }
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle("内存监视器")
                .setContentText("实时悬浮窗运行中")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true)
                .setContentIntent(pi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPi);
        return b.build();
    }

    private static int piFlags() {
        return Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
    }
}
