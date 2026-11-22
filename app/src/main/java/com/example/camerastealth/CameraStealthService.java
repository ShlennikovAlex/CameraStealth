package com.example.camerastealth;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.MediaActionSound;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;

public class CameraStealthService extends Service implements DotOverlay.Listener {

    private static final String CHANNEL = "stealth";
    private static final long MAX_RECORD_MS = 20 * 60 * 1000L;
    private static final long BURST_MS = 320;
    private static final int BURST_LONG = 4;

    public static volatile boolean running;
    public static volatile boolean rec;

    private Handler ui;
    private Handler cam;
    private HandlerThread camThread;

    private DotOverlay dot;
    private Recorder engine;

    private boolean front;
    private boolean recording;
    private boolean photo;
    private boolean mic;
    private boolean sound;
    private boolean vibro;
    private int burst;

    private MediaActionSound actionSound;
    private PowerManager.WakeLock wakeLock;

    private final Runnable burstEnd = this::onBurstEnd;
    private final Runnable maxStop = this::stopRecording;

    private final BroadcastReceiver volumeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            if (!"android.media.VOLUME_CHANGED_ACTION".equals(intent.getAction())) return;

            int stream = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1);
            if (stream != AudioManager.STREAM_MUSIC) return;

            int now = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1);
            if (now < 0) return;

            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;

            int base;
            try { base = am.getStreamVolume(AudioManager.STREAM_MUSIC); }
            catch (Exception e) { return; }

            if (now < base) {
                try { am.setStreamVolume(AudioManager.STREAM_MUSIC, base, 0); }
                catch (Exception ignored) { }
                onVolumeDown();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        ui = new Handler(Looper.getMainLooper());
        camThread = new HandlerThread("stealth-cam");
        camThread.start();
        cam = new Handler(camThread.getLooper());

        SharedPreferences p = getSharedPreferences("settings", MODE_PRIVATE);
        mic = p.getBoolean("mic", true);
        sound = p.getBoolean("sound", false);
        vibro = p.getBoolean("vibro", true);
        photo = "photo".equals(p.getString("mode", "video"));

        engine = new Recorder(this, cam);
        engine.setMic(mic);

        running = true;

        createChannel();
        startForegroundNow();

        if (Settings.canDrawOverlays(this)) {
            dot = new DotOverlay(this, this);
            dot.show();
        }

        registerVolume();
        acquireWake();
    }

    @Override
    public void onTap() {
        toggle();
    }

    @Override
    public void onLongPress() {
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) { }
        disarm();
    }

    @Override
    public void onVolumeDown() {
        burst++;
        ui.removeCallbacks(burstEnd);
        ui.postDelayed(burstEnd, BURST_MS);
    }

    @Override
    public void onVolumeUp() {
        switchCamera();
    }

    private void toggle() {
        if (photo) {
            snap();
            return;
        }
        if (recording) stopRecording();
        else startRecording();
    }

    private void onBurstEnd() {
        int count = burst;
        burst = 0;
        if (count >= BURST_LONG) switchCamera();
        else toggle();
    }

    private void switchCamera() {
        boolean was = recording;
        if (was) stopRecording();
        front = !front;
        engine.setFront(front);
        if (dot != null) dot.setFront(front);
        buzz(35);
        if (was) ui.postDelayed(this::startRecording, 700);
    }

    private void registerVolume() {
        try {
            IntentFilter f = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(volumeReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(volumeReceiver, f);
            }
        } catch (Exception ignored) { }
    }

    private void snap() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;

        feedback(true);
        if (dot != null) {
            dot.setRecording(true);
            ui.postDelayed(() -> {
                if (dot != null && !recording) dot.setRecording(false);
            }, 260);
        }
        engine.shot();
    }

    private void startRecording() {
        if (recording) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;

        recording = true;
        rec = true;
        if (dot != null) dot.setRecording(true);
        feedback(true);
        ui.removeCallbacks(maxStop);
        ui.postDelayed(maxStop, MAX_RECORD_MS);
        engine.start();
    }

    private void stopRecording() {
        if (!recording) return;
        recording = false;
        rec = false;
        ui.removeCallbacks(maxStop);
        if (dot != null) dot.setRecording(false);
        feedback(false);
        engine.stop();
    }

    private void feedback(boolean start) {
        if (vibro) buzz(start ? 60 : 40);
        if (sound) {
            try {
                if (actionSound == null) {
                    actionSound = new MediaActionSound();
                    actionSound.load(MediaActionSound.START_VIDEO_RECORDING);
                    actionSound.load(MediaActionSound.STOP_VIDEO_RECORDING);
                }
                actionSound.play(start
                        ? MediaActionSound.START_VIDEO_RECORDING
                        : MediaActionSound.STOP_VIDEO_RECORDING);
            } catch (Exception ignored) { }
        }
    }

    private void buzz(int ms) {
        try {
            Vibrator v = null;
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager m =
                        (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (m != null) v = m.getDefaultVibrator();
            }
            if (v == null) v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;

            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(ms, 255));
            } else {
                v.vibrate(ms);
            }
        } catch (Exception ignored) { }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Съёмка",
                NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private void startForegroundNow() {
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                int type = 0;
                if (checkSelfPermission(Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED) {
                    type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
                }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED) {
                    type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                }
                if (type != 0) {
                    startForeground(1, n, type);
                    return;
                }
            }
            startForeground(1, n);
        } catch (Exception ignored) { }
    }

    private Notification buildNotification() {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);

        return b.setContentTitle("Заметки")
                .setContentText("Свернуто")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void acquireWake() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "stealth:screen");
            wakeLock.acquire();
        } catch (Exception ignored) { }
    }

    private void disarm() {
        stopRecording();
        if (dot != null) dot.hide();
        stopSelf();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (dot != null) dot.show();
        String action = intent == null ? null : intent.getAction();
        if ("toggle".equals(action)) toggle();
        if ("disarm".equals(action)) disarm();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        stopRecording();
        running = false;
        if (dot != null) dot.hide();
        try { unregisterReceiver(volumeReceiver); } catch (Exception ignored) { }
        try { if (actionSound != null) actionSound.release(); } catch (Exception ignored) { }
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) { }
        if (camThread != null) camThread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
