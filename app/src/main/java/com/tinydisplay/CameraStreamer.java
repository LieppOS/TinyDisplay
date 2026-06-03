package com.tinydisplay;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.Collections;

/** Streams a low-resolution Camera2 preview into TinyDisplay RGB565 frames. */
final class CameraStreamer {
    interface FrameSink {
        void onFrame(byte[] frame);
        void onError(String message, Throwable t);
    }

    private static final String TAG = "TinyDisplayCamera";
    private static final int SRC_W = 320;
    private static final int SRC_H = 240;
    private static final int FRAME_SKIP_MS = 120; // ~8 fps; enough for the tiny display/HAL

    private final Context context;
    private final FrameSink sink;
    private HandlerThread thread;
    private Handler handler;
    private ImageReader reader;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private int sensorOrientation = 90;
    private long lastFrameMs;
    private volatile boolean frozen = false;
    private volatile int facing = CameraCharacteristics.LENS_FACING_BACK;

    CameraStreamer(Context context, FrameSink sink) {
        this.context = context.getApplicationContext();
        this.sink = sink;
    }

    /** Pause/resume the live preview (long-press freeze). */
    void toggleFreeze() {
        frozen = !frozen;
        Log.i(TAG, "freeze=" + frozen);
    }

    /** Switch between back and front camera (double-tap). */
    void flipCamera() {
        facing = (facing == CameraCharacteristics.LENS_FACING_BACK)
                ? CameraCharacteristics.LENS_FACING_FRONT
                : CameraCharacteristics.LENS_FACING_BACK;
        Log.i(TAG, "flip facing=" + facing);
        if (handler != null) handler.post(this::restart);
    }

    private void restart() {
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (camera != null) camera.close(); } catch (Exception ignored) {}
        session = null;
        camera = null;
        frozen = false;
        openSelected();
    }

    @SuppressLint("MissingPermission")
    void start() {
        if (thread != null) return;
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            sink.onError("CAMERA permission not granted", null);
            return;
        }
        thread = new HandlerThread("tinydisplay_camera");
        thread.start();
        handler = new Handler(thread.getLooper());
        reader = ImageReader.newInstance(SRC_W, SRC_H, ImageFormat.YUV_420_888, 2);
        reader.setOnImageAvailableListener(this::onImageAvailable, handler);
        openSelected();
    }

    @SuppressLint("MissingPermission")
    private void openSelected() {
        CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            String id = chooseCamera(cm, facing);
            if (id == null) throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "No camera");
            CameraCharacteristics c = cm.getCameraCharacteristics(id);
            Integer o = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (o != null) sensorOrientation = o;
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) {
                    camera = device;
                    createSession();
                }
                @Override public void onDisconnected(CameraDevice device) {
                    device.close();
                    camera = null;
                }
                @Override public void onError(CameraDevice device, int error) {
                    device.close();
                    camera = null;
                    sink.onError("Camera error " + error, null);
                }
            }, handler);
        } catch (Throwable t) {
            sink.onError("Camera start failed", t);
            stop();
        }
    }

    void stop() {
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (camera != null) camera.close(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        session = null;
        camera = null;
        reader = null;
        if (thread != null) {
            thread.quitSafely();
            thread = null;
            handler = null;
        }
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

    private void createSession() {
        try {
            Surface surface = reader.getSurface();
            camera.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    session = s;
                    try {
                        CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        b.addTarget(surface);
                        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                        session.setRepeatingRequest(b.build(), null, handler);
                    } catch (Throwable t) {
                        sink.onError("Camera preview failed", t);
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) {
                    sink.onError("Camera session configure failed", null);
                }
            }, handler);
        } catch (Throwable t) {
            sink.onError("Camera session start failed", t);
        }
    }

    private void onImageAvailable(ImageReader r) {
        long now = System.currentTimeMillis();
        Image image = null;
        try {
            image = r.acquireLatestImage();
            if (image == null) return;
            if (frozen) return;
            if (now - lastFrameMs < FRAME_SKIP_MS) return;
            lastFrameMs = now;
            sink.onFrame(render(image, sensorOrientation));
        } catch (Throwable t) {
            sink.onError("Camera frame failed", t);
        } finally {
            if (image != null) image.close();
        }
    }

    private byte[] render(Image image, int orientation) {
        byte[] frame = new byte[com.tinydisplay.hal.TinyLcdHal.FRAME_SIZE];
        Image.Plane yPlane = image.getPlanes()[0];
        Image.Plane uPlane = image.getPlanes()[1];
        Image.Plane vPlane = image.getPlanes()[2];
        ByteBuffer yb = yPlane.getBuffer();
        ByteBuffer ub = uPlane.getBuffer();
        ByteBuffer vb = vPlane.getBuffer();
        int iw = image.getWidth();
        int ih = image.getHeight();
        int crop = Math.min(iw, ih);
        int ox = (iw - crop) / 2;
        int oy = (ih - crop) / 2;

        for (int y = 0; y < RawFontRenderer.H; y++) {
            for (int x = 0; x < RawFontRenderer.W; x++) {
                int lx = (x * crop) / RawFontRenderer.W;
                int ly = (y * crop) / RawFontRenderer.H;
                int[] rp = rotateInSquare(lx, ly, crop, orientation);
                int sx = ox + rp[0];
                int sy = oy + rp[1];
                int rgb = yuvToRgb565(yb, ub, vb, yPlane, uPlane, vPlane, sx, sy, iw, ih);
                RawFontRenderer.setPixel(frame, x, y, rgb);
            }
        }
        // Small status label so the user can tell this is camera mode.
        RawFontRenderer.fillRect(frame, 96, 8, 148, 22, RawFontRenderer.rgb565(0, 0, 0));
        RawFontRenderer.drawCentered(frame, "CAMERA", 20, 2, RawFontRenderer.rgb565(120, 220, 255));
        return frame;
    }

    private int[] rotateInSquare(int x, int y, int size, int orientation) {
        int o = ((orientation % 360) + 360) % 360;
        if (o == 90) return new int[]{y, size - 1 - x};
        if (o == 180) return new int[]{size - 1 - x, size - 1 - y};
        if (o == 270) return new int[]{size - 1 - y, x};
        return new int[]{x, y};
    }

    private int yuvToRgb565(ByteBuffer yb, ByteBuffer ub, ByteBuffer vb,
                            Image.Plane yp, Image.Plane up, Image.Plane vp,
                            int x, int y, int iw, int ih) {
        x = Math.max(0, Math.min(iw - 1, x));
        y = Math.max(0, Math.min(ih - 1, y));
        int yy = yb.get(y * yp.getRowStride() + x * yp.getPixelStride()) & 0xff;
        int uvx = x / 2;
        int uvy = y / 2;
        int u = (ub.get(uvy * up.getRowStride() + uvx * up.getPixelStride()) & 0xff) - 128;
        int v = (vb.get(uvy * vp.getRowStride() + uvx * vp.getPixelStride()) & 0xff) - 128;
        int c = yy - 16;
        if (c < 0) c = 0;
        int r = clamp((298 * c + 409 * v + 128) >> 8);
        int g = clamp((298 * c - 100 * u - 208 * v + 128) >> 8);
        int b = clamp((298 * c + 516 * u + 128) >> 8);
        return RawFontRenderer.rgb565(r, g, b);
    }

    private int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }
}
