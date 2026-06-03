package com.tinydisplay;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Build;
import android.util.TypedValue;
import android.view.MenuItem;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

public class TinyDisplaySettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The Settings homepage tile points here; always show the modern
        // dashboard (MainActivity) instead of the bare preference list.
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public static class SettingsFragment extends PreferenceFragmentCompat
            implements SharedPreferences.OnSharedPreferenceChangeListener {

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.tinydisplay_preferences, rootKey);
            updateBrightnessSummary();
        }

        @Override
        public void onViewCreated(View view, Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            getListView().setBackgroundColor(resolveColor(android.R.attr.colorBackground));
            getListView().setClipToPadding(false);
            int horizontalPadding = (int) (8 * getResources().getDisplayMetrics().density);
            int verticalPadding = (int) (24 * getResources().getDisplayMetrics().density);
            getListView().setPadding(
                    horizontalPadding,
                    getListView().getPaddingTop(),
                    horizontalPadding,
                    verticalPadding);
        }

        @Override
        public void onResume() {
            super.onResume();
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .registerOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onPause() {
            super.onPause();
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .unregisterOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
            if ("brightness".equals(key)) {
                updateBrightnessSummary();
            }

            if ("background_service_enabled".equals(key)) {
                Intent svc = new Intent(requireContext(), TinyDisplayService.class);
                if (sp.getBoolean(key, true)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        requireContext().startForegroundService(svc);
                    } else {
                        requireContext().startService(svc);
                    }
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        requireContext().startForegroundService(svc);
                    } else {
                        requireContext().startService(svc);
                    }
                }
            }

            Intent intent = new Intent("com.tinydisplay.CONFIG_CHANGED");
            requireContext().sendBroadcast(intent);
        }

        private void updateBrightnessSummary() {
            ListPreference bp = findPreference("brightness");
            if (bp != null && bp.getEntry() != null) {
                bp.setSummary(bp.getEntry());
            }
        }

        private int resolveColor(int attr) {
            TypedValue value = new TypedValue();
            requireContext().getTheme().resolveAttribute(attr, value, true);
            return value.data;
        }
    }
}
