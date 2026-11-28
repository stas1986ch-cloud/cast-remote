/**
 * @file GoogleHomePowerBridge.kt
 * @summary Full-registry Google Home SDK bridge (v3.6) with resilient cold-start permission handling, deduplicated 52-device sync, and multi-trait TV power control.
 * @description Registers 100% of Standard, Google, and Synthetic device types/traits with a job-free Dispatchers.IO HomeConfig, separates true OAuth consent errors (AUTH_REQUIRED:) from transient cold-start timeouts (TRANSIENT:), and executes OnOff/LowPower/KeypadInput/ExtendedKeypadInput with clear Error 19 HDMI-CEC diagnostics.
 * @testing Validated via gradlew assembleRelease + lintVitalRelease and automated cold-restart E2E scripts on emulator-5554 release APK.
 */
package com.project.autoswitch.ui.cast

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.home.ConsentScreenOptions
import com.google.home.DeviceType
import com.google.home.FactoryRegistry
import com.google.home.ForcePermissionFlow
import com.google.home.Home
import com.google.home.HomeClient
import com.google.home.HomeConfig
import com.google.home.HomeDevice
import com.google.home.PermissionsResultStatus
import com.google.home.PermissionsState
import com.google.home.Trait
import com.google.home.TraitFactory
import com.google.home.google.*
import com.google.home.matter.standard.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashSet
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
        @JvmField val powerOn: Boolean?,   // null when device doesn't report readable power state
        @JvmField val mediaState: String,  // PLAYING, PAUSED, BUFFERING, STANDBY, ACTIVE, or ""
        @JvmField val powerOffUnsupported: Boolean = false,
        @JvmField val powerOffNote: String = "",
        @JvmField val isCastOnlyDongle: Boolean = false
    )

    fun interface PowerCallback {
        fun onResult(success: Boolean, message: String)
    }

    fun interface HomeSyncCallback {
        fun onSynced(devices: List<HomeDeviceSnapshot>, statusNote: String?)
    }

    const val AUTH_REQUIRED_PREFIX = "AUTH_REQUIRED:"
    const val TRANSIENT_PREFIX = "TRANSIENT:"
    const val UNSUPPORTED_CC1_PREFIX = "UNSUPPORTED_CC1:"

    private const val CRASH_FILE = "last_crash.txt"
    private const val DIAG_FILE = "cast_diag.txt"

    private val consentInFlight = AtomicBoolean(false)
    private val syncInFlight = AtomicBoolean(false)
    private val autoPromptedSession = AtomicBoolean(false)
    private val devicesByKey = ConcurrentHashMap<String, HomeDevice>()
    private val devicesByLowerName = ConcurrentHashMap<String, HomeDevice>()
    private val latestSnapshots = CopyOnWriteArrayList<HomeDeviceSnapshot>()

    @Volatile private var sharedClient: HomeClient? = null
    @Volatile private var lastSyncMs = 0L

    @JvmStatic
    fun isSyncRecentlyCompleted(withinMs: Long): Boolean {
        return latestSnapshots.isNotEmpty() && (System.currentTimeMillis() - lastSyncMs) in 0..withinMs
    }

    private val homeCoroutineHandler = CoroutineExceptionHandler { _, throwable ->
        val _ignored = throwable.message
    }

    /**
     * Registers EVERY standard Matter, Google Home, and Synthetic device type & trait in the SDK
     * (including GoogleStreamingStickDevice, GoogleStreamingBoxDevice, GoogleSetTopBoxDevice,
     * GoogleScreenDevice, GooglePowerControlDevice, BasicVideoPlayerDevice, LowPower, KeypadInput,
     * ExtendedKeypadInput, SimplifiedOnOff, OnOff, etc.).
     */
    private fun buildRegistry(): FactoryRegistry = FactoryRegistry(
        traits = StandardTraitRegistry.traits + GoogleTraitRegistry.traits + SyntheticTraitRegistry.traits,
        types = StandardDeviceTypeRegistry.types + GoogleDeviceTypeRegistry.types
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
    @Synchronized
    fun logDiag(context: Context, msg: String) {
        try {
            android.util.Log.d("HomeDiag", msg)
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
            "v3.1"
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
        return "Google Screen Control and Cast $ver\nPackage: ${context.packageName}\n\n=== Diagnostic Log ===\n$diag\n\n=== Last Crash ===\n$crash"
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
            if (s.supportsOnOff || s.category == "TV" || s.category == "PROJECTOR" || s.category == "DISPLAY") {
                list.add(s)
            }
        }
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
    internal fun getOrCreateClient(context: Context): HomeClient {
        sharedClient?.let { return it }
        // CRITICAL: HomeConfig.coroutineContext is passed to Flow.flowOn() internally by HomeSdk,
        // so it MUST NOT contain a Job (no SupervisorJob()!). Use Dispatchers.IO only.
        val config = HomeConfig(
            coroutineContext = Dispatchers.IO,
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

    private suspend fun collectAllDeviceEndpoints(context: Context?, device: HomeDevice): List<DeviceType> {
        val out = LinkedHashSet<DeviceType>()
        try {
            val top = withTimeout(5_000) { device.types().first() }
            for (dt in top) {
                out.add(dt)
                try {
                    for (sub in dt.parts) {
                        out.add(sub)
                        try { out.addAll(sub.parts) } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {}
            }
            if (out.isEmpty() && context != null) {
                val hasOnOff = try { device.has(OnOff) } catch (_: Throwable) { false }
                val hasLowPower = try { device.has(LowPower) } catch (_: Throwable) { false }
                val hasCast = try { device.has(CastingVideoPlayerDevice) } catch (_: Throwable) { false }
                val hasGtv = try { device.has(GoogleTVDevice) } catch (_: Throwable) { false }
                val hasDisp = try { device.has(GoogleDisplayDevice) } catch (_: Throwable) { false }
                logDiag(context, "EMPTY types() on '${device.name}': has(OnOff=$hasOnOff LowPower=$hasLowPower Cast=$hasCast GTV=$hasGtv Disp=$hasDisp)")
            }
        } catch (t: Throwable) {
            if (context != null) {
                logDiag(context, "types() ERR on '${device.name}': ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        return out.toList()
    }

    private fun <T : Trait> findTraitAcrossEndpoints(endpoints: List<DeviceType>, factory: TraitFactory<T>): T? {
        for (dt in endpoints) {
            try {
                val t = dt.trait(factory)
                if (t != null) return t
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun summarizeAllTraits(endpoints: List<DeviceType>): String {
        val names = LinkedHashSet<String>()
        for (dt in endpoints) {
            try {
                for (tr in dt.traits()) {
                    names.add(tr.javaClass.simpleName.replace("Impl", ""))
                }
            } catch (_: Throwable) {}
        }
        return if (names.isEmpty()) "none" else names.joinToString(",")
    }

    private fun classifyTypes(typeNames: List<String>, devName: String): Pair<String, String> {
        val joined = (typeNames.joinToString(" ") + " " + devName).lowercase(Locale.US)
        return when {
            joined.contains("projector") || joined.contains("fengmi") || joined.contains("xgimi") || joined.contains("formovie") ->
                "PROJECTOR" to "Projector"
            typeNames.any {
                it.contains("GoogleTV") || it.contains("CastingVideoPlayer") || it.contains("BasicVideoPlayer")
                        || it.contains("StreamingStick") || it.contains("StreamingBox")
                        || it.contains("SetTopBox") || it.contains("ScreenDevice")
            } || joined.contains(" tv") || joined.endsWith("tv") ->
                "TV" to "TV / Google TV"
            typeNames.any { it.contains("GoogleDisplay") } ->
                "DISPLAY" to "Smart Display"
            typeNames.any { it.contains("Speaker") || it.contains("Soundbar") || it.contains("AudioVideoReceiver") } ->
                "SPEAKER" to "Speaker"
            typeNames.any { it.contains("Color") || it.contains("Dimmable") || it.contains("OnOffLight") } ->
                "LIGHT" to "Smart Light"
            typeNames.any { it.contains("PluginUnit") || it.contains("PowerControl") } ->
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

    private suspend fun awaitPermissionState(activity: ComponentActivity, hc: HomeClient): PermissionsState? {
        val firstTry = try {
            withTimeoutOrNull(22_000) {
                hc.hasPermissions().first { it != PermissionsState.PERMISSIONS_STATE_UNINITIALIZED }
            }
        } catch (_: Throwable) { null }
        if (firstTry != null) return firstTry

        logDiag(activity, "hasPermissions() still initializing after 22s — retrying...")
        delay(1_500)
        return try {
            withTimeoutOrNull(15_000) {
                hc.hasPermissions().first { it != PermissionsState.PERMISSIONS_STATE_UNINITIALIZED }
            }
        } catch (_: Throwable) { null }
    }

    internal suspend fun requestConsentIfNeeded(
        activity: ComponentActivity,
        hc: HomeClient,
        allowPrompt: Boolean,
        forceLaunch: Boolean = false
    ): String? {
        val permState = awaitPermissionState(activity, hc)
        if (!forceLaunch) {
            if (permState == PermissionsState.GRANTED) return null
            if (permState == null) {
                return "${TRANSIENT_PREFIX}Google Home still connecting — retrying..."
            }
            if (!allowPrompt) {
                return "${AUTH_REQUIRED_PREFIX}Tap 'Connect / Manage Google Home Devices' under the title"
            }
        }
        if (!consentInFlight.compareAndSet(false, true)) return "${TRANSIENT_PREFIX}Google Home selector already open"
        return try {
            val existingStructId = if (permState == PermissionsState.GRANTED) {
                try {
                    withTimeout(6_000) { hc.structures().first().firstOrNull()?.id?.id }
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

            com.project.autoswitch.ui.ScreenControlApp.suppressNextAdBriefly(25_000L)
            val res = withContext(Dispatchers.Main) {
                hc.requestPermissions(ForcePermissionFlow.FORCE_LAUNCH, consentScreenOptions = options)
            }
            logDiag(activity, "Consent result: ${res.status} ${res.errorMessage ?: ""}")
            if (res.status == PermissionsResultStatus.SUCCESS) null
            else "${AUTH_REQUIRED_PREFIX}Google Home consent: ${res.status}"
        } catch (t: Throwable) {
            val r = "Google Home login error: ${t.message}"
            logDiag(activity, r)
            "${AUTH_REQUIRED_PREFIX}$r"
        } finally {
            consentInFlight.set(false)
        }
    }

    @JvmStatic
    fun shouldAutoPromptThisSession(): Boolean = autoPromptedSession.compareAndSet(false, true)

    private suspend fun collectSnapshotsFromClient(activity: ComponentActivity, hc: HomeClient): ArrayList<HomeDeviceSnapshot> {
        val structures = withTimeout(12_000) { hc.structures().first() }
        val snapshots = ArrayList<HomeDeviceSnapshot>()
        for (s in structures) {
            val devs = try {
                withTimeout(12_000) { s.devices().first() }
            } catch (t: Throwable) {
                logDiag(activity, "Structure '${s.name}' timeout: ${t.message}")
                continue
            }
            // Inspect devices with bounded concurrency (12 at a time) so 50+ devices finish quickly with zero IPC timeouts
            val gate = Semaphore(12)
            val built = coroutineScope {
                devs.map { d ->
                    async {
                        gate.withPermit {
                            val endpoints = collectAllDeviceEndpoints(activity, d)
                            val typeNames = endpoints.map { it.javaClass.simpleName }.distinct()
                            if (typeNames.size == 1 && (typeNames[0].contains("RootNode") || typeNames[0].contains("OtaRequestor"))) {
                                return@withPermit null
                            }
                            if (typeNames.size == 1 && (typeNames[0].contains("Routine") || typeNames[0].contains("Scene"))) {
                                return@withPermit null
                            }

                            val key = "home::${s.name}::${d.name}"
                            devicesByKey[key] = d
                            devicesByLowerName[d.name.trim().lowercase(Locale.US)] = d

                            val onOff = findTraitAcrossEndpoints(endpoints, OnOff)
                            val lowPower = findTraitAcrossEndpoints(endpoints, LowPower)
                            val keypad = findTraitAcrossEndpoints(endpoints, KeypadInput)
                            val extKeypad = findTraitAcrossEndpoints(endpoints, ExtendedKeypadInput)
                            val mediaAct = findTraitAcrossEndpoints(endpoints, MediaActivityState)
                            val mediaPlay = findTraitAcrossEndpoints(endpoints, MediaPlayback)

                            val supportsPower = (onOff != null || lowPower != null || keypad != null || extKeypad != null)

                            val actState = try { mediaAct?.activityState } catch (_: Throwable) { null }
                            val playState = try { mediaPlay?.currentState } catch (_: Throwable) { null }
                            val mediaLabel = when {
                                playState == MediaPlaybackTrait.PlaybackStateEnum.Playing -> "PLAYING"
                                playState == MediaPlaybackTrait.PlaybackStateEnum.Paused -> "PAUSED"
                                playState == MediaPlaybackTrait.PlaybackStateEnum.Buffering -> "BUFFERING"
                                actState == MediaActivityStateTrait.ActivityState.Active -> "ON"
                                actState == MediaActivityStateTrait.ActivityState.Standby -> "OFF"
                                actState == MediaActivityStateTrait.ActivityState.Inactive -> "OFF"
                                else -> ""
                            }

                            // Determine live power state without falsely overriding unknown (null) to true!
                            val livePower: Boolean? = when {
                                onOff?.onOff != null -> onOff.onOff
                                playState == MediaPlaybackTrait.PlaybackStateEnum.Playing ||
                                    playState == MediaPlaybackTrait.PlaybackStateEnum.Paused ||
                                    playState == MediaPlaybackTrait.PlaybackStateEnum.Buffering -> true
                                actState == MediaActivityStateTrait.ActivityState.Active -> true
                                actState == MediaActivityStateTrait.ActivityState.Standby ||
                                    actState == MediaActivityStateTrait.ActivityState.Inactive -> false
                                else -> null
                            }

                            val (cat, label) = classifyTypes(typeNames, d.name)

                            val basicInfo = findTraitAcrossEndpoints(endpoints, BasicInformation)
                            val swVer = try { basicInfo?.softwareVersionString?.trim().orEmpty() } catch (_: Throwable) { "" }
                            val hwVer = try { basicInfo?.hardwareVersionString?.trim().orEmpty() } catch (_: Throwable) { "" }
                            val prodName = try { basicInfo?.productName?.trim().orEmpty() } catch (_: Throwable) { "" }
                            val isCastTv = (cat == "TV" || cat == "PROJECTOR" || cat == "DISPLAY") &&
                                (prodName.contains("Chromecast", ignoreCase = true) || prodName.contains("Eureka", ignoreCase = true))
                            val isGen1ByBasicInfo = isCastTv && swVer.startsWith("1.36.")
                            val isCastOnlyDongle = prodName.equals("Chromecast", ignoreCase = true) ||
                                prodName.equals("Chromecast Ultra", ignoreCase = true) ||
                                prodName.equals("Eureka Dongle", ignoreCase = true)

                            if (cat == "TV" || cat == "PROJECTOR" || cat == "DISPLAY") {
                                logDiag(
                                    activity,
                                    "TV '${d.name}' [${s.name}] onOff=${onOff?.onOff} act=$actState play=$playState -> livePower=$livePower sw='$swVer' hw='$hwVer' prod='$prodName' types=${typeNames.joinToString()}"
                                )
                            }

                            HomeDeviceSnapshot(
                                key = key,
                                homeName = s.name,
                                name = d.name,
                                typeSummary = if (s.name.isNotEmpty()) "$label · ${s.name}" else label,
                                category = cat,
                                supportsOnOff = supportsPower,
                                powerOn = livePower,
                                mediaState = mediaLabel,
                                powerOffUnsupported = isGen1ByBasicInfo,
                                powerOffNote = if (isGen1ByBasicInfo) "Unsupported w/ Chromecast 1" else "",
                                isCastOnlyDongle = isCastOnlyDongle
                            )
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            snapshots.addAll(built)
        }
        return snapshots
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
        if (!forceReconsent && !syncInFlight.compareAndSet(false, true)) {
            if (latestSnapshots.isNotEmpty()) {
                callback.onSynced(ArrayList(latestSnapshots), null)
            }
            return
        }
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

                var snapshots = try {
                    collectSnapshotsFromClient(activity, hc)
                } catch (t: Throwable) {
                    logDiag(activity, "Initial snapshot sync interrupted (${t.javaClass.simpleName}) — retrying once...")
                    delay(2_000)
                    collectSnapshotsFromClient(activity, hc)
                }
                // If permission appeared granted yet 0 devices were returned (stale/revoked/unshared structure),
                // automatically prompt to reauthorize when allowed!
                if (snapshots.isEmpty() && promptIfNeeded && !forceReconsent) {
                    logDiag(activity, "0 Google Home devices returned — prompting reauthorization")
                    val reauthErr = requestConsentIfNeeded(activity, hc, allowPrompt = true, forceLaunch = true)
                    if (reauthErr != null) {
                        callback.onSynced(emptyList(), reauthErr)
                        return@launch
                    }
                    snapshots = collectSnapshotsFromClient(activity, hc)
                }

                latestSnapshots.clear()
                latestSnapshots.addAll(snapshots)
                lastSyncMs = System.currentTimeMillis()
                logDiag(activity, "Synced ${snapshots.size} Home device(s)")
                callback.onSynced(snapshots, null)
            } catch (t: Throwable) {
                val err = "${t.javaClass.simpleName}: ${t.message}"
                logDiag(activity, "syncAllHomeDevices failed: $err")
                callback.onSynced(emptyList(), "${TRANSIENT_PREFIX}Google Home sync delayed ($err)")
            } finally {
                syncInFlight.set(false)
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

    /**
     * Executes power command on a single HomeDevice across all Android / Google Home power traits:
     * 1. OnOff (off() / on())
     * 2. LowPower (sleep() for TV standby when turning off)
     * 3. KeypadInput (HDMI-CEC PowerOffFunction / PowerOnFunction)
     * 4. ExtendedKeypadInput (Google TV PowerOffFunction / PowerOnFunction / Power)
     */
    private suspend fun tryExecutePowerOnDevice(activity: ComponentActivity, dev: HomeDevice, turnOn: Boolean): Pair<Boolean, String> {
        val endpoints = collectAllDeviceEndpoints(activity, dev)
        val traitsSummary = summarizeAllTraits(endpoints)

        // 1. Standard / Cloud OnOff trait across any DeviceType or child part
        var lastOnOffErr: String? = null
        val onOff = findTraitAcrossEndpoints(endpoints, OnOff)
        if (onOff != null) {
            try {
                if (turnOn) onOff.on() else onOff.off()
                val msg = "${dev.name} (OnOff.${if (turnOn) "on" else "off"})"
                logDiag(activity, "Power OK: $msg")
                return true to dev.name
            } catch (t: Throwable) {
                lastOnOffErr = t.message
                logDiag(activity, "OnOff.${if (turnOn) "on" else "off"} threw on '${dev.name}': ${t.message}")
            }
        }

        // 2. Matter LowPower.sleep() (TV / Chromecast HDMI-CEC standby when turning OFF)
        if (!turnOn) {
            val lowPower = findTraitAcrossEndpoints(endpoints, LowPower)
            if (lowPower != null) {
                try {
                    lowPower.sleep()
                    logDiag(activity, "Power OK: '${dev.name}' via LowPower.sleep()")
                    return true to dev.name
                } catch (t: Throwable) {
                    logDiag(activity, "LowPower.sleep() threw on '${dev.name}': ${t.message}")
                }
            }
        }

        // 3. Matter KeypadInput (HDMI-CEC discrete PowerOffFunction / PowerOnFunction)
        val keypad = findTraitAcrossEndpoints(endpoints, KeypadInput)
        if (keypad != null) {
            try {
                val cecKey = if (turnOn) {
                    KeypadInputTrait.CecKeyCodeEnum.PowerOnFunction
                } else {
                    KeypadInputTrait.CecKeyCodeEnum.PowerOffFunction
                }
                keypad.sendKey(cecKey)
                logDiag(activity, "Power OK: '${dev.name}' via KeypadInput($cecKey)")
                return true to dev.name
            } catch (t: Throwable) {
                logDiag(activity, "KeypadInput threw on '${dev.name}': ${t.message}")
            }
        }

        // 4. Google ExtendedKeypadInput (Google TV / Android TV remote PowerOffFunction / Power)
        val extKeypad = findTraitAcrossEndpoints(endpoints, ExtendedKeypadInput)
        if (extKeypad != null) {
            val keysToTry = if (turnOn) {
                listOf(
                    ExtendedKeypadInputTrait.KeyCode.PowerOnFunction,
                    ExtendedKeypadInputTrait.KeyCode.Power
                )
            } else {
                listOf(
                    ExtendedKeypadInputTrait.KeyCode.PowerOffFunction,
                    ExtendedKeypadInputTrait.KeyCode.Power
                )
            }
            for (kc in keysToTry) {
                try {
                    extKeypad.extendedSendKey(ExtendedKeypadInputTrait.Key(kc, false))
                    logDiag(activity, "Power OK: '${dev.name}' via ExtendedKeypadInput($kc)")
                    return true to dev.name
                } catch (t: Throwable) {
                    logDiag(activity, "ExtendedKeypadInput($kc) threw on '${dev.name}': ${t.message}")
                }
            }
        }

        val reason = if (lastOnOffErr != null && (lastOnOffErr.contains("19:") || lastOnOffErr.contains("Command errors"))) {
            if (!turnOn) {
                "${UNSUPPORTED_CC1_PREFIX}Unsupported w/ Chromecast 1"
            } else {
                "TV rejected HDMI-CEC power ON (Error 19: check TV HDMI-CEC)"
            }
        } else {
            "traits=[$traitsSummary]"
        }
        return false to reason
    }

    @JvmStatic
    fun setPower(activity: ComponentActivity, homeKeyOrId: String, deviceName: String, turnOn: Boolean, callback: PowerCallback) {
        activity.lifecycleScope.launch(Dispatchers.IO + homeCoroutineHandler) {
            try {
                val hc = getOrCreateClient(activity)
                val permErr = requestConsentIfNeeded(activity, hc, allowPrompt = true, forceLaunch = false)
                if (permErr != null) {
                    val cleanPermErr = permErr
                        .removePrefix(AUTH_REQUIRED_PREFIX)
                        .removePrefix(TRANSIENT_PREFIX)
                        .trim()
                    logDiag(activity, "setPower('$deviceName') blocked: $cleanPermErr")
                    callback.onResult(false, cleanPermErr)
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

                val (ok, detail) = tryExecutePowerOnDevice(activity, dev, turnOn)
                if (ok) {
                    callback.onResult(true, detail)
                    return@launch
                }

                // Room-sibling check: e.g., if 'Master Bedroom TV' is a Chromecast / Media node paired with a sibling TV / Plug in the same Google Home room!
                val targetRoomId = try { dev.room()?.id?.id } catch (_: Throwable) { null }
                if (!targetRoomId.isNullOrEmpty()) {
                    for (candidate in devicesByKey.values) {
                        if (candidate == dev) continue
                        val candRoom = try { candidate.room()?.id?.id } catch (_: Throwable) { null }
                        val candLower = candidate.name.lowercase(Locale.US)
                        if (candRoom == targetRoomId && (candLower.contains("tv") || candLower.contains("plug") || candLower.contains("power"))) {
                            val (sibOk, sibName) = tryExecutePowerOnDevice(activity, candidate, turnOn)
                            if (sibOk) {
                                logDiag(activity, "Power OK via room sibling '$sibName' for '${dev.name}'")
                                callback.onResult(true, sibName)
                                return@launch
                            }
                        }
                    }
                }

                logDiag(activity, "setPower('${dev.name}'): no power command succeeded ($detail)")
                val outMsg = if (detail.startsWith(UNSUPPORTED_CC1_PREFIX)) detail else "'${dev.name}' $detail"
                callback.onResult(false, outMsg)
            } catch (t: Throwable) {
                val err = "${t.javaClass.simpleName}: ${t.message}"
                logDiag(activity, "setPower('$deviceName') error: $err")
                callback.onResult(false, err)
            }
        }
    }

    /**
     * Executes scheduled background power-off on a Google Home device without needing an active Activity.
     */
    @JvmStatic
    suspend fun powerOffFromBackground(context: Context, homeKey: String, deviceName: String): Pair<Boolean, String> {
        return try {
            val hc = getOrCreateClient(context)
            val perm = withTimeout(8_000) {
                hc.hasPermissions().first { it != PermissionsState.PERMISSIONS_STATE_UNINITIALIZED }
            }
            if (perm != PermissionsState.GRANTED) {
                return false to "Google Home permission not granted"
            }
            var dev = lookupDevice(homeKey, deviceName)
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
                dev = lookupDevice(homeKey, deviceName)
            }
            if (dev == null) return false to "Device '$deviceName' not found in Google Home"
            val endpoints = collectAllDeviceEndpoints(context, dev)
            findTraitAcrossEndpoints(endpoints, OnOff)?.let {
                try {
                    it.off()
                    logDiag(context, "Scheduled Off OK: '${dev.name}' via OnOff.off()")
                    return true to dev.name
                } catch (_: Throwable) {}
            }
            findTraitAcrossEndpoints(endpoints, LowPower)?.let {
                try {
                    it.sleep()
                    logDiag(context, "Scheduled Off OK: '${dev.name}' via LowPower.sleep()")
                    return true to dev.name
                } catch (_: Throwable) {}
            }
            findTraitAcrossEndpoints(endpoints, KeypadInput)?.let {
                try {
                    it.sendKey(KeypadInputTrait.CecKeyCodeEnum.PowerOffFunction)
                    logDiag(context, "Scheduled Off OK: '${dev.name}' via KeypadInput")
                    return true to dev.name
                } catch (_: Throwable) {}
            }
            findTraitAcrossEndpoints(endpoints, ExtendedKeypadInput)?.let {
                for (kc in listOf(ExtendedKeypadInputTrait.KeyCode.PowerOffFunction, ExtendedKeypadInputTrait.KeyCode.Power)) {
                    try {
                        it.extendedSendKey(ExtendedKeypadInputTrait.Key(kc, false))
                        logDiag(context, "Scheduled Off OK: '${dev.name}' via ExtendedKeypadInput($kc)")
                        return true to dev.name
                    } catch (_: Throwable) {}
                }
            }
            false to "No power-off trait succeeded on '${dev.name}'"
        } catch (t: Throwable) {
            false to (t.message ?: t.javaClass.simpleName)
        }
    }
}
