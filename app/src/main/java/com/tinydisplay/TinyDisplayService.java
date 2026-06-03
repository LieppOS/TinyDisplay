package com.tinydisplay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.tinydisplay.hal.TinyLcdHal;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class TinyDisplayService extends Service {

    private static final String TAG = "TinyDisplayService";
    private static final String CHANNEL_ID = "tinydisplay_fg";
    static final String EXTRA_BOOT_COMPLETED = "com.tinydisplay.EXTRA_BOOT_COMPLETED";

    public static final String ACTION_PREVIEW = "com.tinydisplay.action.PREVIEW";
    public static final String ACTION_SHOW_NOTIFICATION = "com.tinydisplay.action.SHOW_NOTIFICATION";
    public static final String ACTION_REAR_TAP = "com.tinydisplay.action.REAR_TAP";
    public static final String ACTION_REAR_LONGPRESS = "com.tinydisplay.action.REAR_LONGPRESS";
    public static final String ACTION_REAR_SWIPE = "com.tinydisplay.action.REAR_SWIPE";
    public static final String ACTION_F2_PHOTO = "com.tinydisplay.action.F2_PHOTO";
    public static final String EXTRA_TOUCH_X = "x";
    public static final String EXTRA_TOUCH_Y = "y";
    public static final String EXTRA_TOUCH_START_X = "sx";
    public static final String EXTRA_TOUCH_START_Y = "sy";
    public static final String EXTRA_TOUCH_END_X = "ex";
    public static final String EXTRA_TOUCH_END_Y = "ey";
    public static final String EXTRA_NOTIF_APP = "app";
    public static final String EXTRA_NOTIF_TITLE = "title";
    public static final String EXTRA_NOTIF_TEXT = "text";
    private static final int NOTIFICATION_ID = 1;

    private static final int MSG_RENDER_CLOCK = 1;
    private static final int MSG_RENDER_CALL  = 2;
    private static final int MSG_RENDER_NOTIF = 3;
    private static final int MSG_NOTIF_END    = 4;
    private static final int MSG_RENDER_AOD   = 5;
    private static final int CLOCK_INTERVAL_MS = 30_000;
    private static final int AOD_INTERVAL_MS = 60_000;
    private static final int BOOT_POWER_PULSE_MS = 350;
    private static final int NOTIF_DURATION_MS = 15_000;
    private static final int DOUBLE_TAP_MS = 320;
    private static final int AOD_BACKLIGHT = 18;
    private static final int NOTIF_QUEUE_MAX = 10;

    // Pages.
    private static final int PAGE_CLOCK = 0;
    private static final int PAGE_NOTIFICATIONS = 1;
    private static final int PAGE_CAMERA = 2;
    private static final int PAGE_MAX = PAGE_CAMERA;

    private TinyLcdHal hal;
    private ScreenRenderer renderer;
    private CameraStreamer cameraStreamer;

    private HandlerThread renderThread;
    private Handler renderHandler;

    // State
    private int batteryLevel = 50;
    private boolean isCharging = false;
    private boolean inCall = false;
    private String callNumber;
    private String callName;
    private boolean halReady = false;
    private boolean cameraMode = false;
    private boolean subScreenPowered = false;
    private boolean aodActive = false;
    private boolean pocketCovered = false;
    private int currentPage = PAGE_CLOCK;
    private int lastFrameSize = -1;
    private byte[] lastFrame;

    // Notification queue (index 0 = newest).
    private static final class Notif {
        final String app, title, text;
        Notif(String a, String t, String x) { app = a; title = t; text = x; }
    }
    private final List<Notif> notifQueue = new ArrayList<>();
    private int notifIndex = 0;
    private boolean glanceActive = false;

    // Double-tap bookkeeping.
    private long lastTapTime = 0;
    private Runnable pendingSingleTap;
    private boolean captureNextCameraFrame = false;
    private boolean startCameraFrontOnce = false;

    private SharedPreferences prefs;
    private SensorManager sensorManager;
    private Sensor proximitySensor;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service onCreate");

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        hal = new TinyLcdHal();
        renderer = new ScreenRenderer();

        renderThread = new HandlerThread("tinydisplay_render", android.os.Process.THREAD_PRIORITY_DISPLAY);
        renderThread.start();
        renderHandler = new Handler(renderThread.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_RENDER_CLOCK: renderAndPushClock(); break;
                    case MSG_RENDER_CALL:  renderAndPushCall(); break;
                    case MSG_RENDER_NOTIF: renderNotificationsPage(); break;
                    case MSG_NOTIF_END:    endGlance(); break;
                    case MSG_RENDER_AOD:   renderAndPushAod(); break;
                }
            }
        };

        startForegroundNotification();
        registerReceivers();
        registerPhoneListener();
        setupSensors();

        applyConfiguredState(false);
    }

    // ── HAL power ────────────────────────────────────────────────────

    private boolean ensureHalReady() {
        if (halReady) return true;
        if (!hal.connect()) {
            Log.e(TAG, "HAL connection failed — screen will not update");
            return false;
        }
        halReady = true;
        return true;
    }

    private int getConfiguredBrightness() {
        try {
            return Integer.parseInt(prefs.getString("brightness", "200"));
        } catch (NumberFormatException ignored) {
            return 200;
        }
    }

    private int getClockFace() {
        try {
            return Integer.parseInt(prefs.getString("clock_face", "0"));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void powerOnSubScreen() {
        if (!ensureHalReady()) return;
        int brightness = getConfiguredBrightness();
        hal.setPower(1);
        hal.setBlEnable(1);
        hal.setBacklight(brightness);
        lastFrameSize = -1;
        subScreenPowered = true;
        aodActive = false;
        Log.i(TAG, "Sub-screen powered on, backlight " + brightness);
    }

    private void powerOffSubScreen() {
        if (!ensureHalReady()) return;
        stopCameraModeLocked();
        renderHandler.removeMessages(MSG_RENDER_CLOCK);
        renderHandler.removeMessages(MSG_RENDER_AOD);
        // Soft-off: keep controller/touch powered so rear double-tap can wake it.
        // Full hal.setPower(0)/setBlEnable(0) can also kill the rear digitizer on
        // this device, making wake-by-touch impossible.
        pushFrame(new byte[TinyLcdHal.FRAME_SIZE]);
        hal.setBacklight(0);
        lastFrameSize = -1;
        subScreenPowered = false;
        aodActive = false;
        Log.i(TAG, "Sub-screen soft-off (touch wake kept alive)");
    }

    private boolean isMainScreenInteractive() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm == null || pm.isInteractive();
    }

    private boolean shouldSubScreenBeOn() {
        if (!prefs.getBoolean("sub_screen_enabled", false)) return false;
        if (pocketCovered) return false;
        return !prefs.getBoolean("auto_off_with_screen", true) || isMainScreenInteractive();
    }

    private void applyConfiguredState(boolean fromBoot) {
        if (!prefs.getBoolean("background_service_enabled", true)) {
            renderHandler.removeMessages(MSG_RENDER_CLOCK);
            renderHandler.post(() -> { powerOffSubScreen(); stopSelf(); });
            return;
        }
        renderHandler.post(() -> {
            if (fromBoot) {
                Log.i(TAG, "Boot power pulse");
                powerOnSubScreen();
                try { Thread.sleep(BOOT_POWER_PULSE_MS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (shouldSubScreenBeOn()) {
                powerOnSubScreen();
                currentPage = PAGE_CLOCK;
                if (!inCall) scheduleClockUpdate(0);
            } else {
                renderHandler.removeMessages(MSG_RENDER_CLOCK);
                powerOffSubScreen();
            }
        });
    }

    // ── Rendering ────────────────────────────────────────────────────

    private void renderAndPushClock() {
        if (!subScreenPowered || aodActive || cameraMode || inCall) return;
        if (currentPage != PAGE_CLOCK) return;
        pushFrame(renderer.renderClock(batteryLevel, isCharging, getClockFace()));
        renderHandler.removeMessages(MSG_RENDER_CLOCK);
        renderHandler.sendEmptyMessageDelayed(MSG_RENDER_CLOCK, CLOCK_INTERVAL_MS);
    }

    private void renderAndPushAod() {
        if (!subScreenPowered || !aodActive) return;
        pushFrame(renderer.renderAod());
        renderHandler.removeMessages(MSG_RENDER_AOD);
        renderHandler.sendEmptyMessageDelayed(MSG_RENDER_AOD, AOD_INTERVAL_MS);
    }

    private void renderAndPushCall() {
        pushFrame(renderer.renderIncomingCall(callName, callNumber));
    }

    private void renderNotificationsPage() {
        if (!subScreenPowered) return;
        if (notifQueue.isEmpty()) {
            pushFrame(RawFontRenderer.renderText("No notifications", 3));
            return;
        }
        if (notifIndex < 0) notifIndex = 0;
        if (notifIndex >= notifQueue.size()) notifIndex = notifQueue.size() - 1;
        Notif n = notifQueue.get(notifIndex);
        pushFrame(renderer.renderNotification(n.app, n.title, n.text, notifIndex, notifQueue.size()));
    }

    // ── Page navigation ──────────────────────────────────────────────

    private void setPage(int page) {
        page = Math.max(PAGE_CLOCK, Math.min(PAGE_MAX, page));
        if (page == currentPage && page != PAGE_NOTIFICATIONS) {
            Log.i(TAG, "Page unchanged: " + pageName(page));
            return;
        }
        int old = currentPage;
        Log.i(TAG, "Page " + pageName(old) + " -> " + pageName(page));
        if (old == PAGE_CAMERA && page != PAGE_CAMERA) stopCameraModeLocked();
        currentPage = page;
        glanceActive = false;
        renderHandler.removeMessages(MSG_NOTIF_END);
        renderHandler.removeMessages(MSG_RENDER_CLOCK);
        switch (page) {
            case PAGE_CAMERA: startCameraModeLocked(); break;
            case PAGE_NOTIFICATIONS: notifIndex = 0; renderNotificationsPage(); break;
            default: scheduleClockUpdate(0); break;
        }
    }

    private String pageName(int page) {
        switch (page) {
            case PAGE_NOTIFICATIONS: return "notifications";
            case PAGE_CAMERA: return "camera";
            case PAGE_CLOCK:
            default: return "clock";
        }
    }

    private void nextPage() { setPage(currentPage >= PAGE_MAX ? PAGE_CLOCK : currentPage + 1); }
    private void prevPage() { setPage(currentPage <= PAGE_CLOCK ? PAGE_MAX : currentPage - 1); }

    // ── Gesture handling ─────────────────────────────────────────────

    private void handleRearTap(int x, int y) {
        renderHandler.post(() -> {
            long now = SystemClock.uptimeMillis();
            if (now - lastTapTime < DOUBLE_TAP_MS) {
                lastTapTime = 0;
                if (pendingSingleTap != null) {
                    renderHandler.removeCallbacks(pendingSingleTap);
                    pendingSingleTap = null;
                }
                onDoubleTap();
            } else {
                lastTapTime = now;
                pendingSingleTap = () -> { pendingSingleTap = null; onSingleTap(); };
                renderHandler.postDelayed(pendingSingleTap, DOUBLE_TAP_MS);
            }
        });
    }

    private void onSingleTap() {
        if (!subScreenPowered) {
            pocketCovered = false;
            powerOnSubScreen();
            setPage(PAGE_CLOCK);
            return;
        }
        if (aodActive) { exitAod(); return; }
        if (inCall) return;
        switch (currentPage) {
            case PAGE_CAMERA:
                setPage(PAGE_CLOCK);
                break;
            case PAGE_NOTIFICATIONS:
                if (!notifQueue.isEmpty()) {
                    notifIndex = (notifIndex + 1) % notifQueue.size();
                    renderNotificationsPage();
                }
                break;
            default:
                renderAndPushClock();
                break;
        }
    }

    private void onDoubleTap() {
        runAction("toggle_power");
    }

    private void handleRearLongPress() {
        renderHandler.post(() -> {
            if (cameraMode) { if (cameraStreamer != null) cameraStreamer.toggleFreeze(); return; }
            runAction(prefs.getString("gesture_long_press", "aod"));
        });
    }

    private void handleRearSwipe(int sx, int sy, int ex, int ey) {
        renderHandler.post(() -> {
            int dx = ex - sx, dy = ey - sy;
            int adx = Math.abs(dx), ady = Math.abs(dy);
            // On the 340px round panel human swipes are often diagonal. Prefer
            // left/right page navigation unless the drag is clearly vertical.
            boolean horizontal = adx >= 40 && adx * 100 >= ady * 55;
            boolean vertical = !horizontal && ady >= 40;
            Log.i(TAG, "Rear swipe dx=" + dx + " dy=" + dy + " horizontal=" + horizontal
                    + " page=" + pageName(currentPage));
            if (!subScreenPowered) { pocketCovered = false; powerOnSubScreen(); setPage(PAGE_CLOCK); return; }
            if (aodActive) { exitAod(); return; }
            if (horizontal) {
                if (currentPage == PAGE_CLOCK) {
                    if (dx < 0) setPage(PAGE_NOTIFICATIONS); // right-to-left
                    else setPage(PAGE_CAMERA);               // left-to-right
                } else if (currentPage == PAGE_NOTIFICATIONS) {
                    if (dx > 0) setPage(PAGE_CLOCK);         // left-to-right back
                } else if (currentPage == PAGE_CAMERA) {
                    if (dx < 0) setPage(PAGE_CLOCK);         // right-to-left back
                }
            } else if (vertical) {
                if (currentPage == PAGE_NOTIFICATIONS) {
                    scrollNotifications(dy < 0 ? 1 : -1);
                } else if (currentPage == PAGE_CAMERA) {
                    setPage(PAGE_CLOCK);
                } else {
                    Log.i(TAG, "Vertical swipe ignored on clock hub");
                }
            } else {
                Log.i(TAG, "Rear swipe ignored: too short/ambiguous");
            }
        });
    }

    /** Resolve a configurable gesture action string. */
    private void runAction(String action) {
        if (action == null) return;
        switch (action) {
            case "toggle_power":
                if (subScreenPowered) powerOffSubScreen();
                else { powerOnSubScreen(); setPage(PAGE_CLOCK); }
                break;
            case "aod":
                if (aodActive) exitAod(); else enterAod();
                break;
            case "camera": setPage(PAGE_CAMERA); break;
            case "clock": setPage(PAGE_CLOCK); break;
            case "notifications": setPage(PAGE_NOTIFICATIONS); break;
            case "next_page": nextPage(); break;
            case "prev_page": prevPage(); break;
            case "dismiss": dismissCurrentNotification(); break;
            case "none": default: break;
        }
    }

    private void dismissCurrentNotification() {
        if (currentPage != PAGE_NOTIFICATIONS || notifQueue.isEmpty()) return;
        if (notifIndex >= 0 && notifIndex < notifQueue.size()) notifQueue.remove(notifIndex);
        if (notifQueue.isEmpty()) setPage(PAGE_CLOCK);
        else { if (notifIndex >= notifQueue.size()) notifIndex = notifQueue.size() - 1; renderNotificationsPage(); }
    }

    private void scrollNotifications(int delta) {
        if (currentPage != PAGE_NOTIFICATIONS || notifQueue.isEmpty()) {
            renderNotificationsPage();
            return;
        }
        notifIndex += delta;
        if (notifIndex < 0) notifIndex = notifQueue.size() - 1;
        if (notifIndex >= notifQueue.size()) notifIndex = 0;
        Log.i(TAG, "Notification index " + (notifIndex + 1) + "/" + notifQueue.size());
        renderNotificationsPage();
    }

    // ── AOD ──────────────────────────────────────────────────────────

    private void enterAod() {
        if (!ensureHalReady()) return;
        if (!subScreenPowered) { hal.setPower(1); hal.setBlEnable(1); subScreenPowered = true; }
        stopCameraModeLocked();
        renderHandler.removeMessages(MSG_RENDER_CLOCK);
        hal.setBacklight(AOD_BACKLIGHT);
        lastFrameSize = -1;
        aodActive = true;
        renderAndPushAod();
        Log.i(TAG, "AOD on");
    }

    private void exitAod() {
        if (!aodActive) return;
        aodActive = false;
        renderHandler.removeMessages(MSG_RENDER_AOD);
        hal.setBacklight(getConfiguredBrightness());
        currentPage = PAGE_CLOCK;
        scheduleClockUpdate(0);
        Log.i(TAG, "AOD off");
    }

    // ── Frame push ───────────────────────────────────────────────────

    private void pushFrame(byte[] frame) {
        if (!halReady || frame == null) return;
        lastFrame = frame;
        for (int i = 0; i < 5; i++) {
            int status = hal.getStatus();
            if (status < 2) break;
            try { Thread.sleep(2); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        if (lastFrameSize != frame.length) {
            hal.setFrameSize(TinyLcdHal.BUFFER_WIDTH, TinyLcdHal.BUFFER_HEIGHT);
            lastFrameSize = frame.length;
        }
        int result = hal.writeFrame(frame);
        if (result < 0) Log.w(TAG, "writeFrame returned " + result);
    }

    private void scheduleClockUpdate(long delayMs) {
        renderHandler.removeMessages(MSG_RENDER_CLOCK);
        renderHandler.sendEmptyMessageDelayed(MSG_RENDER_CLOCK, delayMs);
    }

    // ── Camera ───────────────────────────────────────────────────────

    private void startCameraModeLocked() {
        if (inCall) return;
        if (!subScreenPowered) powerOnSubScreen();
        glanceActive = false;
        renderHandler.removeMessages(MSG_RENDER_CLOCK);
        renderHandler.removeMessages(MSG_NOTIF_END);
        if (cameraMode) return;

        // Android 14+ forbids adding FOREGROUND_SERVICE_TYPE_CAMERA while the
        // app is only being driven by a background service/rear-touch command.
        // Never let that policy kill the whole renderer: fail back to clock and
        // tell the user to open the app/grant camera permission first.
        cameraMode = true;
        if (!updateForegroundType(true)) {
            cameraMode = false;
            currentPage = PAGE_CLOCK;
            pushFrame(RawFontRenderer.renderText("OPEN APP", 4));
            scheduleClockUpdate(3_000);
            return;
        }

        pushFrame(RawFontRenderer.renderText("CAMERA", 6));
        cameraStreamer = new CameraStreamer(this, new CameraStreamer.FrameSink() {
            @Override public void onFrame(byte[] frame) {
                renderHandler.post(() -> {
                    if (captureNextCameraFrame) {
                        captureNextCameraFrame = false;
                        saveCameraFrame(frame);
                    }
                    if (cameraMode) pushFrame(frame);
                });
            }
            @Override public void onError(String message, Throwable t) {
                Log.w(TAG, message, t);
                renderHandler.post(() -> { if (cameraMode) pushFrame(RawFontRenderer.renderText("CAMERA ERR", 4)); });
            }
        });
        try {
            if (startCameraFrontOnce) {
                startCameraFrontOnce = false;
                cameraStreamer.useFrontCamera();
            }
            cameraStreamer.start();
            Log.i(TAG, "Camera mode started");
        } catch (SecurityException e) {
            Log.w(TAG, "Camera start blocked by Android foreground-camera policy", e);
            if (cameraStreamer != null) { cameraStreamer.stop(); cameraStreamer = null; }
            cameraMode = false;
            updateForegroundType(false);
            currentPage = PAGE_CLOCK;
            pushFrame(RawFontRenderer.renderText("CAMERA BLOCK", 3));
            scheduleClockUpdate(3_000);
        }
    }

    private void stopCameraModeLocked() {
        if (!cameraMode && cameraStreamer == null) return;
        cameraMode = false;
        captureNextCameraFrame = false;
        updateForegroundType(false);
        if (cameraStreamer != null) { cameraStreamer.stop(); cameraStreamer = null; }
        Log.i(TAG, "Camera mode stopped");
    }

    private void takeF2Selfie() {
        Log.i(TAG, "F2 photo requested");
        pocketCovered = false;
        if (!subScreenPowered) powerOnSubScreen();
        if (currentPage != PAGE_CAMERA) {
            startCameraFrontOnce = true;
            setPage(PAGE_CAMERA);
        } else if (cameraStreamer != null) {
            cameraStreamer.useFrontCamera();
        }
        captureNextCameraFrame = true;
        pushFrame(RawFontRenderer.renderText("PHOTO", 5));
    }

    private void saveCameraFrame(byte[] frame) {
        if (frame == null) return;
        try {
            Bitmap bmp = Bitmap.createBitmap(RawFontRenderer.W, RawFontRenderer.H, Bitmap.Config.RGB_565);
            int[] pixels = new int[RawFontRenderer.W * RawFontRenderer.H];
            for (int y = 0; y < RawFontRenderer.H; y++) {
                for (int x = 0; x < RawFontRenderer.W; x++) {
                    int i = ((y * RawFontRenderer.W) + x) * 2;
                    int rgb565 = ((frame[i] & 0xff) << 8) | (frame[i + 1] & 0xff);
                    int r = ((rgb565 >> 11) & 0x1f) << 3;
                    int g = ((rgb565 >> 5) & 0x3f) << 2;
                    int b = (rgb565 & 0x1f) << 3;
                    pixels[y * RawFontRenderer.W + x] = 0xff000000 | (r << 16) | (g << 8) | b;
                }
            }
            bmp.setPixels(pixels, 0, RawFontRenderer.W, 0, 0, RawFontRenderer.W, RawFontRenderer.H);
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, "TinyDisplay_" + System.currentTimeMillis() + ".png");
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TinyDisplay");
            }
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IllegalStateException("MediaStore insert returned null");
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null || !bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw new IllegalStateException("PNG write failed");
                }
            }
            Log.i(TAG, "F2 photo saved: " + uri);
            pushFrame(RawFontRenderer.renderText("SAVED", 5));
        } catch (Throwable t) {
            Log.w(TAG, "F2 photo save failed", t);
            pushFrame(RawFontRenderer.renderText("SAVE ERR", 4));
        }
    }

    // ── External notifications + glance ──────────────────────────────

    public void onExternalNotification(String appName, String title, String text) {
        if (!prefs.getBoolean("sub_screen_enabled", false)) return;
        if (!prefs.getBoolean("show_notifications", true)) return;
        if (inCall) return;
        renderHandler.post(() -> {
            notifQueue.add(0, new Notif(appName, title, text));
            while (notifQueue.size() > NOTIF_QUEUE_MAX) notifQueue.remove(notifQueue.size() - 1);
            notifIndex = 0;
            if (cameraMode) return; // don't interrupt camera; it stays in the queue
            if (!subScreenPowered) {
                if (!prefs.getBoolean("wake_on_notification", true)) return;
                pocketCovered = false;
                powerOnSubScreen();
            }
            currentPage = PAGE_NOTIFICATIONS;
            glanceActive = true;
            renderNotificationsPage();
            renderHandler.removeMessages(MSG_NOTIF_END);
            renderHandler.sendEmptyMessageDelayed(MSG_NOTIF_END, NOTIF_DURATION_MS);
        });
    }

    private void endGlance() {
        if (!glanceActive) return;
        glanceActive = false;
        if (inCall) { renderHandler.sendEmptyMessage(MSG_RENDER_CALL); return; }
        if (!shouldSubScreenBeOn()) { powerOffSubScreen(); return; }
        setPage(PAGE_CLOCK);
    }

    // ── Receivers ────────────────────────────────────────────────────

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            batteryLevel = (level * 100) / scale;
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
            if (!inCall && !cameraMode && !aodActive && currentPage == PAGE_CLOCK && shouldSubScreenBeOn())
                scheduleClockUpdate(0);
        }
    };

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (aodActive) { renderHandler.sendEmptyMessage(MSG_RENDER_AOD); return; }
            if (!inCall && !cameraMode && currentPage == PAGE_CLOCK) scheduleClockUpdate(0);
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!prefs.getBoolean("auto_off_with_screen", true)) return;
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                renderHandler.post(() -> {
                    if (!halReady) return;
                    if (prefs.getBoolean("aod_enabled", false) && prefs.getBoolean("sub_screen_enabled", false)
                            && !pocketCovered) enterAod();
                    else powerOffSubScreen();
                });
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                renderHandler.post(() -> {
                    if (aodActive) { exitAod(); return; }
                    if (halReady && prefs.getBoolean("sub_screen_enabled", false) && !pocketCovered) {
                        powerOnSubScreen();
                        currentPage = PAGE_CLOCK;
                        if (!inCall) scheduleClockUpdate(0);
                    }
                });
            }
        }
    };

    private final BroadcastReceiver shutdownReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            renderHandler.post(() -> powerOffSubScreen());
        }
    };

    private final BroadcastReceiver configReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "Config changed");
            setupSensors();
            applyConfiguredState(false);
        }
    };

    private void registerReceivers() {
        ContextCompat.registerReceiver(this, batteryReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED);
        IntentFilter timeFilter = new IntentFilter();
        timeFilter.addAction(Intent.ACTION_TIME_TICK);
        timeFilter.addAction(Intent.ACTION_TIME_CHANGED);
        timeFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        ContextCompat.registerReceiver(this, timeReceiver, timeFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
        IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        ContextCompat.registerReceiver(this, screenReceiver, screenFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
        ContextCompat.registerReceiver(this, shutdownReceiver,
                new IntentFilter(Intent.ACTION_SHUTDOWN), ContextCompat.RECEIVER_NOT_EXPORTED);
        ContextCompat.registerReceiver(this, configReceiver,
                new IntentFilter("com.tinydisplay.CONFIG_CHANGED"), ContextCompat.RECEIVER_EXPORTED);
    }

    // ── Pocket / proximity ───────────────────────────────────────────

    private final SensorEventListener proximityListener = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent event) {
            boolean near = event.values.length > 0 && event.values[0] < (proximitySensor.getMaximumRange() * 0.5f);
            renderHandler.post(() -> {
                if (near && !pocketCovered) {
                    pocketCovered = true;
                    if (subScreenPowered) powerOffSubScreen();
                } else if (!near && pocketCovered) {
                    pocketCovered = false;
                    if (prefs.getBoolean("sub_screen_enabled", false) && isMainScreenInteractive()) {
                        powerOnSubScreen();
                        currentPage = PAGE_CLOCK;
                        if (!inCall) scheduleClockUpdate(0);
                    }
                }
            });
        }
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    private boolean sensorsRegistered = false;

    private void setupSensors() {
        if (sensorManager == null) sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager == null) return;
        if (proximitySensor == null) proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        boolean want = prefs.getBoolean("pocket_mode", false) && proximitySensor != null;
        if (want && !sensorsRegistered) {
            sensorManager.registerListener(proximityListener, proximitySensor,
                    SensorManager.SENSOR_DELAY_NORMAL, renderHandler);
            sensorsRegistered = true;
        } else if (!want && sensorsRegistered) {
            sensorManager.unregisterListener(proximityListener);
            sensorsRegistered = false;
            pocketCovered = false;
        }
    }

    // ── Phone state ──────────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private void registerPhoneListener() {
        TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        if (tm == null) return;
        try {
            tm.listen(new PhoneStateListener() {
                @Override public void onCallStateChanged(int state, String incomingNumber) {
                    switch (state) {
                        case TelephonyManager.CALL_STATE_RINGING:
                            if (!prefs.getBoolean("show_incoming_calls", true)) break;
                            inCall = true;
                            renderHandler.post(() -> { stopCameraModeLocked(); exitAod(); });
                            callNumber = incomingNumber;
                            callName = lookupContact(incomingNumber);
                            renderHandler.removeMessages(MSG_RENDER_CLOCK);
                            renderHandler.sendEmptyMessage(MSG_RENDER_CALL);
                            break;
                        case TelephonyManager.CALL_STATE_OFFHOOK:
                            break;
                        case TelephonyManager.CALL_STATE_IDLE:
                            inCall = false;
                            callNumber = null;
                            callName = null;
                            currentPage = PAGE_CLOCK;
                            if (!cameraMode) scheduleClockUpdate(0);
                            break;
                    }
                }
            }, PhoneStateListener.LISTEN_CALL_STATE);
        } catch (SecurityException e) {
            Log.w(TAG, "No READ_PHONE_STATE permission — incoming-call detection disabled");
        }
    }

    private String lookupContact(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isEmpty()) return null;
        try {
            Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber));
            ContentResolver cr = getContentResolver();
            try (Cursor cursor = cr.query(uri,
                    new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
            }
        } catch (Exception e) {
            Log.w(TAG, "Contact lookup failed", e);
        }
        return null;
    }

    // ── Foreground notification ──────────────────────────────────────

    static TinyDisplayService instance;

    private void startForegroundNotification() {
        instance = this;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "TinyDisplay Service", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps the sub-screen controller running");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
        updateForegroundType(false);
    }

    private Notification buildForegroundNotification() {
        Intent notifIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, notifIntent, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("TinyDisplay")
                .setContentText(cameraMode ? "Camera mode active" : "Sub-screen active")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private boolean updateForegroundType(boolean camera) {
        Notification notification = buildForegroundNotification();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
                if (camera) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
                startForeground(NOTIFICATION_ID, notification, type);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Foreground service update denied (camera=" + camera + ")", e);
            return false;
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_REAR_TAP.equals(action)) {
            handleRearTap(intent.getIntExtra(EXTRA_TOUCH_X, -1), intent.getIntExtra(EXTRA_TOUCH_Y, -1));
            return START_STICKY;
        }
        if (ACTION_REAR_LONGPRESS.equals(action)) {
            handleRearLongPress();
            return START_STICKY;
        }
        if (ACTION_REAR_SWIPE.equals(action)) {
            handleRearSwipe(intent.getIntExtra(EXTRA_TOUCH_START_X, -1),
                    intent.getIntExtra(EXTRA_TOUCH_START_Y, -1),
                    intent.getIntExtra(EXTRA_TOUCH_END_X, -1),
                    intent.getIntExtra(EXTRA_TOUCH_END_Y, -1));
            return START_STICKY;
        }
        if (ACTION_SHOW_NOTIFICATION.equals(action)) {
            onExternalNotification(intent.getStringExtra(EXTRA_NOTIF_APP),
                    intent.getStringExtra(EXTRA_NOTIF_TITLE), intent.getStringExtra(EXTRA_NOTIF_TEXT));
            return START_STICKY;
        }
        if (ACTION_F2_PHOTO.equals(action)) {
            if (prefs.getBoolean("f2_photo_enabled", true)) renderHandler.post(this::takeF2Selfie);
            else Log.i(TAG, "F2 photo ignored: disabled in settings");
            return START_STICKY;
        }
        if (ACTION_PREVIEW.equals(action)) {
            renderHandler.post(() -> {
                pocketCovered = false;
                powerOnSubScreen();
                setPage(PAGE_CLOCK);
            });
            return START_STICKY;
        }
        boolean fromBoot = intent != null && intent.getBooleanExtra(EXTRA_BOOT_COMPLETED, false);
        applyConfiguredState(fromBoot);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        try { unregisterReceiver(batteryReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(timeReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(shutdownReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(configReceiver); } catch (Exception ignored) {}
        if (sensorManager != null && sensorsRegistered) {
            try { sensorManager.unregisterListener(proximityListener); } catch (Exception ignored) {}
        }
        renderHandler.post(() -> {
            stopCameraModeLocked();
            if (halReady) hal.disconnect();
        });
        renderThread.quitSafely();
        Log.i(TAG, "Service destroyed");
    }
}
