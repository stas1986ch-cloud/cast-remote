/**
 * @file ScheduledPowerOffManager.kt
 * @summary Persists and schedules future device turn-off alarms by date and time using Android AlarmManager.
 * @description Stores per-device schedules as JSON in SharedPreferences, schedules AlarmManager broadcasts (exact when permitted, inexact fallback otherwise), and formats human-readable schedule badges.
 * @testing Compiled via Gradle assembleRelease and verified for exact/inexact alarm API compatibility across Android 8-15.
 */
package com.project.autoswitch.ui.cast

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class ScheduledOffEntry(
    val deviceId: String,
    val deviceName: String,
    val homeKey: String,
    val host: String,
    val port: Int,
    val triggerAtMs: Long
)

object ScheduledPowerOffManager {
    private const val PREFS = "ScheduledPowerOffPrefs"
    private const val KEY_MAP = "schedules_v1"

    @JvmStatic
    fun schedulePowerOff(
        context: Context,
        deviceId: String,
        deviceName: String,
        homeKey: String,
        host: String,
        port: Int,
        triggerAtMs: Long
    ) {
        val entry = ScheduledOffEntry(deviceId, deviceName, homeKey, host, port, triggerAtMs)
        val map = loadAllRaw(context).toMutableMap()
        map[deviceId] = entry
        saveAllRaw(context, map)

        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = buildPendingIntent(context, entry)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
                }
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        }
    }

    @JvmStatic
    fun cancelSchedule(context: Context, deviceId: String) {
        val map = loadAllRaw(context).toMutableMap()
        val existing = map.remove(deviceId)
        saveAllRaw(context, map)
        if (existing != null) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            am?.cancel(buildPendingIntent(context, existing))
        }
    }

    @JvmStatic
    fun getSchedule(context: Context, deviceId: String): ScheduledOffEntry? {
        val now = System.currentTimeMillis()
        val map = loadAllRaw(context)
        val entry = map[deviceId] ?: return null
        if (entry.triggerAtMs <= now - 60_000L) {
            cancelSchedule(context, deviceId)
            return null
        }
        return entry
    }

    @JvmStatic
    fun formatTriggerTime(triggerAtMs: Long): String {
        val target = Calendar.getInstance().apply { timeInMillis = triggerAtMs }
        val today = Calendar.getInstance()
        val sameDay = target.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            target.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
        val fmt = if (sameDay) {
            SimpleDateFormat("'Today' h:mm a", Locale.US)
        } else {
            SimpleDateFormat("MMM d h:mm a", Locale.US)
        }
        return fmt.format(Date(triggerAtMs))
    }

    private fun buildPendingIntent(context: Context, entry: ScheduledOffEntry): PendingIntent {
        val intent = Intent(context, ScheduledPowerOffReceiver::class.java).apply {
            action = "com.project.autoswitch.ACTION_SCHEDULED_POWER_OFF"
            putExtra("device_id", entry.deviceId)
            putExtra("device_name", entry.deviceName)
            putExtra("home_key", entry.homeKey)
            putExtra("host", entry.host)
            putExtra("port", entry.port)
        }
        val reqCode = entry.deviceId.hashCode() and 0x7FFFFFFF
        return PendingIntent.getBroadcast(
            context,
            reqCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun loadAllRaw(context: Context): Map<String, ScheduledOffEntry> {
        val out = LinkedHashMap<String, ScheduledOffEntry>()
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MAP, null) ?: return out
        try {
            val root = JSONObject(raw)
            val keys = root.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val o = root.optJSONObject(id) ?: continue
                out[id] = ScheduledOffEntry(
                    deviceId = id,
                    deviceName = o.optString("deviceName", id),
                    homeKey = o.optString("homeKey", ""),
                    host = o.optString("host", ""),
                    port = o.optInt("port", 8009),
                    triggerAtMs = o.optLong("triggerAtMs", 0L)
                )
            }
        } catch (_: Exception) {}
        return out
    }

    private fun saveAllRaw(context: Context, map: Map<String, ScheduledOffEntry>) {
        val root = JSONObject()
        for ((id, e) in map) {
            val o = JSONObject()
            o.put("deviceName", e.deviceName)
            o.put("homeKey", e.homeKey)
            o.put("host", e.host)
            o.put("port", e.port)
            o.put("triggerAtMs", e.triggerAtMs)
            root.put(id, o)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MAP, root.toString())
            .apply()
    }
}
