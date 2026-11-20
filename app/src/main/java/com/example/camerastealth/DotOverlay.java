package com.example.camerastealth;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

class DotOverlay {

    interface Listener {
        void onTap();
        void onLongPress();
    }

    private static final int COLOR_IDLE = 0xFF9E9E9E;
    private static final int COLOR_REC = 0xFFE53935;
    private static final int DP_DOT = 10;
    private static final int DP_ZONE = 44;

    private final Context ctx;
    private Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private WindowManager wm;
    private FrameLayout zone;
    private View dot;
    private GradientDrawable dotDrawable;
    private boolean longFired;

    private final Runnable longPress = () -> {
        longFired = true;
        listener.onLongPress();
    };

    private final Runnable blink = new Runnable() {
        @Override
        public void run() {
            if (dot == null) return;
            dot.setAlpha(dot.getAlpha() > 0.6f ? 0.35f : 1f);
            ui.postDelayed(this, 700);
        }
    };

    DotOverlay(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
    }

    void show() {
        if (zone != null) return;

        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);

        dotDrawable = new GradientDrawable();
        dotDrawable.setShape(GradientDrawable.OVAL);
        dotDrawable.setColor(COLOR_IDLE);

        zone = new FrameLayout(ctx);
        dot = new View(ctx);
        dot.setBackground(dotDrawable);
        zone.addView(dot, new FrameLayout.LayoutParams(dp(DP_DOT), dp(DP_DOT), Gravity.CENTER));

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                dp(DP_ZONE), dp(DP_ZONE),
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.BOTTOM | Gravity.END;
        p.x = dp(12);
        p.y = dp(96);

        zone.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                longFired = false;
                ui.postDelayed(longPress, 480);
                return true;
            }
            if (e.getAction() == MotionEvent.ACTION_UP
                    || e.getAction() == MotionEvent.ACTION_CANCEL) {
                ui.removeCallbacks(longPress);
                if (!longFired) listener.onTap();
                return true;
            }
            return true;
        });

        try {
            wm.addView(zone, p);
        } catch (Exception ignored) {
            zone = null;
        }
    }

    void hide() {
        ui.removeCallbacks(blink);
        ui.removeCallbacks(longPress);
        if (zone == null) return;
        try { wm.removeView(zone); } catch (Exception ignored) { }
        zone = null;
        dot = null;
    }

    void setRecording(boolean rec) {
        if (dot == null) return;
        ui.removeCallbacks(blink);
        dotDrawable.setColor(rec ? COLOR_REC : COLOR_IDLE);
        dot.setAlpha(1f);
        if (rec) ui.postDelayed(blink, 700);
    }

    private int dp(int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
