package com.example.camerastealth;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.GradientDrawable;
import android.graphics.PixelFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.AudioManager;
import android.media.MediaActionSound;
import android.media.MediaMetadata;
import android.media.MediaRecorder;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
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
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

public class CameraStealthService extends Service {

    private static final String CHANNEL = "stealth";
    private static final long MAX_RECORD_MS = 20 * 60 * 1000L;
    private static final long BURST_MS = 320;
    private static final int BURST_LONG = 4;

    private static final int COLOR_IDLE = 0xFF9E9E9E;
    private static final int COLOR_REC = 0xFFE53935;
    private static final int DP_DOT = 10;
    private static final int DP_ZONE = 44;

    private WindowManager wm;
    private FrameLayout zone;
    private View dot;
    private GradientDrawable dotDrawable;
    private WindowManager.LayoutParams zoneParams;

    private Handler ui;
    private Handler cam;
    private HandlerThread camThread;

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
    private boolean longPressed;
    private int burst;

    private MediaSession mediaSession;
    private MediaActionSound actionSound;
    private PowerManager.WakeLock wakeLock;

    private final Runnable burstEnd = this::onBurstEnd;

    private final Runnable longPress = () -> {
        longPressed = true;
        disarm();
    };

    private final Runnable blink = new Runnable() {
        @Override
        public void run() {
            if (!recording) return;
            dot.setAlpha(dot.getAlpha() > 0.6f ? 0.35f : 1f);
            ui.postDelayed(this, 700);
        }
    };

    private final Runnable maxStop = this::stopRecording;

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
        showDot();
        setupVolumeKeys();
        acquireWake();
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

    // ---------- запись ----------

    private void startRecording() {
        if (recording) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;

        recording = true;
        setDot(true);
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
                                CaptureRequest.Builder b = c.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
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
        ui.removeCallbacks(blink);
        setDot(false);
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
                ui.removeCallbacks(blink);
                setDot(false);
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
            int r = wm.getDefaultDisplay().getRotation();
            if (r == Surface.ROTATION_90) return 90;
            if (r == Surface.ROTATION_180) return 180;
            if (r == Surface.ROTATION_270) return 270;
        } catch (Exception ignored) { }
        return 0;
    }

    // ---------- точка ----------

    private void showDot() {
        if (zone != null) return;
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        dotDrawable = new GradientDrawable();
        dotDrawable.setShape(GradientDrawable.OVAL);
        dotDrawable.setColor(COLOR_IDLE);

        zone = new FrameLayout(this);
        dot = new View(this);
        dot.setBackground(dotDrawable);
        FrameLayout.LayoutParams lp =
                new FrameLayout.LayoutParams(dp(DP_DOT), dp(DP_DOT), Gravity.CENTER);
        zone.addView(dot, lp);

        zoneParams = new WindowManager.LayoutParams(
                dp(DP_ZONE), dp(DP_ZONE),
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        zoneParams.gravity = Gravity.BOTTOM | Gravity.END;
        zoneParams.x = dp(12);
        zoneParams.y = dp(96);

        zone.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    longPressed = false;
                    ui.postDelayed(longPress, 480);
                    return true;
                }
                if (e.getAction() == MotionEvent.ACTION_UP
                        || e.getAction() == MotionEvent.ACTION_CANCEL) {
                    ui.removeCallbacks(longPress);
                    if (!longPressed) toggle();
                    return true;
                }
                return true;
            }
        });

        try {
            wm.addView(zone, zoneParams);
        } catch (Exception ignored) {
            zone = null;
        }
    }

    private void hideDot() {
        if (zone == null) return;
        try { wm.removeView(zone); } catch (Exception ignored) { }
        zone = null;
        dot = null;
    }

    private void setDot(boolean rec) {
        if (dot == null) return;
        ui.removeCallbacks(blink);
        dotDrawable.setColor(rec ? COLOR_REC : COLOR_IDLE);
        dot.setAlpha(1f);
        if (rec) ui.postDelayed(blink, 700);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------- отклик ----------

    private void feedback(boolean start) {
        if (vibro) {
            try {
                Vibrator v = vibrator();
                if (v != null) {
                    if (Build.VERSION.SDK_INT >= 26) {
                        v.vibrate(VibrationEffect.createOneShot(28, VibrationEffect.DEFAULT_AMPLITUDE));
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

    // ---------- качельки ----------

    private void setupVolumeKeys() {
        try {
            mediaSession = new MediaSession(this, "stealth");
            mediaSession.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Заметки")
                    .build());
            mediaSession.setPlaybackState(new PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                    .build());

            mediaSession.setPlaybackToRemote(
                    new VolumeProvider(VolumeProvider.VOLUME_CONTROL_RELATIVE, 100, 50) {
                        @Override
                        public void onAdjustVolume(int direction) {
                            if (direction > 0) {
                                try {
                                    AudioManager am =
                                            (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                                    if (am != null) {
                                        am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                                                AudioManager.ADJUST_RAISE,
                                                AudioManager.FLAG_SHOW_UI);
                                    }
                                } catch (Exception ignored) { }
                                return;
                            }
                            burst++;
                            ui.removeCallbacks(burstEnd);
                            ui.postDelayed(burstEnd, BURST_MS);
                        }
                    });
            mediaSession.setActive(true);
        } catch (Exception ignored) { }
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
        hideDot();
        stopSelf();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        showDot();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        stopRecording();
        hideDot();
        try { if (mediaSession != null) mediaSession.release(); } catch (Exception ignored) { }
        try { if (actionSound != null) actionSound.release(); } catch (Exception ignored) { }
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) { }
        if (camThread != null) camThread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
