package com.tinydisplay;

/**
 * Production renderer for the sub-screen. Delegates to {@link RawFontRenderer},
 * which writes RGB565 big-endian bytes directly at the panel's true geometry
 * (340-wide rows, 180° rotated). The Android Canvas path is intentionally NOT
 * used here — see FINDINGS.md for why.
 */
public class ScreenRenderer {

    public static final int FACE_DIGITAL = 0;
    public static final int FACE_ANALOG = 1;
    public static final int FACE_RING = 2;

    /** Clock screen: time, date, battery level and arc. */
    public byte[] renderClock(int batteryLevel, boolean isCharging) {
        return RawFontRenderer.renderClock(batteryLevel, isCharging);
    }

    /** Clock screen using the selected face style. */
    public byte[] renderClock(int batteryLevel, boolean isCharging, int face) {
        switch (face) {
            case FACE_ANALOG: return RawFontRenderer.renderClockAnalog(batteryLevel, isCharging);
            case FACE_RING:   return RawFontRenderer.renderClockRing(batteryLevel, isCharging);
            default:          return RawFontRenderer.renderClock(batteryLevel, isCharging);
        }
    }

    /** Minimal dim always-on face. */
    public byte[] renderAod() {
        return RawFontRenderer.renderClockAod();
    }

    /** Notification card with a queue position badge. */
    public byte[] renderNotification(String appName, String title, String text, int index, int total) {
        return RawFontRenderer.renderNotification(appName, title, text, index, total);
    }

    /** Incoming-call screen: caller name and number. */
    public byte[] renderIncomingCall(String callerName, String phoneNumber) {
        return RawFontRenderer.renderIncomingCall(callerName, phoneNumber);
    }

    /** Full-screen notification: app name, title and body text. */
    public byte[] renderNotification(String appName, String title, String text) {
        return RawFontRenderer.renderNotification(appName, title, text);
    }
}
