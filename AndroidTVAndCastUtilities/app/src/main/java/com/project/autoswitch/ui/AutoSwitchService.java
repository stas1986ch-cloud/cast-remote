package com.project.autoswitch.ui;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * Background Service for automatic HDMI switching.
 * Requirement: Execute the switch command in the background as soon as possible, 
 * without waiting for UI windows to initialize.
 */
public class AutoSwitchService extends Service {
    private static final String TAG = "AutoSwitchService";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String targetHdmi;
    private TvInputManager manager;
    private int attempts = 0;
    private static final int MAX_ATTEMPTS = 100;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Background Switch Service Started");

        SharedPreferences prefs = getSharedPreferences("ProjectPrefs", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("is_configured", false)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        targetHdmi = prefs.getString("target_hdmi", "");
        if (targetHdmi.isEmpty() || "OFF".equals(targetHdmi)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        manager = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        
        // Start polling immediately in the background
        startPolling();

        return START_STICKY;
    }

    private void startPolling() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                attempts++;
                try {
                    TvInputInfo info = manager.getTvInputInfo(targetHdmi);
                    if (info != null) {
                        Log.d(TAG, "Hardware Ready. Triggering System Switch Intent.");
                        
                        Uri uri = TvContract.buildChannelUriForPassthroughInput(targetHdmi);
                        Intent switchIntent = new Intent(Intent.ACTION_VIEW, uri);
                        switchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                        
                        // Executing this from a service is more direct as it's already a background process
                        startActivity(switchIntent);
                        stopSelf(); // Mission accomplished
                        return;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Service Switching error: " + e.getMessage());
                }

                if (attempts < MAX_ATTEMPTS) {
                    handler.postDelayed(this, 100);
                } else {
                    Log.e(TAG, "Service timed out waiting for HDMI hardware.");
                    stopSelf();
                }
            }
        });
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
