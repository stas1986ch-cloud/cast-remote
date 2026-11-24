package com.project.autoswitch.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.DataOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class HdmiSettingsActivity extends AppCompatActivity {
    private static final String TAG = "HdmiSettings";
    private Spinner hdmiSpinner;
    private SharedPreferences prefs;

    // Constant for the 'OFF' option
    private static final String OPTION_OFF = "TURN OFF AUTOSWITCH";
    private static final String ID_OFF = "OFF";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hdmi_settings);

        hdmiSpinner = findViewById(R.id.hdmi_spinner);
        Button btnSave = findViewById(R.id.btn_save);
        btnSave.setText("APPLY");
        
        final TextView statusText = findViewById(R.id.status_text);
        prefs = getSharedPreferences("ProjectPrefs", Context.MODE_PRIVATE);

        TvInputManager manager = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        List<TvInputInfo> inputs = (manager != null) ? manager.getTvInputList() : new ArrayList<TvInputInfo>();
        
        final List<String> hdmiIds = new ArrayList<>();
        List<String> displayNames = new ArrayList<>();

        // 1. ADD 'OFF' OPTION AS THE FIRST ITEM (INDEX 0)
        hdmiIds.add(ID_OFF);
        displayNames.add(OPTION_OFF);

        // 2. Add detected HDMI ports
        for (TvInputInfo input : inputs) {
            if (input.getType() == TvInputInfo.TYPE_HDMI) {
                String fullId = input.getId();
                hdmiIds.add(fullId);
                CharSequence label = input.loadLabel(this);
                String friendlyName = (label != null) ? label.toString() : "HDMI";
                String internalCode = fullId.contains("/") ? fullId.substring(fullId.lastIndexOf('/') + 1) : fullId;
                displayNames.add(friendlyName + " - " + internalCode);
            }
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.spinner_item, displayNames);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        hdmiSpinner.setAdapter(adapter);

        // 3. CHECK SAVED STATE
        boolean isConfigured = prefs.getBoolean("is_configured", false);
        String savedId = prefs.getString("target_hdmi", ID_OFF); // Default to OFF if never set

        if (!isConfigured || savedId.equals(ID_OFF)) {
            statusText.setText("STATUS: AUTOSWITCH DISABLED");
            statusText.setTextColor(Color.RED);
            hdmiSpinner.setSelection(0); // Set spinner to 'OFF' by default
        } else {
            // Find and select the saved HDMI port in the list
            int index = hdmiIds.indexOf(savedId);
            String armedName = (index >= 0) ? displayNames.get(index) : prefs.getString("target_hdmi_name", savedId);
            statusText.setText("STATUS: ARMED FOR " + armedName);
            statusText.setTextColor(Color.CYAN);
            if (index >= 0) hdmiSpinner.setSelection(index);
        }

        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int pos = hdmiSpinner.getSelectedItemPosition();
                if (pos >= 0 && pos < hdmiIds.size()) {
                    String fullId = hdmiIds.get(pos);
                    String displayName = displayNames.get(pos);
                    
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putString("target_hdmi", fullId);
                    
                    // If 'OFF' is selected, we consider it 'not configured' to stop the boot receiver
                    if (fullId.equals(ID_OFF)) {
                        editor.putString("target_hdmi_name", "OFF");
                        editor.putBoolean("is_configured", false);
                        statusText.setText("STATUS: AUTOSWITCH DISABLED");
                        statusText.setTextColor(Color.RED);
                        Toast.makeText(HdmiSettingsActivity.this, "AUTOSWITCH DISABLED", Toast.LENGTH_SHORT).show();
                    } else {
                        editor.putString("target_hdmi_name", displayName);
                        editor.putBoolean("is_configured", true);
                        statusText.setText("STATUS: ARMED FOR " + displayName);
                        statusText.setTextColor(Color.CYAN);
                        Toast.makeText(HdmiSettingsActivity.this, "ARMED FOR " + displayName, Toast.LENGTH_SHORT).show();
                    }
                    editor.apply();

                    // Reboot logic
                    if (isDeviceRooted()) {
                        Toast.makeText(HdmiSettingsActivity.this, "ROOT DETECTED. REBOOTING...", Toast.LENGTH_LONG).show();
                        v.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                performReboot();
                            }
                        }, 1500);
                    } else {
                        showManualRestartDialog();
                    }
                }
            }
        });
    }

    private boolean isDeviceRooted() {
        String[] paths = {"/system/app/Superuser.apk", "/sbin/su", "/system/bin/su", "/system/xbin/su", "/data/local/xbin/su", "/data/local/bin/su", "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su", "/su/bin/su"};
        for (String path : paths) { if (new File(path).exists()) return true; }
        return false;
    }

    private void showManualRestartDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Configuration Applied")
            .setMessage("Restart the device to check auto switch works")
            .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) { dialog.dismiss(); }
            })
            .setIcon(android.R.drawable.ic_dialog_info)
            .show();
    }

    private void performReboot() {
        Log.d(TAG, "Initiating reboot sequence...");
        if (tryRootReboot()) return;
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) { pm.reboot(null); return; }
        } catch (Exception e) { Log.e(TAG, "PowerManager reboot failed: " + e.getMessage()); }
        try {
            Intent intent = new Intent(Intent.ACTION_REBOOT);
            intent.putExtra("nowait", 1);
            intent.putExtra("nowait", 1);
            intent.putExtra("interval", 1);
            intent.putExtra("window", 0);
            sendBroadcast(intent);
        } catch (SecurityException e) { showManualRestartDialog(); }
        catch (Exception e) { Log.e(TAG, "Intent reboot failed: " + e.getMessage()); }
    }

    private boolean tryRootReboot() {
        Process process = null;
        DataOutputStream os = null;
        try {
            process = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(process.getOutputStream());
            os.writeBytes("reboot\n");
            os.flush();
            os.writeBytes("exit\n");
            os.flush();
            process.waitFor();
            return true;
        } catch (Exception e) { return false;
        } finally {
            try { if (os != null) os.close(); if (process != null) process.destroy(); } catch (Exception ignored) {}
        }
    }
}
