package com.tinydisplay.touchhelper;

import android.util.Log;

import java.io.FileDescriptor;

final class TouchGrabber {
    private static final String TAG = "TinyTouchHelper";

    static {
        try {
            System.loadLibrary("touchgrabber");
        } catch (Throwable t) {
            Log.w(TAG, "touchgrabber native library unavailable", t);
        }
    }

    private TouchGrabber() {}

    static boolean grab(FileDescriptor fd, boolean enable) {
        try {
            int rc = nativeGrab(fd, enable);
            if (rc != 0) Log.w(TAG, "EVIOCGRAB ioctl rc=" + rc);
            return rc == 0;
        } catch (Throwable t) {
            Log.w(TAG, "EVIOCGRAB failed", t);
            return false;
        }
    }

    static String getName(FileDescriptor fd) {
        try {
            return nativeGetName(fd);
        } catch (Throwable t) {
            Log.w(TAG, "EVIOCGNAME failed", t);
            return null;
        }
    }

    private static native int nativeGrab(FileDescriptor fd, boolean enable);
    private static native String nativeGetName(FileDescriptor fd);
}
