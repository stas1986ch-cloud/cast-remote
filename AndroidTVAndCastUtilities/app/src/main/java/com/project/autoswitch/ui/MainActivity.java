/**
 * @file MainActivity.java
 * @summary Main launcher hub for AndroidTV & Cast Utilities supporting both Phones and Google TVs.
 * @description Adapts the GridLayout column count to phone or TV screen width and routes to HDMI Auto-Boot and Cast Remote & TV Power.
 * @testing Verified via Gradle APK compilation and responsive layout width calculations.
 */
package com.project.autoswitch.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.GridLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.card.MaterialCardView;
import com.project.autoswitch.ui.cast.CastRemoteActivity;
import java.util.HashSet;
import java.util.Set;

public class MainActivity extends AppCompatActivity {
    private TextView hdmiSubtitle;
    private TextView castSubtitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        hdmiSubtitle = findViewById(R.id.tile_hdmi_subtitle);
        castSubtitle = findViewById(R.id.tile_cast_subtitle);

        GridLayout grid = findViewById(R.id.hub_grid);
        if (grid != null) {
            float dpWidth = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
            int cols = dpWidth >= 700f ? 3 : (dpWidth >= 460f ? 2 : 1);
            grid.setColumnCount(cols);
        }

        MaterialCardView hdmiTile = findViewById(R.id.tile_hdmi_settings);
        if (hdmiTile != null) {
            hdmiTile.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, HdmiSettingsActivity.class)));
        }

        MaterialCardView castTile = findViewById(R.id.tile_cast_remote);
        if (castTile != null) {
            castTile.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, CastRemoteActivity.class)));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateArmedStatus();
        updateCastSummary();
    }

    private void updateArmedStatus() {
        if (hdmiSubtitle == null) return;
        SharedPreferences prefs = getSharedPreferences("ProjectPrefs", Context.MODE_PRIVATE);
        boolean isConfigured = prefs.getBoolean("is_configured", false);
        String targetHdmi = prefs.getString("target_hdmi", "");
        String targetName = prefs.getString("target_hdmi_name", "");

        if (isConfigured && !targetHdmi.isEmpty() && !"OFF".equals(targetHdmi)) {
            String display = !targetName.isEmpty() ? targetName : targetHdmi;
            hdmiSubtitle.setText("ARMED: " + display);
            hdmiSubtitle.setTextColor(Color.parseColor("#00FFFF"));
        } else {
            hdmiSubtitle.setText("AUTOSWITCH: OFF");
            hdmiSubtitle.setTextColor(Color.parseColor("#8899A6"));
        }
    }

    private void updateCastSummary() {
        if (castSubtitle == null) return;
        SharedPreferences prefs = getSharedPreferences("CastUtilitiesPrefs", Context.MODE_PRIVATE);
        Set<String> pinned = prefs.getStringSet("pinned_device_ids", new HashSet<>());
        Set<String> paired = prefs.getStringSet("atv_paired_ids", new HashSet<>());
        int pinCount = pinned != null ? pinned.size() : 0;
        int pairCount = paired != null ? paired.size() : 0;
        if (pinCount > 0 || pairCount > 0) {
            castSubtitle.setText(pinCount + " Pinned · " + pairCount + " Paired TVs");
        } else {
            castSubtitle.setText("Pin, Mirror & TV Power");
        }
    }
}
