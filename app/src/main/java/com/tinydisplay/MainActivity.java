package com.tinydisplay;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;

/**
 * Launcher dashboard for TinyDisplay.
 *
 * Shows notification-access status (required for the notification mirroring
 * feature), a one-tap preview action, and embeds the full settings screen so
 * all toggles live in one place.
 */
public class MainActivity extends AppCompatActivity {

    private TextView notifStatus;
    private MaterialButton notifButton;
    private SubScreenMirrorView mirror;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_TinyDisplay);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.app_name);
        }

        mirror = findViewById(R.id.mirror);
        notifStatus = findViewById(R.id.notif_status);
        notifButton = findViewById(R.id.notif_button);
        notifButton.setOnClickListener(v -> openNotificationAccess());

        findViewById(R.id.preview_button).setOnClickListener(v -> previewOnSubScreen());
        requestCameraPermissionIfNeeded();

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settings_container,
                            new TinyDisplaySettingsActivity.SettingsFragment())
                    .commit();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshNotificationAccess();
        TinyDisplayService.setFrameListener(frame -> {
            if (mirror != null) mirror.update(frame);
        });
        // Ensure the renderer service is alive so the mirror has a live source.
        Intent svc = new Intent(this, TinyDisplayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc);
        else startService(svc);
    }

    @Override
    protected void onPause() {
        super.onPause();
        TinyDisplayService.setFrameListener(null);
    }

    private void refreshNotificationAccess() {
        boolean granted = isNotificationAccessGranted();
        if (granted) {
            notifStatus.setText(R.string.dash_notif_access_granted);
            notifButton.setVisibility(MaterialButton.GONE);
        } else {
            notifStatus.setText(R.string.dash_notif_access_needed);
            notifButton.setVisibility(MaterialButton.VISIBLE);
        }
    }

    private boolean isNotificationAccessGranted() {
        String flat = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        return flat != null && flat.contains(getPackageName());
    }

    private void requestCameraPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 29);
        }
    }

    private void openNotificationAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    /** Power on the sub-screen and show the clock briefly, regardless of config. */
    private void previewOnSubScreen() {
        Intent svc = new Intent(this, TinyDisplayService.class);
        svc.setAction(TinyDisplayService.ACTION_PREVIEW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
    }
}
