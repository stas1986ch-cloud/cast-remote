/**
 * @file NestAlertActionReceiver.kt
 * @summary Handles notification actions (Restore to Target, OK/Dismiss, Snooze 1 Day) and repeating 15-minute follow-up critical buzz alarms for Nest warnings.
 * @description Executes background thermostat restoration via Google Home SDK when Restore is tapped, clears/snoozes active warnings, records every action in the Nest Change Log, and re-buzzes every 15 minutes while unresolved.
 * @testing Compiled via Gradle testDebugUnitTest and assembleRelease.
 */
package com.project.autoswitch.ui.nest

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

class NestAlertActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_RESTORE_TARGET = "com.project.autoswitch.NEST_RESTORE_TARGET"
        const val ACTION_DISMISS_OK = "com.project.autoswitch.NEST_DISMISS_OK"
        const val ACTION_SNOOZE_DAY = "com.project.autoswitch.NEST_SNOOZE_DAY"
        const val ACTION_FOLLOWUP_BUZZ = "com.project.autoswitch.NEST_FOLLOWUP_BUZZ"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val key = intent.getStringExtra("thermostat_key") ?: return
        val name = intent.getStringExtra("thermostat_name") ?: "Nest Thermostat"
        val notifId = intent.getIntExtra("notif_id", key.hashCode())
        val restoreTargetF = intent.getDoubleExtra("restore_target_f", NestThermostatBridge.loadRules(context).resetTempF.toDouble())

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                when (action) {
                    ACTION_RESTORE_TARGET -> {
                        val (ok, msg) = NestThermostatBridge.setTargetTemperatureFromBackground(context, key, restoreTargetF)
                        NestThermostatBridge.clearActiveWarning(context, key)
                        nm.cancel(notifId)
                        NestThermostatBridge.appendChangeLog(
                            context = context,
                            tool = name,
                            change = String.format(Locale.US, "Restore requested -> %.1f°F", restoreTargetF),
                            actionTaken = if (ok) "Restored to ${String.format(Locale.US, "%.1f°F", restoreTargetF)}" else "Restore failed: $msg"
                        )
                    }
                    ACTION_DISMISS_OK -> {
                        NestThermostatBridge.clearActiveWarning(context, key)
                        nm.cancel(notifId)
                        NestThermostatBridge.appendChangeLog(
                            context = context,
                            tool = name,
                            change = "Warning acknowledged",
                            actionTaken = "OK / Dismissed"
                        )
                    }
                    ACTION_SNOOZE_DAY -> {
                        NestThermostatBridge.snoozeForOneDay(context, key)
                        NestThermostatBridge.clearActiveWarning(context, key)
                        nm.cancel(notifId)
                        NestThermostatBridge.appendChangeLog(
                            context = context,
                            tool = name,
                            change = "Alerts paused for 24h",
                            actionTaken = "Snoozed 1 Day"
                        )
                    }
                    ACTION_FOLLOWUP_BUZZ -> {
                        val active = NestThermostatBridge.getActiveWarning(context, key)
                        if (active != null && !NestThermostatBridge.isSnoozed(context, key)) {
                            NestThermostatBridge.appendChangeLog(
                                context = context,
                                tool = active.name,
                                change = active.body,
                                actionTaken = "15m Follow-up Critical Buzz"
                            )
                            NestThermostatBridge.postAlertNotification(
                                context = context,
                                id = notifId,
                                title = "REMINDER: ${active.title}",
                                body = active.body,
                                thermostatKey = active.key,
                                thermostatName = active.name,
                                restoreTargetF = active.restoreTargetF,
                                scheduleFollowup = true
                            )
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
