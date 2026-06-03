package com.tinydisplay;

import android.app.Application;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class TinyDisplayApp extends Application {

    private static final String TAG = "TinyDisplayApp";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Application onCreate — starting TinyDisplayService");

        Intent intent = new Intent(this, TinyDisplayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }
}
