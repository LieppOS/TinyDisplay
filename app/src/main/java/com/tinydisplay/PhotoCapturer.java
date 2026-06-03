package com.tinydisplay;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.Manifest;
import android.content.pm.PackageManager;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Collections;

/**
 * Captures a single full-resolution JPEG still (not the tiny sub-screen frame)
 * and saves it to Pictures/TinyDisplay via MediaStore. Used by the F1/F2
 * hardware shutter button.
 */
final class PhotoCapturer {

    interface Result {
        void onSaved(Uri uri);
        void onError(String message, Throwable t);
    }

    private static final String TAG = "TinyDisplayPhoto";

    private final Context context;
    private final int facing;
    private final Result cb;
    private HandlerThread thread;
    private Handler handler;
    private ImageReader reader;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private int sensorOrientation = 0;
    private boolean done = false;

    private PhotoCapturer(Context ctx, int facing, Result cb) {
        this.context = ctx.getApplicationContext();
        this.facing = facing;
        this.cb = cb;
    }

    static void capture(Context ctx, int facing, Result cb) {
        new PhotoCapturer(ctx, facing, cb).start();
    }

    @SuppressLint("MissingPermission")
    private void start() {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cb.onError("CAMERA permission not granted", null);
            return;
        }
        thread = new HandlerThread("tinydisplay_photo");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(this::open);
    }

    @SuppressLint("MissingPermission")
    private void open() {
        CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            String id = chooseCamera(cm, facing);
            if (id == null) { fail("No camera available", null); return; }
            CameraCharacteristics c = cm.getCameraCharacteristics(id);
            Integer o = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (o != null) sensorOrientation = o;
            Size size = chooseJpegSize(c);
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.JPEG, 1);
            reader.setOnImageAvailableListener(this::onImage, handler);
            Log.i(TAG, "capture id=" + id + " facing=" + facing + " size=" + size);
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) { camera = d; createSession(); }
                @Override public void onDisconnected(CameraDevice d) { d.close(); }
                @Override public void onError(CameraDevice d, int e) { d.close(); fail("Camera error " + e, null); }
            }, handler);
        } catch (Throwable t) {
            fail("Camera open failed", t);
        }
    }

    private void createSession() {
        try {
            reader.getSurface();
            camera.createCaptureSession(Collections.singletonList(reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            try {
                                CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                                b.addTarget(reader.getSurface());
                                b.set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation);
                                s.capture(b.build(), null, handler);
                            } catch (Throwable t) {
                                fail("Capture request failed", t);
                            }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) {
                            fail("Capture session configure failed", null);
                        }
                    }, handler);
        } catch (Throwable t) {
            fail("Capture session failed", t);
        }
    }

    private void onImage(ImageReader r) {
        Image image = null;
        try {
            image = r.acquireNextImage();
            if (image == null) { fail("No image", null); return; }
            ByteBuffer buf = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            Uri uri = save(bytes);
            finish();
            if (uri != null) cb.onSaved(uri); else fail("Save returned null", null);
        } catch (Throwable t) {
            fail("Image save failed", t);
        } finally {
            if (image != null) image.close();
        }
    }

    private Uri save(byte[] jpeg) throws Exception {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, "TinyDisplay_" + System.currentTimeMillis() + ".jpg");
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TinyDisplay");
        }
        Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) return null;
        try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IllegalStateException("openOutputStream null");
            out.write(jpeg);
        }
        Log.i(TAG, "photo saved: " + uri);
        return uri;
    }

    private String chooseCamera(CameraManager cm, int wantFacing) throws CameraAccessException {
        String fallback = null;
        for (String id : cm.getCameraIdList()) {
            CameraCharacteristics c = cm.getCameraCharacteristics(id);
            Integer f = c.get(CameraCharacteristics.LENS_FACING);
            if (fallback == null) fallback = id;
            if (f != null && f == wantFacing) return id;
        }
        return fallback;
    }

    private Size chooseJpegSize(CameraCharacteristics c) {
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map != null ? map.getOutputSizes(ImageFormat.JPEG) : null;
        if (sizes == null || sizes.length == 0) return new Size(1280, 960);
        Size best = sizes[0];
        for (Size s : sizes) {
            if ((long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) best = s;
        }
        return best;
    }

    private void fail(String msg, Throwable t) {
        if (done) return;
        finish();
        cb.onError(msg, t);
    }

    private void finish() {
        if (done) return;
        done = true;
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (camera != null) camera.close(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        session = null; camera = null; reader = null;
        if (thread != null) { thread.quitSafely(); thread = null; handler = null; }
    }
}
