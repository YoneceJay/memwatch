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
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Toast;

/**
 * 悬浮窗前台服务。
 *
 * 主悬浮窗与固定应用子悬浮窗是**两个独立窗口、两个独立开关**，
 * 谁开着就只创建谁；两个都关时服务自己停掉。
 * 拖动时窗口尺寸固定（首帧后写死 width/height），避免每次 MOVE 触发重新测量，
 * 手感才跟得上手指；边界收敛放在抬手时做。
 */
public final class OverlayService extends Service implements Sampler.Listener {

    public static final String ACTION_STOP = "com.dsh.memwatch.action.STOP";
    public static final String ACTION_RELOAD = "com.dsh.memwatch.action.RELOAD";

    private static final int NOTIF_ID = 1001;
    private static final String CHANNEL = "memwatch_overlay";

    private WindowManager wm;
    private Sampler sampler;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int slop;

    private OverlayView view;
    private WindowManager.LayoutParams lp;
    private boolean mainAttached;

    private PinnedView pinned;
    private WindowManager.LayoutParams pinnedLp;
    private boolean pinnedAttached;

    // 拖动状态
    private float downRawX, downRawY;
    private int startX, startY;
    private boolean dragging;
    private long downAt;
    private boolean longFired;

    private boolean wasAlert;
    private long lastAlertAt;

    private final Runnable longPress = new Runnable() {
        @Override public void run() {
            if (dragging) return;
            longFired = true;
            Prefs.setOverlayOn(OverlayService.this, false);
            syncWindows();
            Toast.makeText(OverlayService.this, "已关闭主悬浮窗", Toast.LENGTH_SHORT).show();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        slop = ViewConfiguration.get(this).getScaledTouchSlop();
        sampler = Sampler.get(this);
        try { ShizukuShell.init(); } catch (Throwable ignore) {}
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        startForeground(NOTIF_ID, buildNotification());
        if (!canOverlay()) {
            Toast.makeText(this, "没有悬浮窗权限，请在设置里授权", Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }
        syncWindows();
        sampler.addListener(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Prefs.setOverlayOn(this, false);
            Prefs.setSubOn(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }
        syncWindows();
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public void onDestroy() {
        main.removeCallbacks(longPress);
        if (sampler != null) sampler.removeListener(this);
        removeMain();
        removePinned();
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 按需求不做任何位置修正：用户放到哪就留在哪
        applyStyles();
    }

    // ------------------------------------------------------------------ 窗口管理

    private boolean canOverlay() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
    }

    private int overlayType() {
        return Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private WindowManager.LayoutParams params(int x, int y) {
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

    /** 按两个开关同步窗口；都关就停服务 */
    private void syncWindows() {
        boolean wantMain = Prefs.overlayOn(this);
        boolean wantSub = Prefs.subOn(this) && Prefs.pinned(this).length > 0;
        if (!wantMain && !wantSub) {
            stopSelf();
            return;
        }
        if (wantMain) {
            if (!mainAttached) addMain();
            else { applyStyle(view); }
        } else {
            removeMain();
        }
        if (wantSub) {
            if (!pinnedAttached) addPinned();
            else { applyStyle(pinned); }
        } else {
            removePinned();
        }
    }

    private void addMain() {
        view = new OverlayView(this);
        applyStyle(view);
        view.bind(sampler.snapshot(), Prefs.topN(this));
        int px = Prefs.overlayX(this), py = Prefs.overlayY(this);
        if (px == Integer.MIN_VALUE) px = (int) (12 * getResources().getDisplayMetrics().density);
        if (py == Integer.MIN_VALUE) py = (int) (110 * getResources().getDisplayMetrics().density);
        lp = params(px, py);
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                return handleTouch(e, false);
            }
        });
        try {
            wm.addView(view, lp);
            mainAttached = true;
            centerOnEnable(lp, view);
            animateIn(view);
        } catch (Throwable t) {
            mainAttached = false;
            Toast.makeText(this, "悬浮窗添加失败：" + t.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
    }

    private void removeMain() {
        if (view != null && mainAttached) {
            try { wm.removeView(view); } catch (Throwable ignore) {}
        }
        mainAttached = false;
        view = null;
        lp = null;
    }

    private void addPinned() {
        pinned = new PinnedView(this);
        applyStyle(pinned);
        pinned.bind(sampler.snapshot(), Prefs.pinned(this));
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int px = Prefs.subX(this), py = Prefs.subY(this);
        if (px == Integer.MIN_VALUE) px = screenW - (int) (116 * getResources().getDisplayMetrics().density);
        if (py == Integer.MIN_VALUE) py = (int) (320 * getResources().getDisplayMetrics().density);
        pinnedLp = params(px, py);
        pinned.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                return handleTouch(e, true);
            }
        });
        try {
            wm.addView(pinned, pinnedLp);
            pinnedAttached = true;
            centerOnEnable(pinnedLp, pinned);
            animateIn(pinned);
        } catch (Throwable ignore) {
            pinnedAttached = false;
            pinned = null;
        }
    }

    private void removePinned() {
        if (pinned != null && pinnedAttached) {
            try { wm.removeView(pinned); } catch (Throwable ignore) {}
        }
        pinnedAttached = false;
        pinned = null;
        pinnedLp = null;
    }

    /** 首帧后把 wrap_content 写死成具体尺寸：拖动时不再重新测量，跟手 */
    private void freezeSize(WindowManager.LayoutParams p, View v) {
        if (p == null || v == null) return;
        int w = v.getWidth(), h = v.getHeight();
        if (w <= 0 || h <= 0) return;
        WindowManager.LayoutParams copy = p;
        copy.width = w;
        copy.height = h;
        try { wm.updateViewLayout(v, copy); } catch (Throwable ignore) {}
    }

    private void applyStyles() {
        applyStyle(view);
        applyStyle(pinned);
    }

    private void applyStyle(OverlayView v) {
        if (v == null) return;
        v.setBall(Prefs.ball(this));
        v.setStyle(Theme.effective(this), Prefs.bgAlphaPct(this), Prefs.textAlphaPct(this),
                Prefs.textSizeSp(this), Prefs.alertPct(this), Prefs.expanded(this),
                Prefs.showBar(this), Prefs.showCurve(this));
        refreeze(v == view ? lp : null, v);
    }

    private void applyStyle(PinnedView v) {
        if (v == null) return;
        v.setStyle(Theme.effective(this), Prefs.bgAlphaPct(this), Prefs.textAlphaPct(this),
                Math.max(8, Prefs.textSizeSp(this) - 2), Prefs.alertPct(this));
        refreeze(v == pinned ? pinnedLp : null, v);
    }

    /** 每次开启时把窗口摆到屏幕正中（不做任何自动修正，之后随用户拖动） */
    private void centerOnEnable(final WindowManager.LayoutParams p, final View v) {
        v.post(new Runnable() {
            @Override public void run() {
                int sw = getResources().getDisplayMetrics().widthPixels;
                int sh = getResources().getDisplayMetrics().heightPixels;
                p.x = Math.max(0, (sw - v.getWidth()) / 2);
                p.y = Math.max(0, (sh - v.getHeight()) / 2);
                try { wm.updateViewLayout(v, p); } catch (Throwable ignore) {}
                freezeSize(p, v);
            }
        });
    }

    /** 入场：淡入 + 轻微放大，160ms，走 Decelerate 曲线，跟 60/120Hz 都顺 */
    private void animateIn(final View v) {
        if (v == null) return;
        v.setAlpha(0f);
        v.setScaleX(0.88f);
        v.setScaleY(0.88f);
        v.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(170).setInterpolator(new DecelerateInterpolator())
                .withLayer().start();
    }

    /** 形态切换：先淡出缩小 -> 换尺寸 -> 弹性放大，中间只做一次窗口尺寸变更 */
    private void switchShape(final boolean expandPanel) {
        if (view == null) return;
        final boolean fromBall = Prefs.ball(this);
        view.animate().cancel();
        view.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f)
                .setDuration(90).withEndAction(new Runnable() {
                    @Override public void run() {
                        if (view == null) return;
                        applyStyle(view);
                        view.bind(sampler.snapshot(), Prefs.topN(OverlayService.this));
                        view.setAlpha(0f);
                        view.setScaleX(0.86f);
                        view.setScaleY(0.86f);
                        view.animate().alpha(1f).scaleX(1f).scaleY(1f)
                                .setDuration(180)
                                .setInterpolator(new OvershootInterpolator(1.05f))
                                .withLayer().start();
                    }
                }).start();
    }

    /** 样式变化可能改变尺寸（例如进入圆球模式），先放开 wrap_content，再在下一帧重新固定 */
    private void refreeze(final WindowManager.LayoutParams p, final View v) {
        if (p == null || v == null) return;
        p.width = WindowManager.LayoutParams.WRAP_CONTENT;
        p.height = WindowManager.LayoutParams.WRAP_CONTENT;
        try { wm.updateViewLayout(v, p); } catch (Throwable ignore) {}
        v.postDelayed(new Runnable() {
            @Override public void run() { freezeSize(p, v); }
        }, 40);
    }

    // ------------------------------------------------------------------ 拖动

    private boolean handleTouch(MotionEvent e, boolean isPinned) {
        WindowManager.LayoutParams p = isPinned ? pinnedLp : lp;
        View v = isPinned ? pinned : view;
        if (p == null || v == null) return false;

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
                    if (isPinned) Prefs.setSubPos(this, p.x, p.y);
                    else Prefs.setOverlayPos(this, p.x, p.y);
                } else if (!longFired && System.currentTimeMillis() - downAt < 400) {
                    onTap(isPinned);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                main.removeCallbacks(longPress);
                return true;
            default:
                return false;
        }
    }

    private void onTap(boolean isPinned) {
        if (isPinned) {
            Intent i = new Intent(this, MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            try { startActivity(i); } catch (Throwable ignore) {}
            return;
        }
        if (Prefs.ball(this)) {
            switchShape(false);          // 圆球 -> 面板
            return;
        }
        boolean exp = !Prefs.expanded(this);
        switchShape(exp);                // 展开 / 收起
    }

    // ------------------------------------------------------------------ 采样

    @Override public void onSample(Snap s) {
        if (view != null && mainAttached) view.bind(s, Prefs.topN(this));

        if (pinnedAttached && pinned != null) pinned.bind(s, Prefs.pinned(this));

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
                .setContentText("悬浮窗运行中")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true)
                .setContentIntent(pi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "全部停止", stopPi);
        return b.build();
    }

    private static int piFlags() {
        return Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
    }
}
