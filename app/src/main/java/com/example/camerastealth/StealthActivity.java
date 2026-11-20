package com.example.camerastealth;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

public class StealthActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private View dot;
    private GradientDrawable shape;
    private boolean on;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!CameraStealthService.running) {
                finish();
                return;
            }
            boolean r = CameraStealthService.rec;
            if (r != on) {
                on = r;
                shape.setColor(r ? 0xFFE53935 : 0xFF9E9E9E);
                dot.setAlpha(1f);
            }
            if (r) dot.setAlpha(dot.getAlpha() > 0.6f ? 0.35f : 1f);
            ui.postDelayed(this, 700);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);

        FrameLayout root = new FrameLayout(this);
        dot = new View(this);
        shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(0xFF9E9E9E);
        dot.setBackground(shape);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(10), dp(10),
                Gravity.BOTTOM | Gravity.END);
        lp.rightMargin = dp(28);
        lp.bottomMargin = dp(108);
        root.addView(dot, lp);
        setContentView(root);

        ui.postDelayed(tick, 300);
    }

    @Override
    public void onBackPressed() {
        try {
            startService(new Intent(this, CameraStealthService.class).setAction("disarm"));
        } catch (Exception ignored) { }
        finish();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
