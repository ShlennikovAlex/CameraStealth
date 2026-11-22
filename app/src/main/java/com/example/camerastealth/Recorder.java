package com.example.camerastealth;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.Handler;
import android.util.Size;
import android.view.Surface;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

class Recorder {

    private final Context ctx;
    private final Handler cam;

    private CameraManager manager;
    private CameraDevice device;
    private CameraCaptureSession session;
    private MediaRecorder rec;
    private ImageReader reader;

    private String camId = "0";
    private boolean front;
    private boolean mic = true;

    Recorder(Context ctx, Handler cam) {
        this.ctx = ctx;
        this.cam = cam;
    }

    void setFront(boolean f) {
        front = f;
    }

    void setMic(boolean m) {
        mic = m;
    }

    void start() {
        cam.post(this::open);
    }

    void stop() {
        cam.post(this::release);
    }

    void shot() {
        cam.post(this::shoot);
    }

    // ---------- видео ----------

    private void open() {
        try {
            manager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) return;
            camId = find(front);

            File out = new File(movies(), "VID_" + stamp() + ".mp4");

            rec = new MediaRecorder();
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            if (mic) rec.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            rec.setVideoSize(1280, 720);
            rec.setVideoFrameRate(30);
            rec.setVideoEncodingBitRate(6000000);
            if (mic) {
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                rec.setAudioEncodingBitRate(96000);
                rec.setAudioSamplingRate(44100);
            }
            rec.setOrientationHint(hint());
            rec.setOutputFile(out.getAbsolutePath());
            rec.prepare();

            final Surface surface = rec.getSurface();
            manager.openCamera(camId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice c) {
                    device = c;
                    configureVideo(c, surface);
                }

                @Override
                public void onDisconnected(CameraDevice c) {
                    c.close();
                    device = null;
                }

                @Override
                public void onError(CameraDevice c, int e) {
                    c.close();
                    device = null;
                    release();
                }
            }, cam);
        } catch (Exception ignored) {
            release();
        }
    }

    private void configureVideo(CameraDevice c, Surface surface) {
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
                                rec.start();
                            } catch (Exception ignored) {
                                release();
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            release();
                        }
                    }, cam);
        } catch (Exception ignored) {
            release();
        }
    }

    // ---------- фото ----------

    private void shoot() {
        try {
            manager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) return;
            camId = find(front);

            final Size size = pickSize();
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(),
                    ImageFormat.JPEG, 2);

            reader.setOnImageAvailableListener(r -> {
                Image img = null;
                try {
                    img = r.acquireNextImage();
                    ByteBuffer buf = img.getPlanes()[0].getBuffer();
                    byte[] data = new byte[buf.remaining()];
                    buf.get(data);

                    File out = new File(pictures(), "IMG_" + stamp() + ".jpg");
                    FileOutputStream fos = new FileOutputStream(out);
                    try {
                        fos.write(data);
                    } finally {
                        fos.close();
                    }
                } catch (Exception ignored) {
                } finally {
                    try { if (img != null) img.close(); } catch (Exception ignored) { }
                    try { r.close(); } catch (Exception ignored) { }
                    closeDevice();
                }
            }, cam);

            final Surface surface = reader.getSurface();
            manager.openCamera(camId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice c) {
                    device = c;
                    try {
                        c.createCaptureSession(Collections.singletonList(surface),
                                new CameraCaptureSession.StateCallback() {
                                    @Override
                                    public void onConfigured(CameraCaptureSession s) {
                                        session = s;
                                        try {
                                            CaptureRequest.Builder b =
                                                    c.createCaptureRequest(
                                                            CameraDevice.TEMPLATE_STILL_CAPTURE);
                                            b.addTarget(surface);
                                            b.set(CaptureRequest.JPEG_ORIENTATION, hint());
                                            s.capture(b.build(), null, cam);
                                        } catch (Exception ignored) {
                                            closeDevice();
                                        }
                                    }

                                    @Override
                                    public void onConfigureFailed(CameraCaptureSession s) {
                                        closeDevice();
                                    }
                                }, cam);
                    } catch (Exception ignored) {
                        closeDevice();
                    }
                }

                @Override
                public void onDisconnected(CameraDevice c) {
                    c.close();
                    device = null;
                }

                @Override
                public void onError(CameraDevice c, int e) {
                    c.close();
                    device = null;
                    closeDevice();
                }
            }, cam);
        } catch (Exception ignored) {
            closeDevice();
        }
    }

    private Size pickSize() {
        try {
            android.hardware.camera2.params.StreamConfigurationMap map =
                    manager.getCameraCharacteristics(camId)
                            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return new Size(1280, 720);
            Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
            if (sizes == null || sizes.length == 0) return new Size(1280, 720);

            Size best = null;
            for (Size s : sizes) {
                if (s.getWidth() > 2000) continue;
                if (best == null
                        || s.getWidth() * s.getHeight() > best.getWidth() * best.getHeight()) {
                    best = s;
                }
            }
            return best == null ? sizes[0] : best;
        } catch (Exception ignored) {
            return new Size(1280, 720);
        }
    }

    private void closeDevice() {
        try {
            if (session != null) {
                session.stopRepeating();
                session.close();
            }
        } catch (Exception ignored) { }
        session = null;
        try { if (device != null) device.close(); } catch (Exception ignored) { }
        device = null;
    }

    // ---------- общее ----------

    private void release() {
        try {
            if (session != null) {
                session.stopRepeating();
                session.close();
            }
        } catch (Exception ignored) { }
        session = null;
        try { if (rec != null) rec.stop(); } catch (Exception ignored) { }
        try { if (rec != null) rec.reset(); } catch (Exception ignored) { }
        try { if (rec != null) rec.release(); } catch (Exception ignored) { }
        rec = null;
        try { if (device != null) device.close(); } catch (Exception ignored) { }
        device = null;
    }

    private File movies() {
        File dir = new File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Stealth");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private File pictures() {
        File dir = new File(ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Stealth");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    private String find(boolean wantFront) {
        try {
            if (manager == null) {
                manager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            }
            if (manager == null) return camId;
            for (String id : manager.getCameraIdList()) {
                Integer f = manager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING);
                if (f == null) continue;
                if (wantFront && f == CameraCharacteristics.LENS_FACING_FRONT) return id;
                if (!wantFront && f == CameraCharacteristics.LENS_FACING_BACK) return id;
            }
        } catch (Exception ignored) { }
        return camId;
    }

    private int hint() {
        try {
            int sensor = manager.getCameraCharacteristics(camId)
                    .get(CameraCharacteristics.SENSOR_ORIENTATION);
            int rot = rotation();
            return front ? (sensor + rot) % 360 : (sensor - rot + 360) % 360;
        } catch (Exception ignored) {
            return 90;
        }
    }

    private int rotation() {
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return 0;
            int r = wm.getDefaultDisplay().getRotation();
            if (r == Surface.ROTATION_90) return 90;
            if (r == Surface.ROTATION_180) return 180;
            if (r == Surface.ROTATION_270) return 270;
        } catch (Exception ignored) { }
        return 0;
    }
}
