package com.tinydisplay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.preference.PreferenceManager;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "TinyDisplayBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            if (!prefs.getBoolean("background_service_enabled", true)) {
                Log.i(TAG, "Boot completed — background service disabled");
                return;
            }

            Log.i(TAG, "Boot completed — starting TinyDisplayService");
            Intent svc = new Intent(context, TinyDisplayService.class);
            svc.putExtra(TinyDisplayService.EXTRA_BOOT_COMPLETED, true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        }
    }
}
