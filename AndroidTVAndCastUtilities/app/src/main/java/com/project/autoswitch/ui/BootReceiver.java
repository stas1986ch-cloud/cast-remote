package com.project.autoswitch.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * BootReceiver: The core engine for automatic HDMI switching.
 * Optimized for "Background-First" approach:
 * 1. Overlays the splash screen instantly.
 * 2. Starts a background Service to handle the HDMI switching logic.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(final Context context, Intent intent) {
        Log.d(TAG, "OS Signal Received: " + intent.getAction());

        // 1. OVERLAY THE SPLASH SCREEN INSTANTLY
        // This hides the OS UI while the background service works.
        Intent splashIntent = new Intent(context, SplashActivity.class);
        splashIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        splashIntent.putExtra("from_boot", true);
        context.startActivity(splashIntent);

        // 2. START THE BACKGROUND SERVICE
        // Using a Service tells the OS this is a background command that doesn't need to wait for windows.
        Intent serviceIntent = new Intent(context, AutoSwitchService.class);
        context.startService(serviceIntent);
    }
}
