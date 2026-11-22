package com.example.camerastealth;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

class DotOverlay {

    interface Listener {
        void onTap();
        void onLongPress();
        void onVolumeDown();
        void onVolumeUp();
    }

    private static final int COLOR_IDLE = 0xFF9E9E9E;
    private static final int COLOR_FRONT = 0xFF3B82F6;
    private static final int COLOR_REC = 0xFFE53935;
    private static final int DP_DOT = 10;
    private static final int DP_ZONE = 56;
    private static final int DP_BOTTOM = 156;
    private static final long LONG_MS = 330;

    private final Context ctx;
    private Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private WindowManager wm;
    private FrameLayout zone;
    private View dot;
    private GradientDrawable dotDrawable;
    private boolean longFired;
    private boolean front;
    private boolean recording;

    private final Runnable longPress = () -> {
        longFired = true;
        buzz(45);
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

    private class Zone extends FrameLayout {
        Zone(Context c) {
            super(c);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_OUTSIDE) return false;

            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                longFired = false;
                if (dot != null) dot.setAlpha(0.4f);
                ui.postDelayed(longPress, LONG_MS);
                return true;
            }
            if (e.getAction() == MotionEvent.ACTION_UP
                    || e.getAction() == MotionEvent.ACTION_CANCEL) {
                ui.removeCallbacks(longPress);
                if (dot != null) dot.setAlpha(1f);
                if (!longFired) listener.onTap();
                return true;
            }
            return true;
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            int code = e.getKeyCode();

            if (code == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) listener.onVolumeDown();
                return true;
            }
            if (code == KeyEvent.KEYCODE_VOLUME_UP) {
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                    listener.onVolumeUp();
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_BACK
                    && e.getAction() == KeyEvent.ACTION_DOWN) {
                listener.onLongPress();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
    }

    void show() {
        if (zone != null) return;

        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);

        dotDrawable = new GradientDrawable();
        dotDrawable.setShape(GradientDrawable.OVAL);
        dotDrawable.setColor(front ? COLOR_FRONT : COLOR_IDLE);

        zone = new Zone(ctx);
        zone.setFocusableInTouchMode(true);
        dot = new View(ctx);
        dot.setBackground(dotDrawable);
        zone.addView(dot, new FrameLayout.LayoutParams(dp(DP_DOT), dp(DP_DOT), Gravity.CENTER));

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                dp(DP_ZONE), dp(DP_ZONE),
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.BOTTOM | Gravity.END;
        p.x = dp(6);
        p.y = dp(DP_BOTTOM);

        try {
            wm.addView(zone, p);
            zone.requestFocus();
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
        recording = rec;
        ui.removeCallbacks(blink);
        dotDrawable.setColor(rec ? COLOR_REC : (front ? COLOR_FRONT : COLOR_IDLE));
        dot.setAlpha(1f);
        if (rec) ui.postDelayed(blink, 700);
    }

    void setFront(boolean isFront) {
        front = isFront;
        if (dot == null || recording) return;
        dotDrawable.setColor(isFront ? COLOR_FRONT : COLOR_IDLE);
    }

    private void buzz(int ms) {
        try {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(ms, 255));
            } else {
                v.vibrate(ms);
            }
        } catch (Exception ignored) { }
    }

    private int dp(int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
