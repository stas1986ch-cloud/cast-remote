/**
 * @file NestMonitorService.kt
 * @summary Foreground Android service that periodically polls Nest thermostats via Google Home SDK and fires alerts.
 * @description Runs a coroutine loop at the user-configured interval, evaluates temperature delta and low-temperature thresholds, and keeps an unobtrusive status notification while armed.
 * @testing Verified via Gradle assembleRelease compilation and Android 14+ dataSync foreground service manifest registration.
 */
package com.project.autoswitch.ui.nest

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NestMonitorService : Service() {
    companion object {
        private const val CHANNEL_STATUS = "nest_monitor_service"
        private const val NOTIF_ID = 4102

        fun start(context: Context) {
            val intent = Intent(context, NestMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NestMonitorService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureStatusChannel()
        startForeground(NOTIF_ID, buildStatusNotification("Monitoring Nest thermostats..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rules = NestThermostatBridge.loadRules(this)
        if (!rules.enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(
            NOTIF_ID,
            buildStatusNotification("Watching ±${rules.deltaDegreesF}°F or ≤${rules.minTempF}°F (every ${rules.pollIntervalMin}m)")
        )
        startPollingLoop()
        return START_STICKY
    }

    private fun startPollingLoop() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val rules = NestThermostatBridge.loadRules(this@NestMonitorService)
                if (!rules.enabled) {
                    stopSelf()
                    break
                }
                val (snaps, _) = NestThermostatBridge.fetchThermostats(this@NestMonitorService)
                if (snaps.isNotEmpty()) {
                    NestThermostatBridge.evaluateAlerts(this@NestMonitorService, snaps, fireNotifications = true)
                }
                delay(rules.pollIntervalMin.coerceIn(1, 60) * 60_000L)
            }
        }
    }

    private fun ensureStatusChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_STATUS) == null) {
                val ch = NotificationChannel(
                    CHANNEL_STATUS,
                    "Nest Monitor Background Service",
                    NotificationManager.IMPORTANCE_LOW
                )
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun buildStatusNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Nest Thermostat Monitor")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, NestMonitorActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    override fun onDestroy() {
        pollJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
