/**
 * @file ScreenControlApp.kt
 * @summary Application lifecycle tracker enforcing a 15-minute maximum AdMob frequency across both cold starts and background reloads.
 * @description Persists last_ad_shown_ms in SharedPreferences so ads appear at most once per 15 minutes even if the user closes and reopens the app repeatedly.
 * @testing Verified via Gradle assembleRelease compilation and cooldown timestamp boundary logic.
 */
package com.project.autoswitch.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle

class ScreenControlApp : Application(), Application.ActivityLifecycleCallbacks {
    companion object {
        private const val AD_PREFS = "AdCooldownPrefs"
        private const val KEY_LAST_AD_MS = "last_ad_shown_ms"
        const val AD_COOLDOWN_MS = 15L * 60_000L

        @Volatile
        private var suppressNextAdUntilMs: Long = 0L

        @JvmStatic
        fun suppressNextAdBriefly(durationMs: Long = 15_000L) {
            suppressNextAdUntilMs = System.currentTimeMillis() + durationMs
        }

        @JvmStatic
        fun canShowAdNow(context: Context): Boolean {
            val lastMs = context.getSharedPreferences(AD_PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_AD_MS, 0L)
            return (System.currentTimeMillis() - lastMs) >= AD_COOLDOWN_MS
        }

        @JvmStatic
        fun markAdShownNow(context: Context) {
            context.getSharedPreferences(AD_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_AD_MS, System.currentTimeMillis())
                .apply()
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
            if (activity !is SplashActivity && now > suppressNextAdUntilMs && canShowAdNow(activity)) {
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
