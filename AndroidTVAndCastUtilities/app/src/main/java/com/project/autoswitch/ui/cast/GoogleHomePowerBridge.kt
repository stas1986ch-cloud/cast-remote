/**
 * @file GoogleHomePowerBridge.kt
 * @summary Crash-hardened Google Home SDK OnOff bridge matching cast-remote v3.2.1's full FactoryRegistry, Kotlin 2.4 runtime, and Activity lifecycle.
 * @description Uses a single app-scoped HomeClient with CoroutineExceptionHandler, full 25-type/37-trait FactoryRegistry,lifecycle-safe consent, and persistent CrashCapture logging.
 * @testing Compiled against Kotlin 2.4.0 stdlib + activity-ktx 1.9.3 + play-services-home 17.1.0 via signed assembleRelease (v2.4).
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

object GoogleHomePowerBridge {

    fun interface PowerCallback {
        fun onResult(success: Boolean, message: String)
    }

    fun interface StatesCallback {
        fun onStates(statesByLowerName: Map<String, Boolean>)
    }

    private const val CRASH_FILE = "last_crash.txt"
    private const val DIAG_FILE = "cast_diag.txt"

    private val consentInFlight = AtomicBoolean(false)
    private val devicesByLowerName = ConcurrentHashMap<String, HomeDevice>()

    @Volatile private var sharedClient: HomeClient? = null
    @Volatile private var lastSyncMs = 0L
    @Volatile private var homeConsentFailedReason: String? = null

    private val homeCoroutineHandler = CoroutineExceptionHandler { _, throwable ->
        homeConsentFailedReason = "Home SDK background error: ${throwable.message ?: throwable.javaClass.simpleName}"
    }

    // Exact 25-type, 37-trait registry proven working in cast-remote v3.2.1.
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
                val text = "Last crash: $stamp\nThread: ${t.name}\n${e.javaClass.name}: ${e.message}\n$sw"
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
    fun readDiagnosticReport(context: Context): String {
        val diag = try {
            val f = File(context.filesDir, DIAG_FILE)
            if (f.exists()) f.readText() else "No diagnostic entries yet."
        } catch (_: Throwable) { "Could not read diag log." }
        val crash = try {
            val f = File(context.filesDir, CRASH_FILE)
            if (f.exists()) f.readText() else "No crash recorded."
        } catch (_: Throwable) { "Could not read crash log." }
        return "=== Diagnostic Log ===\n$diag\n\n=== Last Crash ===\n$crash"
    }

    @JvmStatic
    fun copyToClipboard(context: Context, label: String, text: String) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
        } catch (_: Throwable) {
        }
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
            logDiag(activity, "HomeClient registered for CastRemoteActivity")
        } catch (t: Throwable) {
            val msg = "HomeClient register failed: ${t.javaClass.simpleName}: ${t.message}"
            logDiag(activity, msg)
            homeConsentFailedReason = msg
        }
    }

    @JvmStatic
    fun unregisterActivity(activity: ComponentActivity) {
        // Keep sharedClient alive across config/consent transitions just like cast-remote v3.2.1;
        // do not null it out while an OAuth ActivityResult may still be returning.
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

    private suspend fun ensureSynced(activity: ComponentActivity, forceRefresh: Boolean): String? {
        homeConsentFailedReason?.let { return it }
        if (activity.isFinishing || activity.isDestroyed) return "Screen closed"
        val hc = try {
            getOrCreateClient(activity)
        } catch (t: Throwable) {
            val r = "Home SDK init error: ${t.message}"
            logDiag(activity, r)
            homeConsentFailedReason = r
            return r
        }

        val permState = withTimeout(12_000) {
            hc.hasPermissions().first { it != PermissionsState.PERMISSIONS_STATE_UNINITIALIZED }
        }
        logDiag(activity, "Home permission state: $permState")

        if (permState != PermissionsState.GRANTED) {
            if (!consentInFlight.compareAndSet(false, true)) {
                return "Google Home sign-in already open"
            }
            try {
                val res = withContext(Dispatchers.Main) {
                    hc.requestPermissions(
                        ForcePermissionFlow.FORCE_LAUNCH,
                        consentScreenOptions = ConsentScreenOptions(
                            structureId = null,
                            allowedStructureIds = emptyList(),
                            isAllowStructureChange = false
                        )
                    )
                }
                logDiag(activity, "Consent result: ${res.status} ${res.errorMessage ?: ""}")
                if (res.status != PermissionsResultStatus.SUCCESS) {
                    // Do not permanently latch CANCELLED so user can retry cleanly on next switch toggle.
                    return "Google Home consent returned ${res.status} ${res.errorMessage ?: ""}".trim()
                }
            } catch (t: Throwable) {
                val reason = "Google Home login error (${t.javaClass.simpleName}: ${t.message})"
                logDiag(activity, reason)
                return reason
            } finally {
                consentInFlight.set(false)
            }
        }

        val stale = (System.currentTimeMillis() - lastSyncMs) > 5 * 60 * 1000L
        if (forceRefresh || devicesByLowerName.isEmpty() || stale) {
            val structures = withTimeout(12_000) { hc.structures().first() }
            logDiag(activity, "Home structures found: ${structures.size}")
            for (s in structures) {
                try {
                    val devs = withTimeout(12_000) { s.devices().first() }
                    for (d in devs) {
                        devicesByLowerName[d.name.trim().lowercase()] = d
                    }
                } catch (t: Throwable) {
                    logDiag(activity, "Structure '${s.name}' scan error: ${t.message}")
                }
            }
            if (devicesByLowerName.isNotEmpty()) lastSyncMs = System.currentTimeMillis()
        }
        return null
    }

    private fun lookupDevice(targetName: String): HomeDevice? {
        val norm = targetName.trim().lowercase()
        devicesByLowerName[norm]?.let { return it }
        return devicesByLowerName.entries.firstOrNull { (k, _) ->
            k.contains(norm) || norm.contains(k)
        }?.value
    }

    /**
     * Passively reads live OnOff state for all Google Home devices (only if permission is already GRANTED,
     * never triggering an unsolicited OAuth popup during background refresh).
     */
    @JvmStatic
    fun queryPowerStates(activity: ComponentActivity, callback: StatesCallback) {
        activity.lifecycleScope.launch(Dispatchers.IO + homeCoroutineHandler) {
            try {
                if (activity.isFinishing || activity.isDestroyed) return@launch
                val hc = getOrCreateClient(activity)
                val perm = withTimeout(5_000) {
                    hc.hasPermissions().first { it != PermissionsState.PERMISSIONS_STATE_UNINITIALIZED }
                }
                if (perm != PermissionsState.GRANTED) return@launch
                val structures = withTimeout(10_000) { hc.structures().first() }
                val out = HashMap<String, Boolean>()
                for (s in structures) {
                    try {
                        val devs = withTimeout(10_000) { s.devices().first() }
                        for (d in devs) {
                            val key = d.name.trim().lowercase()
                            devicesByLowerName[key] = d
                            val onOff = findOnOff(d)
                            val cur = onOff?.onOff
                            if (cur != null) {
                                out[key] = cur
                            }
                        }
                    } catch (_: Throwable) {
                    }
                }
                if (out.isNotEmpty()) {
                    lastSyncMs = System.currentTimeMillis()
                    callback.onStates(out)
                }
            } catch (_: Throwable) {
            }
        }
    }

    @JvmStatic
    fun setPower(activity: ComponentActivity, deviceName: String, turnOn: Boolean, callback: PowerCallback) {
        activity.lifecycleScope.launch(Dispatchers.IO + homeCoroutineHandler) {
            try {
                val permErr = ensureSynced(activity, forceRefresh = false)
                if (permErr != null) {
                    callback.onResult(false, permErr)
                    return@launch
                }
                var dev = lookupDevice(deviceName)
                if (dev == null) {
                    ensureSynced(activity, forceRefresh = true)
                    dev = lookupDevice(deviceName)
                }
                if (dev == null) {
                    callback.onResult(false, "'$deviceName' not found in Google Home")
                    return@launch
                }
                val onOff = findOnOff(dev)
                if (onOff == null) {
                    callback.onResult(false, "'${dev.name}' has no Google Home OnOff trait")
                    return@launch
                }
                if (turnOn) onOff.on() else onOff.off()
                logDiag(activity, "Sent ${if (turnOn) "ON" else "OFF"} to '${dev.name}'")
                callback.onResult(true, dev.name)
            } catch (t: Throwable) {
                val err = "${t.javaClass.simpleName}: ${t.message}"
                logDiag(activity, "setPower error: $err")
                callback.onResult(false, err)
            }
        }
    }
}
