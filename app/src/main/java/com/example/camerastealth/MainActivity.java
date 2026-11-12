package com.example.camerastealth;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Switch;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final String PREFS = "settings";
    private static final String K_SOUND = "sound";
    private static final String K_VIBRO = "vibro";
    private static final String K_MIC = "mic";

    private SharedPreferences prefs;

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

        findViewById(R.id.btn_collapse).setOnClickListener(v ->
                Toast.makeText(this, "Оверлей и служба — следующий шаг", Toast.LENGTH_SHORT).show());

        findViewById(R.id.btn_off).setOnClickListener(v ->
                Toast.makeText(this, "Пока просто закрываю", Toast.LENGTH_SHORT).show());
    }
}
