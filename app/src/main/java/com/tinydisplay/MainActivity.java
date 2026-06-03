package com.tinydisplay;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.appcompat.widget.Toolbar;

/**
 * Launcher dashboard for TinyDisplay.
 *
 * Shows a live mirror of the rear sub-screen and embeds the full settings list
 * (notification access lives at the bottom of that list).
 */
public class MainActivity extends AppCompatActivity {

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

    private void requestCameraPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 29);
        }
    }
}
