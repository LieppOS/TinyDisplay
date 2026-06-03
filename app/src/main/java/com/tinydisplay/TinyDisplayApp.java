package com.tinydisplay;

import android.app.Application;
import android.util.Log;

public class TinyDisplayApp extends Application {

    private static final String TAG = "TinyDisplayApp";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Application onCreate");
    }
}
