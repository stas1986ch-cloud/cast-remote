/**
 * @file ScreenControlApp.kt
 * @summary Application lifecycle tracker that ensures SplashActivity displays the AdMob ad every time the user reloads or returns to the app from background.
 * @description Counts started activities via ActivityLifecycleCallbacks and launches SplashActivity in return-to-caller mode whenever the app transitions from background (0 started) to foreground (1 started), skipping OAuth consent return windows.
 * @testing Verified via Gradle assembleRelease compilation and lifecycle transition guards.
 */
package com.project.autoswitch.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle

class ScreenControlApp : Application(), Application.ActivityLifecycleCallbacks {
    companion object {
        @Volatile
        private var suppressNextAdUntilMs: Long = 0L

        @JvmStatic
        fun suppressNextAdBriefly(durationMs: Long = 15_000L) {
            suppressNextAdUntilMs = System.currentTimeMillis() + durationMs
        }
    }

    private var startedCount = 0
    private var wasInBackground = false
    private var isChangingConfig = false

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        if (startedCount == 0 && wasInBackground && !isChangingConfig) {
            wasInBackground = false
            val now = System.currentTimeMillis()
            if (activity !is SplashActivity && now > suppressNextAdUntilMs) {
                val adIntent = Intent(activity, SplashActivity::class.java).apply {
                    putExtra("return_to_caller", true)
                    addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                }
                activity.startActivity(adIntent)
            }
        }
        startedCount++
    }

    override fun onActivityStopped(activity: Activity) {
        isChangingConfig = activity.isChangingConfigurations
        startedCount = (startedCount - 1).coerceAtLeast(0)
        if (startedCount == 0 && !isChangingConfig) {
            wasInBackground = true
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
