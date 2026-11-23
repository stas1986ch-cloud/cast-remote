package com.stas.castremote

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.app.AlertDialog
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.google.home.HomeDevice
import com.google.home.ConsentScreenOptions
import com.google.home.FactoryRegistry
import com.google.home.ForcePermissionFlow
import com.google.home.Home
import com.google.home.HomeClient
import com.google.home.HomeConfig
import com.google.home.PermissionsResultStatus
import com.google.home.PermissionsState
import com.google.home.google.*
import com.google.home.matter.standard.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

class HomeDevicesActivity : ComponentActivity() {

    private lateinit var output: TextView
    private lateinit var copyDiagBtn: Button
    private lateinit var sendDiagBtn: Button
    private lateinit var devicesContainer: LinearLayout
    private lateinit var devicesNote: TextView
    private var homeClient: HomeClient? = null
    private var homeInitError: String? = null
    private var savedCrash: String? = null

    // v3.1: devices remembered per home. The last good diagnostic is
    // saved to SharedPreferences (per home structure) and shown as
    // tiles the moment this screen opens, before any refresh. The live
    // Home API Device objects of the last diagnostic are kept in
    // memory so Power off can run without a fresh scan; if the app
    // was restarted and only saved JSON exists, tapping Power off
    // runs a fresh diagnostic first (pendingPowerOff).
    private var liveDevices = mapOf<String, HomeDevice>()
    private val tileStateViews = mutableMapOf<String, TextView>()
    private val tilePowerButtons = mutableMapOf<String, Button>()
    private val latestPowerState = mutableMapOf<String, Boolean>()
    private var pendingPowerOff: String? = null
    // v3.1: complete device list of the last finished diagnostic,
    // handed to the main thread by postDiagOutput for save + render.
    private var pendingRenderDevices: List<HomeDeviceInfo>? = null
    // v3.1: true when the last scan missed a home (a structure timed
    // out): the render then merges fresh homes over the saved list
    // instead of replacing it, so a slow home never wipes good data.
    private var pendingRenderPartial = false

    // One device as the Control screen shows it. powerOn is null
    // when the device has no OnOff trait to read.
    private data class HomeDeviceInfo(
        val home: String,
        val name: String,
        val types: List<String>,
        val supportsOff: Boolean,
        val powerOn: Boolean?
    ) {
        val key: String get() = "$home::$name"
    }

    // v2.9: the FULL diagnostic text of the last completed run, kept
    // separate from the TextView (which only ever shows the 8KB capped
    // head). Copy diagnostic copies this, never the on-screen text.
    private var lastFullDiag: String? = null

    // Diagnostic watchdog. v2.6 froze on the checking-permission
    // message even with a 15s withTimeout, because the Home SDK
    // calls can block the collecting thread: a blocked main thread can
    // never run the timeout, so the UI stays frozen forever. The
    // diagnostic now runs on Dispatchers.IO (main thread stays free,
    // Back/Refresh keep working) and this main-thread watchdog
    // guarantees a visible timeout error within 15s of no progress,
    // even if the background work is stuck in a blocking SDK call.
    private val diagHandler = Handler(Looper.getMainLooper())
    private var diagGen = 0
    private var diagDone = false
    @Volatile private var diagLastProgress = 0L
    @Volatile private var awaitingConsent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashCapture.install(this)
        setContentView(R.layout.activity_home_devices)
        output = findViewById(R.id.homeOutput)
        devicesContainer = findViewById(R.id.devicesContainer)
        devicesNote = findViewById(R.id.devicesNote)
        findViewById<TextView>(R.id.versionText).text = "v" + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"
        savedCrash = CrashCapture.readSaved(this)
        findViewById<Button>(R.id.btnHomeRefresh).setOnClickListener { runDiagnostic() }
        findViewById<Button>(R.id.btnHomeBack).setOnClickListener { MainThreadWatchdog.mark("home back"); finish() }
        findViewById<Button>(R.id.btnHomeLog).setOnClickListener { showCrashLog() }
        copyDiagBtn = findViewById(R.id.btnHomeCopyDiag)
        setCopyDiagEnabled(false)
        copyDiagBtn.setOnClickListener { copyDiagnostic() }
        sendDiagBtn = findViewById(R.id.btnHomeSendDiag)
        setSendDiagEnabled(false)
        sendDiagBtn.setOnClickListener { sendDiagnostic() }
        try {
            val registry = FactoryRegistry(

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

            val config = HomeConfig(

                coroutineContext = Dispatchers.IO,

                factoryRegistry = registry,

                homePlatformScope = HomeConfig.HomePlatformScope.HOME_PLATFORM_SCOPE_VERSION_1

            )

            homeClient = Home.getClient(applicationContext, homeConfig = config)

            homeClient!!.registerActivityResultCallerForPermissions(this@HomeDevicesActivity)
        } catch (e: Exception) {
            homeInitError = e.message
        }
        // v3.1: show the saved devices immediately, then refresh them
        // with a live diagnostic like before.
        val saved = loadSavedDevices()
        if (saved != null) {
            renderDeviceTiles(saved.first, saved.second)
        }
        runDiagnostic()
    }

    override fun onDestroy() {
        diagHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // v2.9: Copy diagnostic copies the FULL text of the last completed
    // diagnostic (lastFullDiag), never the capped on-screen text.
    // The clipboard write runs off the main thread so a large
    // diagnostic can never block the UI (v2.8 freeze fix stays intact);
    // copyTextToClipboard posts its toast back to the main thread.
    private fun copyDiagnostic() {
        val text = lastFullDiag
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, "No diagnostic to copy yet", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            copyTextToClipboard(this@HomeDevicesActivity, text, "Diagnostic copied")
        }
    }

    // v3.0.1: Send diagnostic emails the FULL text of the last completed
    // diagnostic (lastFullDiag) to magicapps123@muse.ai as a file
    // attachment. An Intent body cannot carry it (Binder ~1MB limit),
    // so the text goes to a cache file shared via FileProvider and the
    // user taps Send once in their email app.
    private fun sendDiagnostic() {
        val text = lastFullDiag
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, "No diagnostic to send yet", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())
                val file = File(cacheDir, "cast-remote-diagnostic-$stamp.txt")
                file.writeText(text)
                val uri: Uri = FileProvider.getUriForFile(
                    this@HomeDevicesActivity,
                    "${packageName}.fileprovider",
                    file
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_EMAIL, arrayOf("magicapps123@muse.ai"))
                    putExtra(Intent.EXTRA_SUBJECT, "Cast Remote diagnostic $stamp")
                    putExtra(Intent.EXTRA_TEXT, "Cast Remote diagnostic attached (full text, ${text.length} chars).")
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                withContext(Dispatchers.Main) {
                    try {
                        startActivity(Intent.createChooser(intent, "Send diagnostic"))
                    } catch (e: Exception) {
                        Toast.makeText(this@HomeDevicesActivity, "No email app found", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@HomeDevicesActivity, "Could not prepare diagnostic: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Disabled buttons keep their tint under this theme, so dim the
    // button as well to make the disabled state visible.
    private fun setCopyDiagEnabled(enabled: Boolean) {
        copyDiagBtn.isEnabled = enabled
        copyDiagBtn.alpha = if (enabled) 1.0f else 0.5f
    }

    private fun setSendDiagEnabled(enabled: Boolean) {
        sendDiagBtn.isEnabled = enabled
        sendDiagBtn.alpha = if (enabled) 1.0f else 0.5f
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // v3.1: one icon per device type. Emoji glyphs in a big TextView
    // keep the tiles readable without shipping drawable assets.
    // Surrogate-pair escapes keep the source pure ASCII.
    private fun iconForTypes(types: List<String>): String {
        return when {
            types.any { it.contains("CastingVideoPlayer") || it.contains("GoogleTV") || it.contains("GoogleDisplay") } -> "\uD83D\uDCFA"
            types.any { it.contains("Speaker") } -> "\uD83D\uDD0A"
            types.any { it.contains("Color") || it.contains("Dimmable") || it.contains("OnOffLight") } -> "\uD83D\uDCA1"
            types.any { it.contains("PluginUnit") } -> "\uD83D\uDD0C"
            types.any { it.contains("Switch") } -> "\uD83D\uDD18"
            types.any { it.contains("Thermostat") } -> "\uD83C\uDF21\uFE0F"
            types.any { it.contains("Fan") } -> "\uD83C\uDF00"
            types.any { it.contains("DoorLock") } -> "\uD83D\uDD12"
            types.any { it.contains("Camera") || it.contains("Doorbell") } -> "\uD83D\uDCF7"
            types.any { it.contains("Sensor") } -> "\uD83D\uDCE1"
            types.any { it.contains("WindowCovering") } -> "\uD83E\uDE9F"
            types.any { it.contains("Chime") } -> "\uD83D\uDD14"
            types.any { it.contains("RootNode") } -> "\uD83C\uDFE0"
            else -> "\uD83D\uDCE6"
        }
    }

    private fun friendlyTypeName(type: String): String = when {
        type.contains("CastingVideoPlayer") -> "TV"
        type.contains("GoogleTV") -> "Google TV"
        type.contains("GoogleDisplay") -> "Smart display"
        type.contains("Speaker") -> "Speaker"
        type.contains("ExtendedColorLight") -> "Color light"
        type.contains("ColorTemperatureLight") -> "Color temperature light"
        type.contains("DimmableLight") -> "Dimmable light"
        type.contains("OnOffLight") -> "Light"
        type.contains("PluginUnit") -> "Smart plug"
        type.contains("LightSwitch") -> "Light switch"
        type.contains("Thermostat") -> "Thermostat"
        type.contains("Fan") -> "Fan"
        type.contains("DoorLock") -> "Door lock"
        type.contains("Camera") -> "Camera"
        type.contains("Doorbell") -> "Doorbell"
        type.contains("ContactSensor") -> "Contact sensor"
        type.contains("OccupancySensor") -> "Occupancy sensor"
        type.contains("WindowCovering") -> "Window covering"
        type.contains("Chime") -> "Chime"
        type.contains("RootNode") -> "Hub"
        else -> type
    }

    private fun savedPrefs() = getSharedPreferences("cast_remote_home_devices", Context.MODE_PRIVATE)

    // v3.1: save the device list per home so the Control screen can
    // show it without a fresh scan.
    private fun saveDevices(devices: List<HomeDeviceInfo>) {
        try {
            val arr = JSONArray()
            for (d in devices) {
                val o = JSONObject()
                o.put("home", d.home)
                o.put("name", d.name)
                val types = JSONArray()
                for (t in d.types) types.put(t)
                o.put("types", types)
                o.put("supportsOff", d.supportsOff)
                if (d.powerOn != null) o.put("powerOn", d.powerOn) else o.put("powerOn", JSONObject.NULL)
                arr.put(o)
            }
            val root = JSONObject()
            root.put("savedAt", System.currentTimeMillis())
            root.put("devices", arr)
            savedPrefs().edit().putString("devices_v1", root.toString()).apply()
        } catch (e: Exception) {
            DiagLog.log(this, "saveDevices failed: ${e.message}")
        }
    }

    // Returns (devices, note) or null when nothing is saved yet.
    private fun loadSavedDevices(): Pair<List<HomeDeviceInfo>, String>? {
        return try {
            val raw = savedPrefs().getString("devices_v1", null) ?: return null
            val root = JSONObject(raw)
            val savedAt = root.optLong("savedAt", 0L)
            val arr = root.optJSONArray("devices") ?: return null
            val list = mutableListOf<HomeDeviceInfo>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val types = mutableListOf<String>()
                val ta = o.optJSONArray("types")
                if (ta != null) for (j in 0 until ta.length()) types.add(ta.optString(j, ""))
                val powerOn = if (o.isNull("powerOn")) null else o.optBoolean("powerOn")
                list.add(HomeDeviceInfo(o.optString("home", ""), o.optString("name", "?"), types, o.optBoolean("supportsOff", false), powerOn))
            }
            if (list.isEmpty()) return null
            val stamp = if (savedAt > 0) SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(savedAt)) else "earlier"
            Pair(list, "Saved $stamp - showing your saved devices.")
        } catch (e: Exception) {
            null
        }
    }

    // v3.1: fresh devices replace the saved ones for their own homes
    // only; homes the scan did not reach keep their saved devices.
    private fun mergeDevices(saved: List<HomeDeviceInfo>, fresh: List<HomeDeviceInfo>): List<HomeDeviceInfo> {
        val freshHomes = fresh.map { it.home }.toSet()
        val kept = saved.filter { it.home !in freshHomes }
        return kept + fresh
    }

    // v3.1: one card per device, grouped by home, with a type icon
    // and a Power off button when the device supports it.
    private fun renderDeviceTiles(devices: List<HomeDeviceInfo>, note: String) {
        devicesContainer.removeAllViews()
        tileStateViews.clear()
        tilePowerButtons.clear()
        devicesNote.text = note
        if (devices.isEmpty()) {
            devicesNote.text = "No devices found in your home yet."
            return
        }
        val byHome = devices.groupBy { if (it.home.isEmpty()) "Home" else it.home }
        for ((home, list) in byHome) {
            val header = TextView(this).apply {
                text = "$home - ${list.size} devices"
                setTextColor(Color.parseColor("#7C7C9A"))
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(0, dp(12), 0, dp(4))
            }
            devicesContainer.addView(header)
            for (info in list) {
                devicesContainer.addView(buildDeviceTile(info))
            }
        }
    }

    private fun buildDeviceTile(info: HomeDeviceInfo): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        }
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val icon = TextView(this).apply {
            text = iconForTypes(info.types)
            textSize = 32f
            setPadding(0, 0, dp(12), 0)
        }
        topRow.addView(icon)
        val nameCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val nameView = TextView(this).apply {
            text = info.name
            setTextColor(Color.parseColor("#E5E7EB"))
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        nameCol.addView(nameView)
        val typeView = TextView(this).apply {
            text = info.types.joinToString(" · ") { friendlyTypeName(it) }
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 13f
            setPadding(0, dp(2), 0, 0)
        }
        nameCol.addView(typeView)
        topRow.addView(nameCol)
        card.addView(topRow)
        val stateView = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(8), 0, 0)
        }
        card.addView(stateView)
        tileStateViews[info.key] = stateView
        refreshTileState(info)
        if (info.supportsOff) {
            val btn = Button(this).apply {
                text = "Power off"
                isAllCaps = false
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#2A2A3A"))
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener { onPowerOffTapped(info, this, stateView) }
            }
            tilePowerButtons[info.key] = btn
            card.addView(btn)
        } else if (info.types.any { it.contains("CastingVideoPlayer") || it.contains("GoogleTV") || it.contains("GoogleDisplay") }) {
            val noPower = TextView(this).apply {
                text = "Power off not available for this device"
                setTextColor(Color.parseColor("#9CA3AF"))
                textSize = 13f
                setPadding(0, dp(8), 0, 0)
            }
            card.addView(noPower)
        }
        return card
    }

    private fun refreshTileState(info: HomeDeviceInfo) {
        val stateView = tileStateViews[info.key] ?: return
        val on = latestPowerState[info.key] ?: info.powerOn
        if (on == null) {
            stateView.text = ""
            stateView.visibility = View.GONE
            return
        }
        stateView.visibility = View.VISIBLE
        if (on) {
            stateView.text = "Power: On"
            stateView.setTextColor(Color.parseColor("#34D399"))
        } else {
            stateView.text = "Power: Off"
            stateView.setTextColor(Color.parseColor("#9CA3AF"))
        }
    }

    private fun updateTilePowerState(key: String, on: Boolean) {
        latestPowerState[key] = on
        val stateView = tileStateViews[key] ?: return
        stateView.visibility = View.VISIBLE
        if (on) {
            stateView.text = "Power: On"
            stateView.setTextColor(Color.parseColor("#34D399"))
        } else {
            stateView.text = "Power: Off"
            stateView.setTextColor(Color.parseColor("#9CA3AF"))
        }
    }

    // v3.1: the OnOff trait of a Home device, whatever its type.
    // Each typeOrNull returns null fast when the device is not of
    // that type; the timeout only guards a stuck Home call.
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
            } catch (e: Exception) {
            }
        }
        return null
    }

    // v3.1: Power off from the Control screen, through the Google
    // Home API (no ADB host is known for Home devices).
    private fun onPowerOffTapped(info: HomeDeviceInfo, btn: Button, stateView: TextView) {
        if (liveDevices.containsKey(info.key)) {
            attemptPowerOff(info.key)
        } else {
            // App restarted and only saved JSON exists: scan the home
            // first, then power off the match.
            Toast.makeText(this, "Refreshing home devices first...", Toast.LENGTH_SHORT).show()
            pendingPowerOff = info.key
            runDiagnostic()
        }
    }

    private fun attemptPowerOff(key: String) {
        val btn = tilePowerButtons[key]
        btn?.isEnabled = false
        btn?.text = "Turning off..."
        lifecycleScope.launch(Dispatchers.IO) {
            val dev = liveDevices[key]
            val onOff = try { dev?.let { findOnOff(it) } } catch (e: Exception) { null }
            if (onOff == null) {
                withContext(Dispatchers.Main) {
                    btn?.isEnabled = true
                    btn?.text = "Power off"
                    Toast.makeText(this@HomeDevicesActivity, "Device no longer found in your home", Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            try {
                onOff.off()
                withContext(Dispatchers.Main) {
                    btn?.isEnabled = true
                    btn?.text = "Power off"
                    updateTilePowerState(key, false)
                    Toast.makeText(this@HomeDevicesActivity, "Turned off", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    btn?.isEnabled = true
                    btn?.text = "Power off"
                    Toast.makeText(this@HomeDevicesActivity, "Power off failed: " + e.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showCrashLog() {
        val diag = DiagLog.readSaved(this)
        val crash = CrashCapture.readSaved(this)
        val logText = "--- App log ---\n" + (diag ?: "No log recorded yet") + "\n\n--- Crash log ---\n" + (crash ?: "No crash recorded yet")
        val pad = (16 * resources.displayMetrics.density).toInt()
        val tv = TextView(this).apply {
            // v2.8: display is capped - the full log can be ~64KB and
            // used to freeze the dialog the same way the diagnostic
            // output froze the Home screen. Copy log still copies all.
            this.text = cappedForDisplay(logText)
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(pad, pad, pad, pad)
        }
        val sv = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle("App log")
            .setView(sv)
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy log") { _, _ -> copyTextToClipboard(this, logText) }
            .show()
    }

    private fun setOutput(s: String) {
        // v2.8: cap what reaches the TextView. The full diagnostic
        // output (~1.8M chars) set here used to block the main thread
        // for 8-9s and freeze the Home screen.
        val full = if (savedCrash != null) "Last crash recorded, see Log\n\n$s" else s
        output.text = cappedForDisplay(full)
    }

    private fun markDiagProgress() {
        diagLastProgress = System.currentTimeMillis()
    }

    private fun postDiagOutput(gen: Int, s: String) {
        // Log from the background thread BEFORE touching the UI: if the
        // main thread is blocked, this entry still lands and the
        // MainThreadWatchdog will log the block with the location.
        DiagLog.log(this, "Posting diagnostic output to UI: " + s.length + " chars")
        // v2.8: save the FULL output to its own log file first, so
        // nothing is lost when only a short summary reaches the screen.
        DiagLog.writeFull(this, s)
        MainThreadWatchdog.mark("home output posting (" + s.length + " chars)")
        diagHandler.post {
            if (gen == diagGen && !diagDone) {
                val t0 = System.currentTimeMillis()
                setOutput(s)
                lastFullDiag = s
                setCopyDiagEnabled(true)
                setSendDiagEnabled(true)
                // v3.1: a complete diagnostic also refreshes the saved
                // device tiles (and any Power off that waited for it).
                // A partial scan merges fresh homes over the saved
                // list, so a slow home never wipes good saved data.
                val toRender = pendingRenderDevices
                pendingRenderDevices = null
                val partial = pendingRenderPartial
                pendingRenderPartial = false
                if (toRender != null) {
                    val saved = loadSavedDevices()?.first ?: emptyList()
                    val merged = mergeDevices(saved, toRender)
                    saveDevices(merged)
                    val note = if (partial) "Updated just now - ${merged.size} devices saved (some homes could not be refreshed)."
                        else "Updated just now - ${merged.size} devices saved for your home."
                    renderDeviceTiles(merged, note)
                    val pending = pendingPowerOff
                    pendingPowerOff = null
                    if (pending != null) attemptPowerOff(pending)
                }
                DiagLog.log(this@HomeDevicesActivity, "Diagnostic output shown OK in " + (System.currentTimeMillis() - t0) + " ms")
                MainThreadWatchdog.mark("home output shown")
            }
        }
    }

    private fun finishDiag(gen: Int, refreshBtn: Button) {
        diagHandler.post {
            if (gen == diagGen && !diagDone) {
                diagDone = true
                refreshBtn.isEnabled = true
            }
        }
    }

    private fun startDiagWatchdog(gen: Int, refreshBtn: Button) {
        diagHandler.postDelayed(object : Runnable {
            override fun run() {
                if (gen != diagGen || diagDone) return
                if (awaitingConsent) {
                    diagHandler.postDelayed(this, 2_000)
                    return
                }
                if (System.currentTimeMillis() - diagLastProgress > 15_000) {
                    DiagLog.log(this@HomeDevicesActivity, "WATCHDOG: no Home progress for 15s - showing timeout, UI stays responsive")
                    diagDone = true
                    setOutput("Timed out checking Google Home after 15 seconds with no progress. Google Home is not responding - make sure you are signed in to the Google Home app, then tap Refresh to try again.")
                    refreshBtn.isEnabled = true
                    return
                }
                diagHandler.postDelayed(this, 2_000)
            }
        }, 2_000)
    }

    private fun runDiagnostic() {
        if (homeInitError != null) {
            DiagLog.log(this, "Home diagnostic failed at start: $homeInitError")
            setOutput("Home diagnostic failed: $homeInitError")
            return
        }
        val refreshBtn = findViewById<Button>(R.id.btnHomeRefresh)
        refreshBtn.isEnabled = false
        setCopyDiagEnabled(false)
        setSendDiagEnabled(false)
        lastFullDiag = null
        diagGen++
        val gen = diagGen
        diagDone = false
        awaitingConsent = false
        markDiagProgress()
        setOutput("Checking Google Home permission...")
        MainThreadWatchdog.mark("home diagnostic start")
        DiagLog.log(this, "Home diagnostic started")
        startDiagWatchdog(gen, refreshBtn)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val started = System.currentTimeMillis()
                DiagLog.log(this@HomeDevicesActivity, "Checking Google Home permission...")
                val state = try {
                    withTimeout(15_000) {
                        homeClient!!.hasPermissions().first { it != PermissionsState.PERMISSIONS_STATE_UNINITIALIZED }
                    }
                } catch (e: TimeoutCancellationException) {
                    DiagLog.log(this@HomeDevicesActivity, "TIMEOUT after 15s waiting for permission state")
                    postDiagOutput(gen, "Timed out checking Google Home permission after 15 seconds. Google Home is not responding - make sure you are signed in to the Google Home app, then tap Refresh to try again.")
                    return@launch
                }
                markDiagProgress()
                DiagLog.log(this@HomeDevicesActivity, "Permission state: $state (${System.currentTimeMillis() - started} ms)")
                MainThreadWatchdog.mark("home permission: $state")
                if (state != PermissionsState.GRANTED) {
                    DiagLog.log(this@HomeDevicesActivity, "Permission not granted ($state) - launching consent flow")
                    val result = withContext(Dispatchers.Main) {
                        awaitingConsent = true
                        try {
                            homeClient!!.requestPermissions(
                                ForcePermissionFlow.FORCE_LAUNCH,
                                consentScreenOptions = ConsentScreenOptions(
                                    structureId = null,
                                    allowedStructureIds = emptyList(),
                                    isAllowStructureChange = false
                                )
                            )
                        } finally {
                            awaitingConsent = false
                        }
                    }
                    markDiagProgress()
                    DiagLog.log(this@HomeDevicesActivity, "Consent flow result: ${result.status} ${result.errorMessage ?: ""}")
                    if (result.status != PermissionsResultStatus.SUCCESS) {
                        postDiagOutput(gen, "Home permission not granted: ${result.status} ${result.errorMessage ?: ""}")
                        return@launch
                    }
                }
                val sb = StringBuilder()
                val structures = try {
                    withTimeout(15_000) { homeClient!!.structures().first() }
                } catch (e: TimeoutCancellationException) {
                    DiagLog.log(this@HomeDevicesActivity, "TIMEOUT after 15s listing home structures")
                    postDiagOutput(gen, "Timed out listing your Google Home structures after 15 seconds. Google Home is not responding - check your connection and the Google Home app, then tap Refresh to try again.")
                    return@launch
                }
                markDiagProgress()
                DiagLog.log(this@HomeDevicesActivity, "Structures found: ${structures.size}")
                MainThreadWatchdog.mark("home structures: ${structures.size}")
                if (structures.isEmpty()) {
                    sb.appendLine("No home structures found.")
                }
                // v3.1: collect the devices as they are listed, so the
                // Control screen can save them and show tiles.
                val freshDevices = mutableListOf<HomeDeviceInfo>()
                val freshLive = mutableMapOf<String, HomeDevice>()
                var scanComplete = true
                for (structure in structures) {
                    val devices = try {
                        withTimeout(15_000) { structure.devices().first() }
                    } catch (e: TimeoutCancellationException) {
                        DiagLog.log(this@HomeDevicesActivity, "TIMEOUT after 15s listing devices for structure ${structure.name}")
                        sb.appendLine("Timed out listing devices for home ${structure.name}.")
                        scanComplete = false
                        continue
                    }
                    markDiagProgress()
                    DiagLog.log(this@HomeDevicesActivity, "Structure '${structure.name}': ${devices.size} devices")
                    sb.appendLine("Home: ${structure.name} (${devices.size} devices)")
                    for (device in devices) {
                        markDiagProgress()
                        sb.appendLine()
                        sb.appendLine(device.name)
                        // v3.0: print only the type NAME (e.g. CastingVideoPlayerDevice),
                        // never toString(): toString() dumps the whole object graph
                        // (StandardTraits/BasicInformation + cluster command/attribute
                        // lists, ~35KB per device), which made the diagnostic
                        // 1,826,672 chars on a 50-device home and broke Copy
                        // diagnostic (clipboard Binder limit ~1MB -> "Copy failed").
                        val typeNames = try {
                            withTimeout(5_000) { device.types().first().map { it.javaClass.simpleName } }
                        } catch (e: Exception) {
                            listOf("types error: ${e.message}")
                        }
                        sb.appendLine("  Types: ${typeNames.joinToString()}")
                        var powerLine = "  Power off (OnOff): not present"
                        var supportsOff = false
                        var powerOn: Boolean? = null
                        try {
                            val onOff = findOnOff(device)
                            if (onOff == null) {
                                powerLine = "  Power off (OnOff): not present"
                            } else {
                                supportsOff = onOff.supports(OnOff.Command.Off)
                                powerOn = onOff.onOff
                                powerLine = "  Power off (OnOff): supports Off = $supportsOff, current = $powerOn"
                            }
                        } catch (e: Exception) {
                            powerLine = "  Power off check error: ${e.message}"
                        }
                        sb.appendLine(powerLine)
                        val info = HomeDeviceInfo(structure.name, device.name, typeNames, supportsOff, powerOn)
                        freshDevices.add(info)
                        freshLive[info.key] = device
                    }
                }
                sb.appendLine()
                sb.appendLine("Note: only registered traits are shown.")
                DiagLog.log(this@HomeDevicesActivity, "Diagnostic finished OK")
                liveDevices = freshLive
                // Hand whatever was collected to the UI thread; the
                // render merges fresh homes over the saved list, so a
                // partial scan still updates the homes it reached.
                if (freshDevices.isNotEmpty()) {
                    pendingRenderDevices = freshDevices
                    pendingRenderPartial = !scanComplete
                }
                postDiagOutput(gen, sb.toString())
            } catch (e: Exception) {
                DiagLog.log(this@HomeDevicesActivity, "Diagnostic failed: ${e.message}")
                postDiagOutput(gen, "Home diagnostic failed: ${e.message}")
            } finally {
                finishDiag(gen, refreshBtn)
            }
        }
    }

}
