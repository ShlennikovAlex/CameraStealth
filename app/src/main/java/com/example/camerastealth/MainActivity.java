package com.example.camerastealth;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Switch;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final String PREFS = "settings";
    private static final String K_SOUND = "sound";
    private static final String K_VIBRO = "vibro";
    private static final String K_MIC = "mic";
    private static final int REQ = 7;

    private SharedPreferences prefs;
    private boolean pendingArm;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        Switch swSound = findViewById(R.id.sw_sound);
        Switch swVibro = findViewById(R.id.sw_vibro);
        Switch swMic = findViewById(R.id.sw_mic);

        swSound.setChecked(prefs.getBoolean(K_SOUND, false));
        swVibro.setChecked(prefs.getBoolean(K_VIBRO, true));
        swMic.setChecked(prefs.getBoolean(K_MIC, true));

        swSound.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean(K_SOUND, on).apply());
        swVibro.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean(K_VIBRO, on).apply());
        swMic.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean(K_MIC, on).apply());

        findViewById(R.id.btn_collapse).setOnClickListener(v -> collapse());

        findViewById(R.id.btn_off).setOnClickListener(v -> {
            stopService(new Intent(this, CameraStealthService.class));
            finish();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingArm && Settings.canDrawOverlays(this)) {
            pendingArm = false;
            askPermissions();
        }
    }

    private void collapse() {
        if (!Settings.canDrawOverlays(this)) {
            pendingArm = true;
            Toast.makeText(this, "Разреши «поверх других приложений» и вернись", Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) { }
            return;
        }
        askPermissions();
    }

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.CAMERA);
        }
        if (prefs.getBoolean(K_MIC, true)
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (need.isEmpty()) {
            arm();
        } else {
            requestPermissions(need.toArray(new String[0]), REQ);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            arm();
        } else {
            Toast.makeText(this, "Без камеры не сработает", Toast.LENGTH_SHORT).show();
        }
    }

    private void arm() {
        Intent i = new Intent(this, CameraStealthService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            finish();
        } catch (Exception e) {
            Toast.makeText(this, "Не запустилось", Toast.LENGTH_SHORT).show();
        }
    }
}
