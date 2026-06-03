package com.tinydisplay;

import android.app.Notification;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * Captures notifications from other apps and forwards them to the sub-screen.
 * Requires the user to grant notification access (Settings > Notification access,
 * or the "Grant access" button in the TinyDisplay app).
 */
public class NotificationListener extends NotificationListenerService {

    private static final String TAG = "TinyDisplayNotif";

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        if (getPackageName().equals(sbn.getPackageName())) return; // skip our own

        Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return;

        // Skip ongoing/group-summary/low-importance noise (music transport,
        // "running in background", sync, etc.) — only show user-facing alerts.
        if (!sbn.isClearable()) return;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0) return;

        CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        if ((title == null || title.length() == 0) && (text == null || text.length() == 0)) {
            return; // nothing useful to show
        }

        String appName = resolveAppName(sbn.getPackageName());

        Log.d(TAG, "Notification " + appName + " / " + title + " / " + text);

        String titleText = title != null ? title.toString() : null;
        String bodyText = text != null ? text.toString() : null;
        TinyDisplayService svc = TinyDisplayService.instance;
        if (svc != null) {
            svc.onExternalNotification(appName, titleText, bodyText);
        } else {
            Intent intent = new Intent(this, TinyDisplayService.class);
            intent.setAction(TinyDisplayService.ACTION_SHOW_NOTIFICATION);
            intent.putExtra(TinyDisplayService.EXTRA_NOTIF_APP, appName);
            intent.putExtra(TinyDisplayService.EXTRA_NOTIF_TITLE, titleText);
            intent.putExtra(TinyDisplayService.EXTRA_NOTIF_TEXT, bodyText);
            startForegroundService(intent);
        }
    }

    private String resolveAppName(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // No action needed.
    }
}
