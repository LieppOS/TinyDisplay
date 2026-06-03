package com.tinydisplay.hal;

import android.os.MemoryFile;
import android.util.Log;

import java.io.FileDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * HIDL HAL wrapper for vendor.yft.hardware.tinylcd@1.0::ITinylcd.
 *
 * Uses reflection to access hidden android.os.HwBinder / HwParcel / HwBlob APIs.
 * Transaction codes (from HIDL stub symbol ordering):
 *   1 = lcd_set_power (1=on, 0=off)
 *   2 = lcd_set_backlight (brightness level)
 *   3 = lcd_set_bl (backlight enable/disable)
 *   4 = lcd_wirte_frame (259200 bytes, RGB565, via HwBlob)
 *   5 = lcd_frame_status
 *   6 = lcd_wirte_frame_240 (115200 bytes)
 *   7 = lcd_wirte_frame_390 (351000 bytes)
 *   8 = lcd_wirte_frame_memory (NativeHandle + int)
 *   9 = lcd_start_gesture (touch panel)
 *  10 = lcd_wirte_frame_size (two int params)
 */
public class TinyLcdHal {

    private static final String TAG = "TinyLcdHal";
    private static final String INTERFACE_TOKEN = "vendor.yft.hardware.tinylcd@1.0::ITinylcd";
    private static final String INSTANCE_NAME = "default";
    public static final int FRAME_SIZE = 259200; // 360 * 360 * 2 (RGB565) — HAL-required byte count
    public static final int BUFFER_WIDTH = 360;
    public static final int BUFFER_HEIGHT = 360;
    // The HAL accepts a 360x360 (129600 px) buffer, but the CO5300 controller scans
    // PANEL_WIDTH-wide rows. The official panel is 340x340; the extra 20 px of pitch
    // per 360-row do NOT exist on this controller — it reads 340-wide rows back to back.
    // So ALL rendering must lay pixels out at stride 340 (idx = (y*340 + x)*2). Building
    // at stride 360 drifts every row by 20 px and shears text into diagonal stripes.
    // Verified on-device: a left/right split is clean only at stride 340.
    public static final int PANEL_WIDTH = 340;
    public static final int PANEL_HEIGHT = 340;

    // Transaction codes (from HIDL stub symbol ordering in vendor.yft.hardware.tinylcd@1.0.so)
    private static final int TX_POWER           = 1;  // lcd_set_power(int)
    private static final int TX_BACKLIGHT       = 2;  // lcd_set_backlight(int) — brightness level
    private static final int TX_BL_ENABLE       = 3;  // lcd_set_bl(int) — backlight on/off
    private static final int TX_WRITE_FRAME     = 4;  // lcd_wirte_frame(byte[259200])
    private static final int TX_GET_STATUS      = 5;  // lcd_frame_status()
    private static final int TX_WRITE_FRAME_240 = 6;  // lcd_wirte_frame_240(byte[115200])
    private static final int TX_WRITE_FRAME_390 = 7;  // lcd_wirte_frame_390(byte[351000])
    private static final int TX_SHARED_MEM      = 8;  // lcd_wirte_frame_memory(NativeHandle, int)
    private static final int TX_GESTURE         = 9;  // lcd_start_gesture(int)
    private static final int TX_FRAME_SIZE      = 10; // lcd_wirte_frame_size(int, int)

    // Reflected classes
    private Class<?> hwBinderClass;
    private Class<?> hwParcelClass;
    private Class<?> hwBlobClass;
    private Class<?> iHwBinderClass;

    // Reflected methods on HwParcel
    private Method writeInterfaceToken;
    private Method writeInt32;
    private Method writeBuffer;
    private Method verifySuccess;
    private Method readInt32;
    private Method releaseTemporaryStorage;
    private Method releaseParcel;

    // Reflected methods on HwBlob
    private Constructor<?> hwBlobConstructor;
    private Method putInt8Array;
    private Method putInt32Array;
    private Method putInt16Array;
    private Method putInt64;
    private Method putBlobMethod;


    // Reflected methods for shared memory (transaction 8)
    private Class<?> nativeHandleClass;
    private Method writeNativeHandle;

    // Reflected method on IHwBinder
    private Method transactMethod;

    // Shared memory for frame writes
    private MemoryFile memFile;
    private Object nativeHandle;

    // The HAL binder proxy
    private Object hwBinder;

    private boolean connected = false;

    /**
     * Initialize reflection handles for all hidden API classes and methods.
     * Call this once before connect().
     */
    private void initReflection() throws ReflectiveOperationException {
        hwBinderClass = Class.forName("android.os.HwBinder");
        hwParcelClass = Class.forName("android.os.HwParcel");
        hwBlobClass   = Class.forName("android.os.HwBlob");
        iHwBinderClass = Class.forName("android.os.IHwBinder");

        // HwParcel methods
        writeInterfaceToken = hwParcelClass.getMethod("writeInterfaceToken", String.class);
        writeInt32 = hwParcelClass.getMethod("writeInt32", int.class);
        writeBuffer = hwParcelClass.getMethod("writeBuffer", hwBlobClass);
        verifySuccess = hwParcelClass.getMethod("verifySuccess");
        readInt32 = hwParcelClass.getMethod("readInt32");
        releaseTemporaryStorage = hwParcelClass.getMethod("releaseTemporaryStorage");
        releaseParcel = hwParcelClass.getMethod("release");

        // HwBlob constructor and methods
        hwBlobConstructor = hwBlobClass.getConstructor(int.class);
        putInt8Array = hwBlobClass.getMethod("putInt8Array", long.class, byte[].class);
        try {
            putInt32Array = hwBlobClass.getMethod("putInt32Array", long.class, int[].class);
            Log.i(TAG, "putInt32Array available");
        } catch (NoSuchMethodException e) {
            Log.w(TAG, "putInt32Array not available");
        }
        try {
            putInt16Array = hwBlobClass.getMethod("putInt16Array", long.class, short[].class);
            Log.i(TAG, "putInt16Array available");
        } catch (NoSuchMethodException e) {
            Log.w(TAG, "putInt16Array not available");
        }

        // HwBlob methods for vec serialization
        try {
            putInt64 = hwBlobClass.getMethod("putInt64", long.class, long.class);
            putBlobMethod = hwBlobClass.getMethod("putBlob", long.class, hwBlobClass);
            Log.i(TAG, "putInt64/putBlob available for vec serialization");
        } catch (NoSuchMethodException e) {
            Log.w(TAG, "putInt64/putBlob not available: " + e.getMessage());
        }

        // NativeHandle for shared memory frame writes
        nativeHandleClass = Class.forName("android.os.NativeHandle");
        writeNativeHandle = hwParcelClass.getMethod("writeNativeHandle", nativeHandleClass);

        // IHwBinder.transact(int code, HwParcel request, HwParcel reply, int flags)
        transactMethod = iHwBinderClass.getMethod("transact",
                int.class, hwParcelClass, hwParcelClass, int.class);

        Log.i(TAG, "Reflection initialized successfully");
    }

    /**
     * Connect to the HIDL HAL service.
     * Calls HwBinder.getService("vendor.yft.hardware.tinylcd@1.0::ITinylcd", "default", true)
     *
     * @return true if connected successfully
     */
    public boolean connect() {
        try {
            initReflection();

            Method getService = hwBinderClass.getMethod("getService",
                    String.class, String.class, boolean.class);

            hwBinder = getService.invoke(null, INTERFACE_TOKEN, INSTANCE_NAME, true);

            if (hwBinder == null) {
                Log.e(TAG, "getService returned null — HAL not registered. Check lshal and SELinux.");
                return false;
            }

            connected = true;
            Log.i(TAG, "Connected to ITinylcd HAL service");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to connect to HAL", e);
            connected = false;
            return false;
        }
    }

    public boolean isConnected() {
        return connected;
    }

    /**
     * Execute a HIDL transaction that sends one int32 argument and reads one int32 result.
     */
    private int transactInt(int txCode, int value, String methodName) {
        if (!connected) {
            Log.e(TAG, methodName + ": not connected");
            return -1;
        }
        Object request = null;
        Object reply = null;
        try {
            request = hwParcelClass.getConstructor().newInstance();
            writeInterfaceToken.invoke(request, INTERFACE_TOKEN);
            writeInt32.invoke(request, value);

            reply = hwParcelClass.getConstructor().newInstance();
            transactMethod.invoke(hwBinder, txCode, request, reply, 0);
            verifySuccess.invoke(reply);
            releaseTemporaryStorage.invoke(request);

            int result = (int) readInt32.invoke(reply);
            Log.d(TAG, methodName + "(" + value + ") = " + result);
            return result;
        } catch (Exception e) {
            Log.e(TAG, methodName + " failed", e);
            return -1;
        } finally {
            safeRelease(reply);
        }
    }

    /**
     * Execute a HIDL transaction with no arguments, reads one int32 result.
     */
    private int transactNoArgs(int txCode, String methodName) {
        if (!connected) {
            Log.e(TAG, methodName + ": not connected");
            return -1;
        }
        Object request = null;
        Object reply = null;
        try {
            request = hwParcelClass.getConstructor().newInstance();
            writeInterfaceToken.invoke(request, INTERFACE_TOKEN);

            reply = hwParcelClass.getConstructor().newInstance();
            transactMethod.invoke(hwBinder, txCode, request, reply, 0);
            verifySuccess.invoke(reply);
            releaseTemporaryStorage.invoke(request);

            int result = (int) readInt32.invoke(reply);
            Log.d(TAG, methodName + "() = " + result);
            return result;
        } catch (Exception e) {
            Log.e(TAG, methodName + " failed", e);
            return -1;
        } finally {
            safeRelease(reply);
        }
    }

    /**
     * Execute a HIDL transaction with two int32 arguments, reads one int32 result.
     */
    private int transactTwoInts(int txCode, int val1, int val2, String methodName) {
        if (!connected) {
            Log.e(TAG, methodName + ": not connected");
            return -1;
        }
        Object request = null;
        Object reply = null;
        try {
            request = hwParcelClass.getConstructor().newInstance();
            writeInterfaceToken.invoke(request, INTERFACE_TOKEN);
            writeInt32.invoke(request, val1);
            writeInt32.invoke(request, val2);

            reply = hwParcelClass.getConstructor().newInstance();
            transactMethod.invoke(hwBinder, txCode, request, reply, 0);
            verifySuccess.invoke(reply);
            releaseTemporaryStorage.invoke(request);

            int result = (int) readInt32.invoke(reply);
            Log.d(TAG, methodName + "(" + val1 + ", " + val2 + ") = " + result);
            return result;
        } catch (Exception e) {
            Log.e(TAG, methodName + " failed", e);
            return -1;
        } finally {
            safeRelease(reply);
        }
    }

    private void safeRelease(Object parcel) {
        if (parcel != null) {
            try {
                releaseParcel.invoke(parcel);
            } catch (Exception ignored) {}
        }
    }

    // ── Public HAL methods ──────────────────────────────────────────

    /** Transaction 3: Power control. 1 = on, 0 = off. */
    public int setPower(int on) {
        return transactInt(TX_POWER, on, "setPower");
    }

    /** Transaction 9: Set backlight level. */
    public int setBacklight(int level) {
        return transactInt(TX_BACKLIGHT, level, "setBacklight");
    }

/** Transaction 5: Get status (no arguments). */
    public int getStatus() {
        return transactNoArgs(TX_GET_STATUS, "getStatus");
    }

    /** Transaction 3: Backlight enable/disable (separate from brightness level). */
    public int setBlEnable(int on) {
        return transactInt(TX_BL_ENABLE, on, "setBlEnable");
    }

    /** Transaction 9: Ask vendor driver to enable rear touch/gesture handling. */
    public int startGesture() {
        return transactNoArgs(TX_GESTURE, "startGesture");
    }

    /** Transaction 10: Set frame size (two int params). */
    public int setFrameSize(int val1, int val2) {
        return transactTwoInts(TX_FRAME_SIZE, val1, val2, "setFrameSize");
    }

    /**
     * Transaction 4: Write a frame to the sub-screen.
     *
     * Uses HwBlob(259200) + putInt8Array + writeBuffer, exactly as the stock
     * HIDL proxy does. The byte array MUST be exactly 259,200 bytes (360x360 RGB565).
     *
     * @param rgb565Data RGB565 frame data, exactly 259200 bytes
     * @return HAL result code, or -1 on error
     */
    public int writeFrame(byte[] rgb565Data) {
        if (!connected) {
            Log.e(TAG, "writeFrame: not connected");
            return -1;
        }
        if (rgb565Data == null || rgb565Data.length != FRAME_SIZE) {
            Log.e(TAG, "writeFrame: data must be exactly " + FRAME_SIZE + " bytes, got "
                    + (rgb565Data == null ? "null" : rgb565Data.length));
            return -1;
        }

        Object request = null;
        Object reply = null;
        try {
            request = hwParcelClass.getConstructor().newInstance();
            writeInterfaceToken.invoke(request, INTERFACE_TOKEN);

            // Create HwBlob(259200), fill with frame data, verify, write as buffer
            Object blob = hwBlobConstructor.newInstance(FRAME_SIZE);
            putInt8Array.invoke(blob, 0L, rgb565Data);

            writeBuffer.invoke(request, blob);

            reply = hwParcelClass.getConstructor().newInstance();
            transactMethod.invoke(hwBinder, TX_WRITE_FRAME, request, reply, 0);
            verifySuccess.invoke(reply);
            releaseTemporaryStorage.invoke(request);

            int result = (int) readInt32.invoke(reply);
            Log.d(TAG, "writeFrame: result=" + result);
            return result;
        } catch (Exception e) {
            Log.e(TAG, "writeFrame failed", e);
            return -1;
        } finally {
            safeRelease(reply);
        }
    }

    /** Disconnect and clean up. */
    public void disconnect() {
        hwBinder = null;
        connected = false;
        Log.i(TAG, "Disconnected from HAL");
    }
}
