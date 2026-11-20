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
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.AudioManager;
import android.media.MediaActionSound;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.Surface;
import android.view.WindowManager;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

public class CameraStealthService extends Service implements DotOverlay.Listener {

    private static final String CHANNEL = "stealth";
    private static final long MAX_RECORD_MS = 20 * 60 * 1000L;
    private static final long BURST_MS = 320;
    private static final int BURST_LONG = 4;

    private Handler ui;
    private Handler cam;
    private HandlerThread camThread;

    private DotOverlay dot;

    private CameraManager camManager;
    private CameraDevice device;
    private CameraCaptureSession session;
    private MediaRecorder recorder;

    private String camId = "0";
    private boolean front;
    private boolean recording;
    private boolean mic;
    private boolean sound;
    private boolean vibro;
    private int burst;
    private int baseVolume = -1;

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
            if (now < 0 || baseVolume < 0) return;
            if (now == baseVolume) return;

            boolean down = now < baseVolume;

            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                try { am.setStreamVolume(AudioManager.STREAM_MUSIC, baseVolume, 0); }
                catch (Exception ignored) { }
            }

            if (!down) return;

            burst++;
            ui.removeCallbacks(burstEnd);
            ui.postDelayed(burstEnd, BURST_MS);
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

        createChannel();
        startForegroundNow();

        dot = new DotOverlay(this, this);
        dot.show();

        rememberVolume();
        registerVolume();
        acquireWake();
    }

    @Override
    public void onTap() {
        toggle();
    }

    @Override
    public void onLongPress() {
        disarm();
    }

    // ---------- управление ----------

    private void toggle() {
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
        camId = findCamera(front);
        if (was) ui.postDelayed(this::startRecording, 700);
    }

    private String findCamera(boolean wantFront) {
        try {
            CameraManager m = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (m == null) return camId;
            for (String id : m.getCameraIdList()) {
                Integer facing = m.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING);
                if (facing == null) continue;
                if (wantFront && facing == CameraCharacteristics.LENS_FACING_FRONT) return id;
                if (!wantFront && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
            }
        } catch (Exception ignored) { }
        return camId;
    }

    // ---------- громкость ----------

    private void rememberVolume() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            baseVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (Build.VERSION.SDK_INT >= 28
                    && baseVolume <= am.getStreamMinVolume(AudioManager.STREAM_MUSIC)) {
                baseVolume = am.getStreamMinVolume(AudioManager.STREAM_MUSIC) + 1;
                am.setStreamVolume(AudioManager.STREAM_MUSIC, baseVolume, 0);
            }
        } catch (Exception ignored) { }
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

    // ---------- запись ----------

    private void startRecording() {
        if (recording) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;

        recording = true;
        dot.setRecording(true);
        feedback(true);
        ui.removeCallbacks(maxStop);
        ui.postDelayed(maxStop, MAX_RECORD_MS);
        cam.post(this::openAndRecord);
    }

    private void openAndRecord() {
        try {
            camManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (camManager == null) { abort(); return; }
            camId = findCamera(front);

            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Stealth");
            if (!dir.exists()) dir.mkdirs();
            String name = "VID_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date()) + ".mp4";
            File out = new File(dir, name);

            recorder = new MediaRecorder();
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            if (mic) recorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setVideoSize(1280, 720);
            recorder.setVideoFrameRate(30);
            recorder.setVideoEncodingBitRate(6000000);
            if (mic) {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                recorder.setAudioEncodingBitRate(96000);
                recorder.setAudioSamplingRate(44100);
            }
            recorder.setOrientationHint(orientationHint());
            recorder.setOutputFile(out.getAbsolutePath());
            recorder.prepare();

            final Surface surface = recorder.getSurface();
            camManager.openCamera(camId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice c) {
                    device = c;
                    configure(c, surface);
                }

                @Override
                public void onDisconnected(CameraDevice c) {
                    c.close();
                    device = null;
                }

                @Override
                public void onError(CameraDevice c, int error) {
                    c.close();
                    device = null;
                    abort();
                }
            }, cam);
        } catch (Exception ignored) {
            abort();
        }
    }

    private void configure(CameraDevice c, Surface surface) {
        try {
            c.createCaptureSession(Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            try {
                                CaptureRequest.Builder b =
                                        c.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                                b.addTarget(surface);
                                s.setRepeatingRequest(b.build(), null, cam);
                                recorder.start();
                            } catch (Exception ignored) {
                                abort();
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            abort();
                        }
                    }, cam);
        } catch (Exception ignored) {
            abort();
        }
    }

    private void stopRecording() {
        if (!recording) return;
        recording = false;
        ui.removeCallbacks(maxStop);
        dot.setRecording(false);
        feedback(false);
        cam.post(this::releaseCamera);
    }

    private void releaseCamera() {
        try {
            if (session != null) {
                session.stopRepeating();
                session.close();
            }
        } catch (Exception ignored) { }
        session = null;
        try { if (recorder != null) recorder.stop(); } catch (Exception ignored) { }
        try { if (recorder != null) recorder.reset(); } catch (Exception ignored) { }
        try { if (recorder != null) recorder.release(); } catch (Exception ignored) { }
        recorder = null;
        try { if (device != null) device.close(); } catch (Exception ignored) { }
        device = null;
    }

    private void abort() {
        releaseCamera();
        ui.post(() -> {
            if (recording) {
                recording = false;
                ui.removeCallbacks(maxStop);
                dot.setRecording(false);
            }
        });
    }

    private int orientationHint() {
        try {
            int sensor = camManager.getCameraCharacteristics(camId)
                    .get(CameraCharacteristics.SENSOR_ORIENTATION);
            int rotation = getDisplayRotation();
            return front ? (sensor + rotation) % 360 : (sensor - rotation + 360) % 360;
        } catch (Exception ignored) {
            return 90;
        }
    }

    private int getDisplayRotation() {
        try {
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return 0;
            int r = wm.getDefaultDisplay().getRotation();
            if (r == Surface.ROTATION_90) return 90;
            if (r == Surface.ROTATION_180) return 180;
            if (r == Surface.ROTATION_270) return 270;
        } catch (Exception ignored) { }
        return 0;
    }

    // ---------- отклик ----------

    private void feedback(boolean start) {
        if (vibro) {
            try {
                Vibrator v = vibrator();
                if (v != null) {
                    if (Build.VERSION.SDK_INT >= 26) {
                        v.vibrate(VibrationEffect.createOneShot(28,
                                VibrationEffect.DEFAULT_AMPLITUDE));
                    } else {
                        v.vibrate(28);
                    }
                }
            } catch (Exception ignored) { }
        }
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

    private Vibrator vibrator() {
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager m = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            return m == null ? null : m.getDefaultVibrator();
        }
        return (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
    }

    // ---------- служба ----------

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
        dot.hide();
        stopSelf();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (dot != null) dot.show();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        stopRecording();
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
