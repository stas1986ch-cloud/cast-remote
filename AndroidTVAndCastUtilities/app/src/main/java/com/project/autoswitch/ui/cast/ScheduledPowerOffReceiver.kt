/**
 * @file ScheduledPowerOffReceiver.kt
 * @summary BroadcastReceiver triggered by AlarmManager to execute scheduled device power-off in the background.
 * @description Uses goAsync() with a background coroutine to turn off the target device via Google Home SDK (with local ATV Remote / Cast stop fallback) and posts a completion notification.
 * @testing Compiled via Gradle assembleRelease and registered in AndroidManifest.xml.
 */
package com.project.autoswitch.ui.cast

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ScheduledPowerOffReceiver : BroadcastReceiver() {
    companion object {
        private const val CHANNEL_ID = "scheduled_power_off"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val deviceId = intent.getStringExtra("device_id") ?: return
        val deviceName = intent.getStringExtra("device_name") ?: "Device"
        val homeKey = intent.getStringExtra("home_key") ?: ""
        val host = intent.getStringExtra("host") ?: ""
        val port = intent.getIntExtra("port", 8009)

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ScheduledPowerOffManager.cancelSchedule(context, deviceId)
                var ok = false
                var detail = ""

                if (homeKey.isNotEmpty() || deviceName.isNotEmpty()) {
                    val (homeOk, msg) = GoogleHomePowerBridge.powerOffFromBackground(context, homeKey, deviceName)
                    ok = homeOk
                    detail = msg
                }

                if (!ok && host.isNotEmpty()) {
                    try {
                        val engine = CastAndTvEngine(context)
                        if (engine.isPaired(deviceId)) {
                            engine.sendPower(deviceId, host, CastAndTvEngine.PowerAction.POWER_OFF)
                            ok = true
                            detail = "ATV Remote"
                        } else {
                            engine.stopCastApp(host, port)
                            ok = true
                            detail = "Cast Stop"
                        }
                    } catch (e: Exception) {
                        detail = e.message ?: "Failed"
                    }
                }

                postResultNotification(
                    context,
                    deviceId.hashCode(),
                    if (ok) "Scheduled Turn Off: $deviceName" else "Scheduled Off Failed: $deviceName",
                    if (ok) "Turned off $deviceName as scheduled." else "Could not turn off $deviceName: $detail"
                )
            } finally {
                pending.finish()
            }
        }
    }

    private fun postResultNotification(context: Context, id: Int, title: String, body: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Scheduled Power Off", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_power_off)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            nm.notify(id, notif)
        } catch (_: SecurityException) {}
    }
}
