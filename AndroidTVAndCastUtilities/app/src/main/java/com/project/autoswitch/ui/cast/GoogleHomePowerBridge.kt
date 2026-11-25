/**
 * @file GoogleHomePowerBridge.kt
 * @summary Full Google Home SDK device discovery and hardware OnOff power bridge (v3.0) for TVs, projectors, displays, speakers, lights, plugs, switches, and fans.
 * @description Treats any present OnOff trait (onOff != null) as controllable so cloud-fulfilled Google TVs/CastingVideoPlayers execute real onOff.off()/on() commands, fixes ConsentScreenOptions structureId rules, and exposes linkable Home power targets.
 * @testing Compiled against Kotlin 2.4.0 stdlib + kotlinx-coroutines 1.11.0 + play-services-home 17.1.0 via Gradle assembleRelease (v3.0).
 */
package com.project.autoswitch.ui.cast

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.home.ConsentScreenOptions
import com.google.home.FactoryRegistry
import com.google.home.ForcePermissionFlow
import com.google.home.Home
import com.google.home.HomeClient
import com.google.home.HomeConfig
import com.google.home.HomeDevice
import com.google.home.PermissionsResultStatus
import com.google.home.PermissionsState
import com.google.home.google.*
import com.google.home.matter.standard.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

object GoogleHomePowerBridge {

    class HomeDeviceSnapshot(
        @JvmField val key: String,
        @JvmField val homeName: String,
        @JvmField val name: String,
        @JvmField val typeSummary: String,
        @JvmField val category: String,
        @JvmField val supportsOnOff: Boolean,
        @JvmField val powerOn: Boolean
    )

    fun interface PowerCallback {
        fun onResult(success: Boolean, message: String)
    }

    fun interface HomeSyncCallback {
        fun onSynced(devices: List<HomeDeviceSnapshot>, statusNote: String?)
    }

    private const val CRASH_FILE = "last_crash.txt"
    private const val DIAG_FILE = "cast_diag.txt"

    private val consentInFlight = AtomicBoolean(false)
    private val devicesByKey = ConcurrentHashMap<String, HomeDevice>()
    private val devicesByLowerName = ConcurrentHashMap<String, HomeDevice>()
    private val latestSnapshots = CopyOnWriteArrayList<HomeDeviceSnapshot>()

    @Volatile private var sharedClient: HomeClient? = null
    @Volatile private var lastSyncMs = 0L

    private val homeCoroutineHandler = CoroutineExceptionHandler { _, throwable ->
        val _ignored = throwable.message
    }

    private fun buildRegistry(): FactoryRegistry = FactoryRegistry(
        types = listOf(
            CastingVideoPlayerDevice,
            GoogleTVDevice,
            GoogleDisplayDevice,
            GoogleCameraDevice,
            GoogleDoorbellDevice,
            SpeakerDevice,
            OnOffLightDevice,
            OnOffPluginUnitDevice,
            OnOffLightSwitchDevice,
            OnOffSensorDevice,
            DimmableLightDevice,
            ExtendedColorLightDevice,
            ColorTemperatureLightDevice,
            ThermostatDevice,
            FanDevice,
            GenericSwitchDevice,
            ContactSensorDevice,
            OccupancySensorDevice,
            DoorLockDevice,
            LightSensorDevice,
            TemperatureSensorDevice,
            WindowCoveringDevice,
            RootNodeDevice,
            ChimeDevice,
            OtaRequestorDevice
        ),
        traits = listOf(
            OnOff,
            MediaPlayback,
            MediaInput,
            BasicInformation,
            AudioOutput,
            LevelControl,
            KeypadInput,
            ContentLauncher,
            Messages,
            WakeOnLan,
            Channel,
            TargetNavigator,
            LowPower,
            ApplicationLauncher,
            AccountLogin,
            FanControl,
            Thermostat,
            TemperatureMeasurement,
            TemperatureControl,
            WindowCovering,
            IlluminanceMeasurement,
            OccupancySensing,
            BooleanState,
            DoorLock,
            Volume,
            ExtendedMediaPlayback,
            ExtendedBasicInformation,
            ExtendedLevelControl,
            ExtendedMediaInput,
            ExtendedApplicationLauncher,
            MediaActivityState,
            Assistant,
            AssistantBroadcast,
            AssistantFulfillment,
            SearchableHome,
            VoiceStarter,
            Time
        )
    )

    @JvmStatic
    fun installCrashCapture(context: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev is SafeCrashHandler) return
        Thread.setDefaultUncaughtExceptionHandler(SafeCrashHandler(context.applicationContext, prev))
    }

    private class SafeCrashHandler(
        private val appContext: Context,
        private val previous: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val ver = getAppVersionLabel(appContext)
                val text = "Last crash ($ver): $stamp\nThread: ${t.name}\n${e.javaClass.name}: ${e.message}\n$sw"
                File(appContext.filesDir, CRASH_FILE).writeText(text)
            } catch (_: Throwable) {
            }
            if (previous != null) {
                previous.uncaughtException(t, e)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(10)
            }
        }
    }

    @JvmStatic
    fun logDiag(context: Context, msg: String) {
        try {
            val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val f = File(context.filesDir, DIAG_FILE)
            val prev = if (f.exists()) f.readText() else ""
            val next = (prev + "[$stamp] $msg\n").takeLast(32768)
            f.writeText(next)
        } catch (_: Throwable) {
        }
    }

    @JvmStatic
    fun clearDiagnosticLogs(context: Context) {
        try { File(context.filesDir, DIAG_FILE).delete() } catch (_: Throwable) {}
        try { File(context.filesDir, CRASH_FILE).delete() } catch (_: Throwable) {}
    }

    @JvmStatic
    fun getAppVersionLabel(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val vName = pInfo.versionName ?: "?"
            @Suppress("DEPRECATION")
            val vCode = if (android.os.Build.VERSION.SDK_INT >= 28) pInfo.longVersionCode else pInfo.versionCode.toLong()
            "v$vName (build $vCode)"
        } catch (_: Throwable) {
            "v3.0"
        }
    }

    @JvmStatic
    fun readDiagnosticReport(context: Context): String {
        val ver = getAppVersionLabel(context)
        val diag = try {
            val f = File(context.filesDir, DIAG_FILE)
            if (f.exists()) f.readText() else "No diagnostic entries yet."
        } catch (_: Throwable) { "Could not read diag log." }
        val crash = try {
            val f = File(context.filesDir, CRASH_FILE)
            if (f.exists()) f.readText() else "No crash recorded."
        } catch (_: Throwable) { "Could not read crash log." }
        return "AndroidTV & Cast Utilities $ver\nPackage: ${context.packageName}\n\n=== Diagnostic Log ===\n$diag\n\n=== Last Crash ===\n$crash"
    }

    @JvmStatic
    fun copyToClipboard(context: Context, label: String, text: String) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
        } catch (_: Throwable) {
        }
    }

    @JvmStatic
    fun getControllableCandidates(): List<HomeDeviceSnapshot> {
        val list = ArrayList<HomeDeviceSnapshot>()
        for (s in latestSnapshots) {
            if (s.supportsOnOff) list.add(s)
        }
        // Sort TVs/Projectors/Displays first so linking a Cast TV puts TVs right at the top
        list.sortWith { a, b ->
            val rankA = when (a.category) {
                "TV", "PROJECTOR", "DISPLAY" -> 0
                "PLUG", "SWITCH" -> 1
                else -> 2
            }
            val rankB = when (b.category) {
                "TV", "PROJECTOR", "DISPLAY" -> 0
                "PLUG", "SWITCH" -> 1
                else -> 2
            }
            if (rankA != rankB) rankA - rankB else a.name.compareTo(b.name, ignoreCase = true)
        }
        return list
    }

    @Synchronized
    private fun getOrCreateClient(context: Context): HomeClient {
        sharedClient?.let { return it }
        val config = HomeConfig(
            coroutineContext = SupervisorJob() + Dispatchers.IO + homeCoroutineHandler,
            factoryRegistry = buildRegistry(),
            homePlatformScope = HomeConfig.HomePlatformScope.HOME_PLATFORM_SCOPE_VERSION_1
        )
        return Home.getClient(context.applicationContext, homeConfig = config).also {
            sharedClient = it
        }
    }

    @JvmStatic
    @Synchronized
    fun registerActivity(activity: ComponentActivity) {
        installCrashCapture(activity)
        try {
            val hc = getOrCreateClient(activity)
            hc.registerActivityResultCallerForPermissions(activity)
            logDiag(activity, "HomeClient registered (${getAppVersionLabel(activity)})")
        } catch (t: Throwable) {
            logDiag(activity, "HomeClient register failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    @JvmStatic
    fun unregisterActivity(activity: ComponentActivity) {
        // Keep sharedClient alive across consent/activity transitions.
    }

    private suspend fun findOnOff(device: HomeDevice): OnOff? {
        val tries: List<suspend () -> OnOff?> = listOf(
            { withTimeout(3_000) { device.typeOrNull(CastingVideoPlayerDevice).firstOrNull() }?.standardTraits?.onOff },
            { withTimeout(3_000) { device.typeOrNull(GoogleTVDevice).firstOrNull() }?.standardTraits?.onOff },
            { withTimeout(3_000) { device.typeOrNull(GoogleDisplayDevice).firstOrNull() }?.standardTraits?.onOff },
            { withTimeout(3_000) { device.typeOrNull(OnOffLightDevice).firstOrNull() }?.standardTraits?.onOff },
            { withTimeout(3_000) { device.typeOrNull(DimmableLightDevice).firstOrNull() }?.standardTraits?.onOff },
            { withTimeout(3_000) { device.typeOrNull(ExtendedColorLightDevice).firstOrNull() }?.standardTraits?.onOff },
            { withTimeout(3_000) { device.typeOrNull(ColorTemperatureLightDevice).firstOrNull() }?.standardTraits?.onOff },
            { withTimeout(3_000) { device.typeOrNull(OnOffPluginUnitDevice).firstOrNull() }?.standardTraits?.onOff },
            { withTimeout(3_000) { device.typeOrNull(FanDevice).firstOrNull() }?.standardTraits?.onOff }
        )
        for (t in tries) {
            try {
                val v = t()
                if (v != null) return v
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun classifyTypes(typeNames: List<String>, devName: String): Pair<String, String> {
        val joined = (typeNames.joinToString(" ") + " " + devName).lowercase(Locale.US)
        return when {
            joined.contains("projector") || joined.contains("fengmi") || joined.contains("xgimi") || joined.contains("formovie") ->
                "PROJECTOR" to "Projector"
            typeNames.any { it.contains("GoogleTV") } ->
                "TV" to "Google TV"
            typeNames.any { it.contains("CastingVideoPlayer") } ->
                "TV" to "TV"
            typeNames.any { it.contains("GoogleDisplay") } ->
                "DISPLAY" to "Smart Display"
            typeNames.any { it.contains("Speaker") } ->
                "SPEAKER" to "Speaker"
            typeNames.any { it.contains("Color") || it.contains("Dimmable") || it.contains("OnOffLight") } ->
                "LIGHT" to "Smart Light"
            typeNames.any { it.contains("PluginUnit") } ->
                "PLUG" to "Smart Plug"
            typeNames.any { it.contains("Switch") } ->
                "SWITCH" to "Wall Switch"
            typeNames.any { it.contains("Fan") } ->
                "FAN" to "Fan"
            typeNames.any { it.contains("Thermostat") } ->
                "THERMOSTAT" to "Thermostat"
            typeNames.any { it.contains("DoorLock") } ->
                "LOCK" to "Smart Lock"
            typeNames.any { it.contains("Camera") || it.contains("Doorbell") } ->
                "CAMERA" to "Camera"
            typeNames.any { it.contains("Sensor") } ->
                "SENSOR" to "Sensor"
            typeNames.any { it.contains("WindowCovering") } ->
                "SHADE" to "Window Shade"
            else -> "OTHER" to (typeNames.firstOrNull()?.replace("Device", "") ?: "Home Device")
        }
    }

    private suspend fun requestConsentIfNeeded(
        activity: ComponentActivity,
        hc: HomeClient,
        allowPrompt: Boolean,
        forceLaunch: Boolean = false
    ): String? {
        val permState = withTimeout(10_000) {
            hc.hasPermissions().first { it != PermissionsState.PERMISSIONS_STATE_UNINITIALIZED }
        }
        if (!forceLaunch) {
            if (permState == PermissionsState.GRANTED) return null
            if (!allowPrompt) return "Tap 'Connect / Manage Google Home Devices' under the title"
        }
        if (!consentInFlight.compareAndSet(false, true)) return "Google Home selector already open"
        return try {
            // Google Home SDK rule: isAllowStructureChange=true REQUIRES a non-null structureId!
            val existingStructId = if (permState == PermissionsState.GRANTED) {
                try {
                    withTimeout(5_000) { hc.structures().first().firstOrNull()?.id?.id }
                } catch (_: Throwable) { null }
            } else null

            val options = if (!existingStructId.isNullOrEmpty()) {
                ConsentScreenOptions(
                    structureId = existingStructId,
                    allowedStructureIds = emptyList(),
                    isAllowStructureChange = true
                )
            } else {
                ConsentScreenOptions(
                    structureId = null,
                    allowedStructureIds = emptyList(),
                    isAllowStructureChange = false
                )
            }

            val res = withContext(Dispatchers.Main) {
                hc.requestPermissions(ForcePermissionFlow.FORCE_LAUNCH, consentScreenOptions = options)
            }
            logDiag(activity, "Consent result: ${res.status} ${res.errorMessage ?: ""}")
            if (res.status == PermissionsResultStatus.SUCCESS) null
            else "Google Home consent: ${res.status}"
        } catch (t: Throwable) {
            val r = "Google Home login error: ${t.message}"
            logDiag(activity, r)
            r
        } finally {
            consentInFlight.set(false)
        }
    }

    @JvmStatic
    fun syncAllHomeDevices(activity: ComponentActivity, promptIfNeeded: Boolean, callback: HomeSyncCallback) {
        syncAllHomeDevices(activity, promptIfNeeded, false, callback)
    }

    @JvmStatic
    fun syncAllHomeDevices(
        activity: ComponentActivity,
        promptIfNeeded: Boolean,
        forceReconsent: Boolean,
        callback: HomeSyncCallback
    ) {
        activity.lifecycleScope.launch(Dispatchers.IO + homeCoroutineHandler) {
            try {
                if (activity.isFinishing || activity.isDestroyed) return@launch
                val hc = getOrCreateClient(activity)
                val permErr = requestConsentIfNeeded(
                    activity,
                    hc,
                    allowPrompt = promptIfNeeded,
                    forceLaunch = forceReconsent
                )
                if (permErr != null) {
                    callback.onSynced(emptyList(), permErr)
                    return@launch
                }

                val structures = withTimeout(12_000) { hc.structures().first() }
                val snapshots = ArrayList<HomeDeviceSnapshot>()
                var tvOnOffCount = 0
                for (s in structures) {
                    val devs = try {
                        withTimeout(12_000) { s.devices().first() }
                    } catch (t: Throwable) {
                        logDiag(activity, "Structure '${s.name}' timeout: ${t.message}")
                        continue
                    }
                    for (d in devs) {
                        val typeNames = try {
                            withTimeout(4_000) { d.types().first().map { it.javaClass.simpleName } }
                        } catch (_: Throwable) { emptyList() }
                        if (typeNames.size == 1 && typeNames[0].contains("RootNode")) continue

                        val key = "home::${s.name}::${d.name}"
                        devicesByKey[key] = d
                        devicesByLowerName[d.name.trim().lowercase(Locale.US)] = d

                        // CRITICAL: Cloud-fulfilled GoogleTV / CastingVideoPlayer traits often report
                        // supports(Command.Off) == false even when onOff.off() physically powers off the TV!
                        val onOff = findOnOff(d)
                        val supportsPower = (onOff != null)
                        val isOn = onOff?.onOff ?: true
                        val (cat, label) = classifyTypes(typeNames, d.name)

                        if (cat == "TV" || cat == "PROJECTOR" || cat == "DISPLAY") {
                            if (supportsPower) tvOnOffCount++
                            logDiag(activity, "Home TV/Display '${d.name}' (${s.name}) types=${typeNames.joinToString()} onOff=$supportsPower state=${onOff?.onOff}")
                        }

                        snapshots.add(
                            HomeDeviceSnapshot(
                                key = key,
                                homeName = s.name,
                                name = d.name,
                                typeSummary = if (s.name.isNotEmpty()) "$label · ${s.name}" else label,
                                category = cat,
                                supportsOnOff = supportsPower,
                                powerOn = isOn
                            )
                        )
                    }
                }
                latestSnapshots.clear()
                latestSnapshots.addAll(snapshots)
                lastSyncMs = System.currentTimeMillis()
                logDiag(activity, "Synced ${snapshots.size} Home device(s) ($tvOnOffCount TV/Display with OnOff)")
                callback.onSynced(snapshots, null)
            } catch (t: Throwable) {
                val err = "${t.javaClass.simpleName}: ${t.message}"
                logDiag(activity, "syncAllHomeDevices failed: $err")
                callback.onSynced(emptyList(), err)
            }
        }
    }

    private fun lookupDevice(homeKeyOrName: String, fallbackName: String): HomeDevice? {
        if (homeKeyOrName.isNotEmpty()) {
            devicesByKey[homeKeyOrName]?.let { return it }
        }
        val norm = fallbackName.trim().lowercase(Locale.US)
        if (norm.isNotEmpty()) {
            devicesByLowerName[norm]?.let { return it }
            return devicesByLowerName.entries.firstOrNull { (k, _) ->
                k.length >= 3 && norm.length >= 3 && (k.contains(norm) || norm.contains(k))
            }?.value
        }
        return null
    }

    @JvmStatic
    fun setPower(activity: ComponentActivity, homeKeyOrId: String, deviceName: String, turnOn: Boolean, callback: PowerCallback) {
        activity.lifecycleScope.launch(Dispatchers.IO + homeCoroutineHandler) {
            try {
                val hc = getOrCreateClient(activity)
                val permErr = requestConsentIfNeeded(activity, hc, allowPrompt = true, forceLaunch = false)
                if (permErr != null) {
                    logDiag(activity, "setPower('$deviceName') blocked: $permErr")
                    callback.onResult(false, permErr)
                    return@launch
                }
                var dev = lookupDevice(homeKeyOrId, deviceName)
                if (dev == null) {
                    val structures = withTimeout(10_000) { hc.structures().first() }
                    for (s in structures) {
                        try {
                            val devs = withTimeout(10_000) { s.devices().first() }
                            for (d in devs) {
                                devicesByKey["home::${s.name}::${d.name}"] = d
                                devicesByLowerName[d.name.trim().lowercase(Locale.US)] = d
                            }
                        } catch (_: Throwable) {}
                    }
                    dev = lookupDevice(homeKeyOrId, deviceName)
                }
                if (dev == null) {
                    logDiag(activity, "setPower('$deviceName', key='$homeKeyOrId'): no matching Google Home device")
                    callback.onResult(false, "'$deviceName' not matched in Google Home")
                    return@launch
                }
                val onOff = findOnOff(dev)
                if (onOff == null) {
                    logDiag(activity, "setPower('${dev.name}'): OnOff trait null")
                    callback.onResult(false, "'${dev.name}' has no Google Home On/Off trait")
                    return@launch
                }
                if (turnOn) {
                    onOff.on()
                } else {
                    onOff.off()
                }
                logDiag(activity, "Google Home OnOff.${if (turnOn) "on()" else "off()"} -> '${dev.name}' OK")
                callback.onResult(true, dev.name)
            } catch (t: Throwable) {
                val err = "${t.javaClass.simpleName}: ${t.message}"
                logDiag(activity, "setPower('$deviceName') error: $err")
                callback.onResult(false, err)
            }
        }
    }
}
