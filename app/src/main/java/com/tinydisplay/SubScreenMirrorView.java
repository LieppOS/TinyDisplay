package com.tinydisplay;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import com.tinydisplay.hal.TinyLcdHal;

/**
 * Live mirror of the rear sub-screen. Renders the exact frames being pushed to
 * the panel and forwards touches on this view back to TinyDisplayService as the
 * same REAR_TAP / REAR_SWIPE / REAR_LONGPRESS intents the native daemon sends —
 * so the settings preview behaves like the physical rear panel.
 */
public class SubScreenMirrorView extends View {

    private static final int W = TinyLcdHal.PANEL_WIDTH;   // 340
    private static final int H = TinyLcdHal.PANEL_HEIGHT;  // 340

    private final Bitmap bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    private final int[] pixels = new int[W * H];
    private final Object lock = new Object();

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect src = new Rect(0, 0, W, H);
    private final RectF dst = new RectF();
    private final Path clip = new Path();

    private final GestureDetector detector;
    private float downX, downY;
    private long downTime;
    private int swipePx = 24;

    public SubScreenMirrorView(Context c, AttributeSet a) {
        super(c, a);
        bgPaint.setColor(Color.BLACK);
        swipePx = (int) (24 * getResources().getDisplayMetrics().density);
        detector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent e) {
                int[] p = toPanel(e.getX(), e.getY());
                sendTap(p[0], p[1]);
                return true;
            }
            @Override public void onLongPress(MotionEvent e) {
                int[] p = toPanel(e.getX(), e.getY());
                sendLongPress(p[0], p[1]);
            }
        });
    }

    /** Push a new panel frame (may be called off the UI thread). */
    public void update(byte[] frame) {
        if (frame == null) return;
        synchronized (lock) {
            RawFontRenderer.decodeToArgb(frame, pixels);
            bitmap.setPixels(pixels, 0, W, 0, 0, W, H);
        }
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        int s = Math.min(w, h);
        float left = (w - s) / 2f, top = (h - s) / 2f;
        dst.set(left, top, left + s, top + s);
        clip.reset();
        clip.addCircle(left + s / 2f, top + s / 2f, s / 2f, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        canvas.drawCircle(left + s / 2f, top + s / 2f, s / 2f, bgPaint);
        synchronized (lock) {
            canvas.drawBitmap(bitmap, src, dst, paint);
        }
        canvas.restore();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        detector.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                downTime = System.currentTimeMillis();
                break;
            case MotionEvent.ACTION_UP:
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (Math.hypot(dx, dy) >= swipePx) {
                    int[] a = toPanel(downX, downY);
                    int[] b = toPanel(e.getX(), e.getY());
                    sendSwipe(a[0], a[1], b[0], b[1]);
                }
                break;
        }
        return true;
    }

    /** Map a view coordinate to rear-panel coordinates (panel is 180° rotated). */
    private int[] toPanel(float vx, float vy) {
        int s = Math.min(getWidth(), getHeight());
        float left = (getWidth() - s) / 2f, top = (getHeight() - s) / 2f;
        int lx = clamp((int) ((vx - left) / s * W), W);
        int ly = clamp((int) ((vy - top) / s * H), H);
        return new int[]{W - 1 - lx, H - 1 - ly};
    }

    private int clamp(int v, int max) { return v < 0 ? 0 : (v >= max ? max - 1 : v); }

    private void send(String action, Intent extras) {
        Intent i = extras.setClass(getContext(), TinyDisplayService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getContext().startForegroundService(i);
        else getContext().startService(i);
    }

    private void sendTap(int x, int y) {
        send(TinyDisplayService.ACTION_REAR_TAP,
                new Intent().putExtra(TinyDisplayService.EXTRA_TOUCH_X, x)
                        .putExtra(TinyDisplayService.EXTRA_TOUCH_Y, y));
    }

    private void sendLongPress(int x, int y) {
        send(TinyDisplayService.ACTION_REAR_LONGPRESS,
                new Intent().putExtra(TinyDisplayService.EXTRA_TOUCH_X, x)
                        .putExtra(TinyDisplayService.EXTRA_TOUCH_Y, y));
    }

    private void sendSwipe(int sx, int sy, int ex, int ey) {
        send(TinyDisplayService.ACTION_REAR_SWIPE,
                new Intent().putExtra(TinyDisplayService.EXTRA_TOUCH_START_X, sx)
                        .putExtra(TinyDisplayService.EXTRA_TOUCH_START_Y, sy)
                        .putExtra(TinyDisplayService.EXTRA_TOUCH_END_X, ex)
                        .putExtra(TinyDisplayService.EXTRA_TOUCH_END_Y, ey));
    }
}
