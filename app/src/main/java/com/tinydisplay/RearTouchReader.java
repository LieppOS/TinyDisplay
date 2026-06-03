package com.tinydisplay;

import android.util.Log;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Reads the rear 340x340 touch panel directly from /dev/input/event5 (hyn_ts).
 *
 * Android currently maps this device as a normal internal touchscreen, which is
 * why touches also affect the main display. This reader lets TinyDisplay react to
 * the hardware independently. It is intentionally optional: if SELinux blocks
 * the open(), TinyDisplay continues working without touch gestures.
 */
public final class RearTouchReader implements Runnable {
    private static final String TAG = "RearTouchReader";
    private static final String DEVICE = "/dev/input/event5"; // hyn_ts, 340x340

    private static final int EV_SYN = 0x00;
    private static final int EV_ABS = 0x03;
    private static final int ABS_MT_POSITION_X = 0x35;
    private static final int ABS_MT_POSITION_Y = 0x36;
    private static final int ABS_MT_TRACKING_ID = 0x39;

    public interface Listener {
        void onRearTap(int x, int y);
        void onRearSwipe(int startX, int startY, int endX, int endY);
    }

    private final Listener listener;
    private volatile boolean running;
    private Thread thread;

    public RearTouchReader(Listener listener) {
        this.listener = listener;
    }

    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(this, "RearTouchReader");
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    @Override
    public void run() {
        // Linux input_event on 64-bit Android: timeval sec(8), usec(8), type/u16, code/u16, value/s32 = 24 bytes.
        byte[] raw = new byte[24];
        int x = -1, y = -1;
        int downX = -1, downY = -1;
        boolean touching = false;

        Log.i(TAG, "Starting rear touch reader for " + DEVICE);
        try (FileInputStream in = new FileInputStream(DEVICE)) {
            Log.i(TAG, "Opened " + DEVICE + " (rear touch enabled)");
            while (running) {
                int off = 0;
                while (off < raw.length) {
                    int n = in.read(raw, off, raw.length - off);
                    if (n < 0) return;
                    off += n;
                }

                ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
                b.position(16); // skip timeval
                int type = b.getShort() & 0xffff;
                int code = b.getShort() & 0xffff;
                int value = b.getInt();

                if (type == EV_ABS) {
                    if (code == ABS_MT_POSITION_X) x = value;
                    else if (code == ABS_MT_POSITION_Y) y = value;
                    else if (code == ABS_MT_TRACKING_ID) {
                        if (value >= 0) {
                            touching = true;
                            downX = x;
                            downY = y;
                        } else if (touching) {
                            touching = false;
                            handleUp(downX, downY, x, y);
                            downX = downY = -1;
                        }
                    }
                } else if (type == EV_SYN && touching && downX < 0 && x >= 0 && y >= 0) {
                    downX = x;
                    downY = y;
                }
            }
        } catch (IOException | SecurityException e) {
            Log.w(TAG, "Rear touch unavailable: " + e.getMessage());
        }
    }

    private void handleUp(int sx, int sy, int ex, int ey) {
        if (sx < 0 || sy < 0 || ex < 0 || ey < 0) return;
        int dx = ex - sx;
        int dy = ey - sy;
        int dist2 = dx * dx + dy * dy;
        if (dist2 < 30 * 30) {
            Log.i(TAG, "rear tap x=" + ex + " y=" + ey);
            listener.onRearTap(ex, ey);
        } else {
            Log.i(TAG, "rear swipe " + sx + "," + sy + " -> " + ex + "," + ey);
            listener.onRearSwipe(sx, sy, ex, ey);
        }
    }
}
