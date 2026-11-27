/**
 * @file NestThermostatBridge.kt
 * @summary Reads Nest Thermostat temperatures via Google Home SDK, evaluates delta/threshold alert rules, fires notifications, and resets target temperatures.
 * @description Shares GoogleHomePowerBridge's HomeClient to inspect Matter Thermostat traits across all structures, tracks per-thermostat baselines in SharedPreferences, and updates occupiedHeatingSetpoint/occupiedCoolingSetpoint.
 * @testing Compiled against play-services-home-types 17.1.0 Thermostat trait signatures via Gradle assembleRelease.
 */
package com.project.autoswitch.ui.nest

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import com.google.home.DeviceType
import com.google.home.HomeDevice
import com.google.home.PermissionsState
import com.google.home.matter.standard.Thermostat
import com.google.home.matter.standard.ThermostatTrait
import com.project.autoswitch.ui.cast.GoogleHomePowerBridge
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

data class NestActiveWarning(
    val key: String,
    val name: String,
    val title: String,
    val body: String,
    val triggeredAtMs: Long,
    val restoreTargetF: Double,
    val notifId: Int
)

data class NestChangeLogEntry(
    val timestampMs: Long,
    val tool: String,
    val change: String,
    val actionTaken: String
)

data class ThermostatSnapshot(
    val key: String,
    val homeName: String,
    val name: String,
    val ambientF: Double?,
    val heatSetpointF: Double?,
    val coolSetpointF: Double?,
    val modeLabel: String
) {
    val activeTargetF: Double?
        get() = when (modeLabel) {
            "COOL" -> coolSetpointF ?: heatSetpointF
            "HEAT", "EMERG HEAT" -> heatSetpointF ?: coolSetpointF
            else -> heatSetpointF ?: coolSetpointF
        }
}

data class NestMonitorRules(
    val enabled: Boolean,
    val deltaDegreesF: Float,
    val minTempF: Float,
    val resetTempF: Float,
    val pollIntervalMin: Int
)

data class NestAlertEvent(
    val notificationIdOffset: Int,
    val title: String,
    val body: String,
    val newAmbientBaselineF: Float? = null,
    val newTargetBaselineF: Float? = null,
    val isLowTempAlert: Boolean = false
)

object NestAlertEvaluator {
    /**
     * Pure deterministic evaluator for Nest thermostat changes.
     * Independently tracks BOTH ambient room temp AND target setpoint so dial/app changes
     * (e.g. First Floor 75°F -> 71°F) trigger immediately without waiting for room temp to drift.
     */
    fun evaluateSingle(
        snap: ThermostatSnapshot,
        rules: NestMonitorRules,
        ambientBaselineF: Float?,
        targetBaselineF: Float?,
        canFireLowAlert: Boolean
    ): Pair<List<NestAlertEvent>, Pair<Float?, Float?>> {
        val events = ArrayList<NestAlertEvent>()
        var nextAmbBase = ambientBaselineF
        var nextTgtBase = targetBaselineF
        val eps = 0.05 // guard floating-point rounding (e.g. 3.999°F for 4°F)

        // 1. Target setpoint delta check (catches immediate dial/app changes like 75 -> 71)
        val curTarget = snap.activeTargetF
        if (curTarget != null) {
            if (targetBaselineF == null) {
                nextTgtBase = curTarget.toFloat()
            } else {
                val diff = curTarget - targetBaselineF.toDouble()
                if (abs(diff) + eps >= rules.deltaDegreesF.toDouble()) {
                    val dir = if (diff > 0) "raised" else "lowered"
                    val msg = String.format(
                        Locale.US,
                        "%s target %s by %.1f°F (%.1f°F -> %.1f°F)",
                        snap.name, dir, abs(diff), targetBaselineF, curTarget
                    )
                    events.add(
                        NestAlertEvent(
                            notificationIdOffset = 2000,
                            title = "Nest Target Changed: ${snap.name}",
                            body = msg,
                            newTargetBaselineF = curTarget.toFloat()
                        )
                    )
                    nextTgtBase = curTarget.toFloat()
                }
            }
        }

        // 2. Ambient room temperature delta check
        val curAmbient = snap.ambientF
        if (curAmbient != null) {
            if (ambientBaselineF == null) {
                nextAmbBase = curAmbient.toFloat()
            } else {
                val diff = curAmbient - ambientBaselineF.toDouble()
                if (abs(diff) + eps >= rules.deltaDegreesF.toDouble()) {
                    val dir = if (diff > 0) "rose" else "dropped"
                    val msg = String.format(
                        Locale.US,
                        "%s room temp %s by %.1f°F (%.1f°F -> %.1f°F)",
                        snap.name, dir, abs(diff), ambientBaselineF, curAmbient
                    )
                    events.add(
                        NestAlertEvent(
                            notificationIdOffset = 0,
                            title = "Nest Temp Changed: ${snap.name}",
                            body = msg,
                            newAmbientBaselineF = curAmbient.toFloat()
                        )
                    )
                    nextAmbBase = curAmbient.toFloat()
                }
            }
        }

        // 3. Low temperature threshold check (ambient or target <= minTempF)
        val lowest = listOfNotNull(curAmbient, curTarget).minOrNull()
        if (lowest != null && lowest <= rules.minTempF.toDouble() + eps && canFireLowAlert) {
            val which = if (curAmbient != null && curAmbient <= rules.minTempF.toDouble() + eps) "room temp" else "target"
            val msg = String.format(
                Locale.US,
                "%s %s is %.1f°F (at or below %.1f°F limit)",
                snap.name, which, lowest, rules.minTempF
            )
            events.add(
                NestAlertEvent(
                    notificationIdOffset = 1000,
                    title = "Low Temp Alert: ${snap.name}",
                    body = msg,
                    isLowTempAlert = true
                )
            )
        }

        return events to (nextAmbBase to nextTgtBase)
    }
}

object NestThermostatBridge {
    private const val PREFS_NAME = "NestMonitorPrefs"
    private const val KEY_ENABLED = "monitor_enabled"
    private const val KEY_DELTA_F = "delta_degrees_f"
    private const val KEY_MIN_TEMP_F = "min_temp_f"
    private const val KEY_RESET_TEMP_F = "reset_temp_f"
    private const val KEY_POLL_MIN = "poll_interval_min"
    private const val KEY_ACTIVE_WARNINGS = "active_warnings_v1"
    private const val CHANGE_LOG_FILE = "nest_change_log.json"
    private const val FOLLOWUP_INTERVAL_MS = 15L * 60_000L
    private const val SNOOZE_ONE_DAY_MS = 24L * 60L * 60_000L
    private const val CHANNEL_ALERTS = "nest_temp_alerts_critical_v2"
    private val CRITICAL_VIBRATION_PATTERN = longArrayOf(0, 650, 180, 650, 180, 900, 220, 900)
    private val CRITICAL_VIBRATION_AMPS = intArrayOf(0, 255, 0, 255, 0, 255, 0, 255)

    private val devicesByKey = ConcurrentHashMap<String, HomeDevice>()
    private val lastLowAlertMs = ConcurrentHashMap<String, Long>()

    private val errHandler = CoroutineExceptionHandler { _, _ -> }

    fun loadRules(context: Context): NestMonitorRules {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return NestMonitorRules(
            enabled = p.getBoolean(KEY_ENABLED, false),
            deltaDegreesF = p.getFloat(KEY_DELTA_F, 2.0f),
            minTempF = p.getFloat(KEY_MIN_TEMP_F, 62.0f),
            resetTempF = p.getFloat(KEY_RESET_TEMP_F, 70.0f),
            pollIntervalMin = p.getInt(KEY_POLL_MIN, 5).coerceIn(1, 60)
        )
    }

    fun saveRules(context: Context, rules: NestMonitorRules) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, rules.enabled)
            .putFloat(KEY_DELTA_F, rules.deltaDegreesF.coerceIn(0.5f, 30f))
            .putFloat(KEY_MIN_TEMP_F, rules.minTempF.coerceIn(32f, 95f))
            .putFloat(KEY_RESET_TEMP_F, rules.resetTempF.coerceIn(50f, 85f))
            .putInt(KEY_POLL_MIN, rules.pollIntervalMin.coerceIn(1, 60))
            .apply()
    }

    fun getBaselineF(context: Context, key: String): Float? {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefKey = "baseline_$key"
        return if (p.contains(prefKey)) p.getFloat(prefKey, Float.NaN).takeIf { !it.isNaN() } else null
    }

    fun setBaselineF(context: Context, key: String, tempF: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putFloat("baseline_$key", tempF)
            .apply()
    }

    fun getTargetBaselineF(context: Context, key: String): Float? {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefKey = "baseline_target_$key"
        return if (p.contains(prefKey)) p.getFloat(prefKey, Float.NaN).takeIf { !it.isNaN() } else null
    }

    fun setTargetBaselineF(context: Context, key: String, tempF: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putFloat("baseline_target_$key", tempF)
            .apply()
    }

    fun centiCToF(centiC: Short?): Double? {
        if (centiC == null) return null
        val c = centiC.toDouble() / 100.0
        return (c * 9.0 / 5.0) + 32.0
    }

    fun fToCentiC(tempF: Double): Short {
        val c = (tempF - 32.0) * 5.0 / 9.0
        return (c * 100.0).roundToInt().toShort()
    }

    private suspend fun collectEndpoints(device: HomeDevice): List<DeviceType> {
        val out = LinkedHashSet<DeviceType>()
        try {
            val top = withTimeout(3_500) { device.types().first() }
            for (dt in top) {
                out.add(dt)
                try {
                    for (sub in dt.parts) {
                        out.add(sub)
                        try { out.addAll(sub.parts) } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        return out.toList()
    }

    private fun findThermostatTrait(endpoints: List<DeviceType>): Thermostat? {
        for (dt in endpoints) {
            try {
                val t = dt.trait(Thermostat)
                if (t != null) return t
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun modeToString(mode: ThermostatTrait.SystemModeEnum?): String = when (mode) {
        ThermostatTrait.SystemModeEnum.Heat -> "HEAT"
        ThermostatTrait.SystemModeEnum.Cool -> "COOL"
        ThermostatTrait.SystemModeEnum.Auto -> "AUTO"
        ThermostatTrait.SystemModeEnum.Off -> "OFF"
        ThermostatTrait.SystemModeEnum.EmergencyHeat -> "EMERG HEAT"
        ThermostatTrait.SystemModeEnum.FanOnly -> "FAN"
        else -> "UNKNOWN"
    }

    suspend fun fetchThermostats(context: Context): Pair<List<ThermostatSnapshot>, String?> {
        return try {
            val hc = GoogleHomePowerBridge.getOrCreateClient(context)
            val perm = withTimeout(8_000) {
                hc.hasPermissions().first { it != PermissionsState.PERMISSIONS_STATE_UNINITIALIZED }
            }
            if (perm != PermissionsState.GRANTED) {
                return emptyList<ThermostatSnapshot>() to "Connect Google Home in Cast Device Controls first."
            }
            val structures = withTimeout(12_000) { hc.structures().first() }
            val result = ArrayList<ThermostatSnapshot>()
            for (s in structures) {
                val devs = try {
                    withTimeout(12_000) { s.devices().first() }
                } catch (_: Throwable) {
                    continue
                }
                val found = coroutineScope {
                    devs.map { d ->
                        async {
                            val endpoints = collectEndpoints(d)
                            val trait = findThermostatTrait(endpoints) ?: return@async null
                            val key = "nest::${s.name}::${d.name}"
                            devicesByKey[key] = d
                            val amb = centiCToF(trait.localTemperature)
                            val heat = centiCToF(trait.occupiedHeatingSetpoint)
                            val cool = centiCToF(trait.occupiedCoolingSetpoint)
                            val mode = modeToString(trait.systemMode)
                            ThermostatSnapshot(
                                key = key,
                                homeName = s.name,
                                name = d.name,
                                ambientF = amb,
                                heatSetpointF = heat,
                                coolSetpointF = cool,
                                modeLabel = mode
                            )
                        }
                    }.awaitAll().filterNotNull()
                }
                result.addAll(found)
            }
            result.sortBy { it.name.lowercase(Locale.US) }
            result to null
        } catch (t: Throwable) {
            emptyList<ThermostatSnapshot>() to (t.message ?: t.javaClass.simpleName)
        }
    }

    fun syncThermostats(
        activity: ComponentActivity,
        autoPrompt: Boolean = true,
        forceReconsent: Boolean = false,
        onResult: (List<ThermostatSnapshot>, String?) -> Unit
    ) {
        activity.lifecycleScope.launch(Dispatchers.IO + errHandler) {
            try {
                val hc = GoogleHomePowerBridge.getOrCreateClient(activity)
                val permErr = GoogleHomePowerBridge.requestConsentIfNeeded(
                    activity = activity,
                    hc = hc,
                    allowPrompt = autoPrompt,
                    forceLaunch = forceReconsent
                )
                if (permErr != null) {
                    activity.runOnUiThread { onResult(emptyList(), permErr) }
                    return@launch
                }
                var (list, err) = fetchThermostats(activity)
                if (err == null && list.isEmpty() && autoPrompt && !forceReconsent) {
                    val reauthErr = GoogleHomePowerBridge.requestConsentIfNeeded(
                        activity = activity,
                        hc = hc,
                        allowPrompt = true,
                        forceLaunch = true
                    )
                    if (reauthErr != null) {
                        activity.runOnUiThread { onResult(emptyList(), reauthErr) }
                        return@launch
                    }
                    val retry = fetchThermostats(activity)
                    list = retry.first
                    err = retry.second
                }
                if (err == null && list.isNotEmpty()) {
                    val rules = loadRules(activity)
                    evaluateAlerts(activity, list, fireNotifications = rules.enabled)
                }
                activity.runOnUiThread { onResult(list, err) }
            } catch (t: Throwable) {
                activity.runOnUiThread { onResult(emptyList(), t.message ?: t.javaClass.simpleName) }
            }
        }
    }

    suspend fun setTargetTemperatureFromBackground(context: Context, key: String, targetF: Double): Pair<Boolean, String> {
        return try {
            var dev = devicesByKey[key]
            if (dev == null) {
                fetchThermostats(context)
                dev = devicesByKey[key]
            }
            if (dev == null) return false to "Thermostat not found"
            val endpoints = collectEndpoints(dev)
            val trait = findThermostatTrait(endpoints) ?: return false to "Thermostat trait unavailable on ${dev.name}"
            val clampedF = targetF.coerceIn(50.0, 85.0)
            val centiC = fToCentiC(clampedF)
            val mode = trait.systemMode
            trait.update {
                when (mode) {
                    ThermostatTrait.SystemModeEnum.Cool -> setOccupiedCoolingSetpoint(centiC)
                    ThermostatTrait.SystemModeEnum.Auto -> {
                        setOccupiedHeatingSetpoint(centiC)
                        val curCool = trait.occupiedCoolingSetpoint
                        if (curCool != null && curCool <= centiC + 150) {
                            setOccupiedCoolingSetpoint((centiC + 200).toShort())
                        }
                    }
                    ThermostatTrait.SystemModeEnum.Off -> {
                        setSystemMode(ThermostatTrait.SystemModeEnum.Heat)
                        setOccupiedHeatingSetpoint(centiC)
                    }
                    else -> setOccupiedHeatingSetpoint(centiC)
                }
            }
            setTargetBaselineF(context, key, clampedF.toFloat())
            val formatted = String.format(Locale.US, "%.1f°F", clampedF)
            GoogleHomePowerBridge.logDiag(context, "Nest '${dev.name}' set to $formatted")
            true to "${dev.name} set to $formatted"
        } catch (t: Throwable) {
            val msg = t.message ?: t.javaClass.simpleName
            GoogleHomePowerBridge.logDiag(context, "Nest setTargetTemperature error: $msg")
            false to "Failed: $msg"
        }
    }

    fun setTargetTemperature(
        activity: ComponentActivity,
        key: String,
        targetF: Double,
        actionLabel: String = "Manual Set",
        onResult: (Boolean, String) -> Unit
    ) {
        activity.lifecycleScope.launch(Dispatchers.IO + errHandler) {
            val (ok, msg) = setTargetTemperatureFromBackground(activity, key, targetF)
            val devName = devicesByKey[key]?.name ?: key.substringAfterLast("::")
            appendChangeLog(
                context = activity,
                tool = devName,
                change = String.format(Locale.US, "Target -> %.1f°F", targetF.coerceIn(50.0, 85.0)),
                actionTaken = if (ok) actionLabel else "Failed: $msg"
            )
            activity.runOnUiThread { onResult(ok, msg) }
        }
    }

    // MARK: - Snooze (24h), Active Warnings, 15m Follow-Up Buzz, & Change Log

    fun snoozeForOneDay(context: Context, key: String) {
        val until = System.currentTimeMillis() + SNOOZE_ONE_DAY_MS
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong("snooze_until_$key", until)
            .apply()
    }

    fun clearSnooze(context: Context, key: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove("snooze_until_$key")
            .apply()
    }

    fun isSnoozed(context: Context, key: String): Boolean {
        val until = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong("snooze_until_$key", 0L)
        return System.currentTimeMillis() < until
    }

    fun getSnoozeUntilMs(context: Context, key: String): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong("snooze_until_$key", 0L)

    fun saveActiveWarning(context: Context, warning: NestActiveWarning) {
        val map = loadActiveWarningsMap(context).toMutableMap()
        map[warning.key] = warning
        saveActiveWarningsMap(context, map)
    }

    fun getActiveWarning(context: Context, key: String): NestActiveWarning? =
        loadActiveWarningsMap(context)[key]

    fun getAllActiveWarnings(context: Context): List<NestActiveWarning> =
        loadActiveWarningsMap(context).values.sortedByDescending { it.triggeredAtMs }

    fun clearActiveWarning(context: Context, key: String) {
        val map = loadActiveWarningsMap(context).toMutableMap()
        val removed = map.remove(key)
        saveActiveWarningsMap(context, map)
        cancelFollowupBuzz(context, key)
        if (removed != null) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(removed.notifId)
        }
    }

    private fun loadActiveWarningsMap(context: Context): Map<String, NestActiveWarning> {
        val out = LinkedHashMap<String, NestActiveWarning>()
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_WARNINGS, null) ?: return out
        try {
            val root = JSONObject(raw)
            val keys = root.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val o = root.optJSONObject(k) ?: continue
                out[k] = NestActiveWarning(
                    key = k,
                    name = o.optString("name", k),
                    title = o.optString("title", "Nest Alert"),
                    body = o.optString("body", ""),
                    triggeredAtMs = o.optLong("triggeredAtMs", 0L),
                    restoreTargetF = o.optDouble("restoreTargetF", 70.0),
                    notifId = o.optInt("notifId", k.hashCode())
                )
            }
        } catch (_: Exception) {}
        return out
    }

    private fun saveActiveWarningsMap(context: Context, map: Map<String, NestActiveWarning>) {
        val root = JSONObject()
        for ((k, w) in map) {
            root.put(k, JSONObject().apply {
                put("name", w.name)
                put("title", w.title)
                put("body", w.body)
                put("triggeredAtMs", w.triggeredAtMs)
                put("restoreTargetF", w.restoreTargetF)
                put("notifId", w.notifId)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_ACTIVE_WARNINGS, root.toString())
            .apply()
    }

    private fun followupPendingIntent(context: Context, key: String, name: String, notifId: Int, restoreTargetF: Double): PendingIntent {
        val intent = Intent(context, NestAlertActionReceiver::class.java).apply {
            action = NestAlertActionReceiver.ACTION_FOLLOWUP_BUZZ
            putExtra("thermostat_key", key)
            putExtra("thermostat_name", name)
            putExtra("notif_id", notifId)
            putExtra("restore_target_f", restoreTargetF)
        }
        val reqCode = ("followup::$key").hashCode() and 0x7FFFFFFF
        return PendingIntent.getBroadcast(
            context, reqCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun scheduleFollowupBuzz(context: Context, key: String, name: String, notifId: Int, restoreTargetF: Double) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAt = System.currentTimeMillis() + FOLLOWUP_INTERVAL_MS
        val pi = followupPendingIntent(context, key, name, notifId, restoreTargetF)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    fun cancelFollowupBuzz(context: Context, key: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = followupPendingIntent(context, key, "", key.hashCode(), 70.0)
        am.cancel(pi)
    }

    @Synchronized
    fun appendChangeLog(context: Context, tool: String, change: String, actionTaken: String) {
        try {
            val file = File(context.filesDir, CHANGE_LOG_FILE)
            val existing = if (file.exists()) {
                runCatching { JSONArray(file.readText()) }.getOrElse { JSONArray() }
            } else JSONArray()
            val entry = JSONObject().apply {
                put("ts", System.currentTimeMillis())
                put("tool", tool)
                put("change", change)
                put("action", actionTaken)
            }
            val next = JSONArray()
            next.put(entry)
            val maxItems = minOf(existing.length(), 199)
            for (i in 0 until maxItems) next.put(existing.get(i))
            file.writeText(next.toString())
        } catch (_: Throwable) {}
    }

    @Synchronized
    fun readChangeLog(context: Context): List<NestChangeLogEntry> {
        val out = ArrayList<NestChangeLogEntry>()
        try {
            val file = File(context.filesDir, CHANGE_LOG_FILE)
            if (!file.exists()) return out
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    NestChangeLogEntry(
                        timestampMs = o.optLong("ts", 0L),
                        tool = o.optString("tool", "Nest"),
                        change = o.optString("change", ""),
                        actionTaken = o.optString("action", "")
                    )
                )
            }
        } catch (_: Throwable) {}
        return out
    }

    @Synchronized
    fun clearChangeLog(context: Context) {
        try { File(context.filesDir, CHANGE_LOG_FILE).delete() } catch (_: Throwable) {}
    }

    fun formatChangeLogText(entries: List<NestChangeLogEntry>): String {
        if (entries.isEmpty()) return "No Nest temperature changes or actions logged yet."
        val fmt = SimpleDateFormat("MMM d h:mm:ss a", Locale.US)
        return entries.joinToString("\n") { e ->
            "[${fmt.format(Date(e.timestampMs))}] ${e.tool} | ${e.change} | Action: ${e.actionTaken}"
        }
    }

    /**
     * Evaluates all thermostats against user-configured delta and low-temp threshold rules.
     * Tracks BOTH ambient room temperature and target setpoint independently.
     * Never silently overwrites baselines when fireNotifications is false!
     */
    fun evaluateAlerts(context: Context, snapshots: List<ThermostatSnapshot>, fireNotifications: Boolean): List<String> {
        val rules = loadRules(context)
        val triggered = ArrayList<String>()
        val now = System.currentTimeMillis()

        for (snap in snapshots) {
            val ambBase = getBaselineF(context, snap.key)
            val tgtBase = getTargetBaselineF(context, snap.key)
            val lastLow = lastLowAlertMs[snap.key] ?: 0L
            val canFireLow = (now - lastLow) >= 30 * 60_000L

            GoogleHomePowerBridge.logDiag(
                context,
                String.format(
                    Locale.US,
                    "Nest poll '%s': amb=%s (base=%s) tgt=%s (base=%s) mode=%s",
                    snap.name,
                    snap.ambientF?.let { String.format(Locale.US, "%.1f°F", it) } ?: "null",
                    ambBase?.let { String.format(Locale.US, "%.1f°F", it) } ?: "none",
                    snap.activeTargetF?.let { String.format(Locale.US, "%.1f°F", it) } ?: "null",
                    tgtBase?.let { String.format(Locale.US, "%.1f°F", it) } ?: "none",
                    snap.modeLabel
                )
            )

            val (events, nextBases) = NestAlertEvaluator.evaluateSingle(
                snap = snap,
                rules = rules,
                ambientBaselineF = ambBase,
                targetBaselineF = tgtBase,
                canFireLowAlert = canFireLow
            )

            // Always seed initial baselines if missing
            if (ambBase == null && nextBases.first != null) {
                setBaselineF(context, snap.key, nextBases.first!!)
            }
            if (tgtBase == null && nextBases.second != null) {
                setTargetBaselineF(context, snap.key, nextBases.second!!)
            }

            for (ev in events) {
                triggered.add(ev.body)
                if (fireNotifications && rules.enabled && !isSnoozed(context, snap.key)) {
                    if (ev.newAmbientBaselineF != null) {
                        setBaselineF(context, snap.key, ev.newAmbientBaselineF)
                    }
                    if (ev.newTargetBaselineF != null) {
                        setTargetBaselineF(context, snap.key, ev.newTargetBaselineF)
                    }
                    if (ev.isLowTempAlert) {
                        lastLowAlertMs[snap.key] = now
                    }
                    val notifId = snap.key.hashCode() + ev.notificationIdOffset
                    val restoreTarget = rules.resetTempF.toDouble()
                    saveActiveWarning(
                        context,
                        NestActiveWarning(
                            key = snap.key,
                            name = snap.name,
                            title = ev.title,
                            body = ev.body,
                            triggeredAtMs = now,
                            restoreTargetF = restoreTarget,
                            notifId = notifId
                        )
                    )
                    appendChangeLog(
                        context = context,
                        tool = snap.name,
                        change = ev.body,
                        actionTaken = "Critical Alert Fired (15m follow-up armed)"
                    )
                    GoogleHomePowerBridge.logDiag(context, "Nest ALERT fired: ${ev.title} — ${ev.body}")
                    postAlertNotification(
                        context = context,
                        id = notifId,
                        title = ev.title,
                        body = ev.body,
                        thermostatKey = snap.key,
                        thermostatName = snap.name,
                        restoreTargetF = restoreTarget,
                        scheduleFollowup = true
                    )
                }
            }
        }
        return triggered
    }

    fun ensureAlertChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ALERTS) == null) {
                val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val audioAttrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val ch = NotificationChannel(
                    CHANNEL_ALERTS,
                    "Nest Thermostat Critical Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Critical alarm buzz & sound when a Nest thermostat changes temperature or drops below your threshold."
                    enableVibration(true)
                    vibrationPattern = CRITICAL_VIBRATION_PATTERN
                    enableLights(true)
                    lightColor = Color.RED
                    setBypassDnd(true)
                    setSound(alarmUri, audioAttrs)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun triggerCriticalBuzz(context: Context) {
        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator == null || !vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (vibrator.hasAmplitudeControl()) {
                    VibrationEffect.createWaveform(CRITICAL_VIBRATION_PATTERN, CRITICAL_VIBRATION_AMPS, -1)
                } else {
                    VibrationEffect.createWaveform(CRITICAL_VIBRATION_PATTERN, -1)
                }
                @Suppress("DEPRECATION")
                vibrator.vibrate(
                    effect,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(CRITICAL_VIBRATION_PATTERN, -1)
            }
        } catch (_: Throwable) {
        }
    }

    fun postAlertNotification(
        context: Context,
        id: Int,
        title: String,
        body: String,
        thermostatKey: String = "test",
        thermostatName: String = "Nest Thermostat",
        restoreTargetF: Double = loadRules(context).resetTempF.toDouble(),
        scheduleFollowup: Boolean = false
    ) {
        ensureAlertChannel(context)
        triggerCriticalBuzz(context)
        val intent = Intent(context, NestMonitorActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun actionPi(action: String, codeOffset: Int): PendingIntent {
            val i = Intent(context, NestAlertActionReceiver::class.java).apply {
                this.action = action
                putExtra("thermostat_key", thermostatKey)
                putExtra("thermostat_name", thermostatName)
                putExtra("notif_id", id)
                putExtra("restore_target_f", restoreTargetF)
            }
            return PendingIntent.getBroadcast(
                context,
                ((id + codeOffset) and 0x7FFFFFFF),
                i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val restoreLabel = String.format(Locale.US, "Restore %.0f°F", restoreTargetF)
        val notif = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setVibrate(CRITICAL_VIBRATION_PATTERN)
            .setSound(alarmUri)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .addAction(0, restoreLabel, actionPi(NestAlertActionReceiver.ACTION_RESTORE_TARGET, 11))
            .addAction(0, "OK / Dismiss", actionPi(NestAlertActionReceiver.ACTION_DISMISS_OK, 22))
            .addAction(0, "Snooze 1d", actionPi(NestAlertActionReceiver.ACTION_SNOOZE_DAY, 33))
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(id, notif)
        } catch (_: SecurityException) {
        }
        if (scheduleFollowup && thermostatKey != "test") {
            scheduleFollowupBuzz(context, thermostatKey, thermostatName, id, restoreTargetF)
        }
    }
}
