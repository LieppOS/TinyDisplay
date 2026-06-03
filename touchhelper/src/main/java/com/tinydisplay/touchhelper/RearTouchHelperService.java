package com.tinydisplay.touchhelper;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class RearTouchHelperService extends Service implements Runnable {
    private static final String TAG = "TinyTouchHelper";
    private static final String DEFAULT_DEVICE = "/dev/input/event5"; // hyn_ts rear 340x340 panel on Armor 29 Pro

    private static final int EV_SYN = 0x00;
    private static final int EV_ABS = 0x03;
    private static final int ABS_MT_POSITION_X = 0x35;
    private static final int ABS_MT_POSITION_Y = 0x36;
    private static final int ABS_MT_TRACKING_ID = 0x39;

    private volatile boolean running;
    private Thread thread;

    @Override
    public void onCreate() {
        super.onCreate();
        startReader();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startReader();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (thread != null) thread.interrupt();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private synchronized void startReader() {
        if (running && thread != null && thread.isAlive()) return;
        running = true;
        thread = new Thread(this, "TinyRearTouch");
        thread.start();
        Log.i(TAG, "Reader thread requested");
    }

    @Override
    public void run() {
        String device = findRearTouchDevice();
        Log.i(TAG, "Starting helper, opening " + device);
        byte[] raw = new byte[24];
        int x = -1, y = -1, downX = -1, downY = -1;
        boolean touching = false;
        try (FileInputStream in = new FileInputStream(device)) {
            boolean grabbed = TouchGrabber.grab(in.getFD(), true);
            Log.i(TAG, "Opened rear touch device " + device + "; EVIOCGRAB=" + grabbed);
            while (running) {
                int off = 0;
                while (off < raw.length) {
                    int n = in.read(raw, off, raw.length - off);
                    if (n < 0) return;
                    off += n;
                }
                ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
                b.position(16);
                int type = b.getShort() & 0xffff;
                int code = b.getShort() & 0xffff;
                int value = b.getInt();

                if (type == EV_ABS) {
                    if (code == ABS_MT_POSITION_X) x = value;
                    else if (code == ABS_MT_POSITION_Y) y = value;
                    else if (code == ABS_MT_TRACKING_ID) {
                        if (value >= 0) {
                            touching = true;
                            downX = x; downY = y;
                        } else if (touching) {
                            touching = false;
                            sendGesture(downX, downY, x, y);
                            downX = downY = -1;
                        }
                    }
                } else if (type == EV_SYN && touching && downX < 0 && x >= 0 && y >= 0) {
                    downX = x; downY = y;
                }
            }
        } catch (IOException | SecurityException e) {
            Log.w(TAG, "Cannot read rear touch: " + e.getMessage(), e);
        } finally {
            running = false;
            Log.i(TAG, "Reader thread stopped");
        }
    }

    private String findRearTouchDevice() {
        String byName = findRearTouchDeviceByIoctl();
        if (byName != null) return byName;

        try (BufferedReader br = new BufferedReader(new FileReader("/proc/bus/input/devices"))) {
            String line;
            boolean isHyn = false;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("N:") && line.contains("hyn_ts")) {
                    isHyn = true;
                } else if (isHyn && line.startsWith("H:")) {
                    for (String part : line.split("\\s+")) {
                        if (part.startsWith("event")) return "/dev/input/" + part;
                    }
                } else if (line.trim().isEmpty()) {
                    isHyn = false;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not discover rear touch device, using default", e);
        }
        Log.w(TAG, "Falling back to default rear touch device " + DEFAULT_DEVICE);
        return DEFAULT_DEVICE;
    }

    private String findRearTouchDeviceByIoctl() {
        File dir = new File("/dev/input");
        File[] files = dir.listFiles((d, name) -> name.startsWith("event"));
        if (files == null || files.length == 0) {
            Log.w(TAG, "No /dev/input/event* entries visible");
            return null;
        }
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        for (File f : files) {
            try (FileInputStream in = new FileInputStream(f)) {
                String name = TouchGrabber.getName(in.getFD());
                Log.i(TAG, "input candidate " + f.getAbsolutePath() + " name=" + name);
                if (name != null && name.contains("hyn_ts")) {
                    Log.i(TAG, "Selected rear touch device " + f.getAbsolutePath());
                    return f.getAbsolutePath();
                }
            } catch (IOException | SecurityException e) {
                Log.w(TAG, "Cannot probe " + f.getAbsolutePath() + ": " + e.getMessage());
            }
        }
        return null;
    }

    private void sendGesture(int sx, int sy, int ex, int ey) {
        if (sx < 0 || sy < 0 || ex < 0 || ey < 0) return;
        int dx = ex - sx, dy = ey - sy;
        int dist2 = dx * dx + dy * dy;
        Intent i = new Intent();
        i.setComponent(new ComponentName("com.tinydisplay", "com.tinydisplay.TinyDisplayService"));
        if (dist2 < 30 * 30) {
            Log.i(TAG, "tap " + ex + "," + ey);
            i.setAction("com.tinydisplay.action.REAR_TAP");
            i.putExtra("x", ex);
            i.putExtra("y", ey);
        } else {
            Log.i(TAG, "swipe " + sx + "," + sy + " -> " + ex + "," + ey);
            i.setAction("com.tinydisplay.action.REAR_SWIPE");
            i.putExtra("sx", sx); i.putExtra("sy", sy);
            i.putExtra("ex", ex); i.putExtra("ey", ey);
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to deliver gesture", e);
        }
    }
}
