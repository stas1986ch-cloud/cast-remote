/**
 * @file MainActivity.kt
 * @summary Main Android activity for Cast Remote v3.2.0: device discovery, favorite pinning, YouTube/URL casting, mirroring, phone receiver, and TV power on/off.
 * @description Bridges Chaquopy Python (pychromecast/phone_cast/adb), native AndroidTVRemoteManager (Mutual TLS v2), and SharedPreferences pinning into a dark-neon View UI.
 * @testing Compiled and packaged into a signed APK via Android Gradle CI with Chaquopy Python 3.11.
 */

package com.stas.castremote

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var linkInput: EditText
    private lateinit var statusText: TextView
    private var savedCrash: String? = null
    private lateinit var devicesContainer: LinearLayout
    private lateinit var btnReceive: Button
    private lateinit var targetSpinner: Spinner
    private val spinnerItems = mutableListOf("No TVs - tap Refresh casting devices")
    private var targetAdapter: ArrayAdapter<String>? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var multicastLock: WifiManager.MulticastLock? = null
    private val phoneTargets = mutableMapOf<String, Pair<String, Int>>()
    private var receiverOn = false
    private var receiverServer: ServerSocket? = null
    private var receiverThread: Thread? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private val deviceStatus = mutableMapOf<String, String>()
    private val deviceStatusViews = mutableMapOf<String, TextView>()
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var atvRemote: AndroidTVRemoteManager
    private var pinnedDevices = mutableSetOf<String>()
    private var lastDevicesArray: JSONArray? = null
    private val receiverPool = Executors.newCachedThreadPool()

    private val violet = Color.parseColor("#7C3AED")
    private val cyan = Color.parseColor("#06B6D4")
    private val magenta = Color.parseColor("#EC4899")
    private val amber = Color.parseColor("#F59E0B")
    private val emerald = Color.parseColor("#10B981")
    private val crimson = Color.parseColor("#EF4444")
    private val bg = Color.parseColor("#0B0B14")
    private val card = Color.parseColor("#17172A")
    private val text = Color.parseColor("#E5E7EB")
    private val darkBtn = Color.parseColor("#2A2A3A")

    private var progressDialog: AlertDialog? = null
    private var progressText: TextView? = null
    private var progressGen = 0
    private val progressHandler = Handler(Looper.getMainLooper())

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun gradientBg(colors: IntArray, radiusDp: Int): GradientDrawable {
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashCapture.install(this)
        atvRemote = AndroidTVRemoteManager(this)
        val prefs = getSharedPreferences("cast_remote_prefs", Context.MODE_PRIVATE)
        pinnedDevices = prefs.getStringSet("pinned_device_names", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("castremote")
        multicastLock?.setReferenceCounted(true)
        multicastLock?.acquire()
        window.decorView.setBackgroundColor(bg)
        setContentView(R.layout.activity_main)
        findViewById<TextView>(R.id.versionText).text = "v" + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"
        linkInput = findViewById(R.id.linkInput)
        statusText = findViewById(R.id.statusText)
        savedCrash = CrashCapture.readSaved(this)
        if (savedCrash != null) {
            statusText.text = "Last crash recorded, see Log"
        }
        devicesContainer = findViewById(R.id.devicesContainer)
        val titleText = findViewById<TextView>(R.id.titleText)
        titleText.post {
            val w = titleText.width.toFloat()
            if (w > 0) {
                titleText.paint.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(violet, cyan, magenta), null, Shader.TileMode.CLAMP)
                titleText.invalidate()
            }
        }
        val btnMirrorPlaying = findViewById<Button>(R.id.btnMirrorPlaying)
        btnMirrorPlaying.background = gradientBg(intArrayOf(violet, cyan, magenta), 14)
        btnMirrorPlaying.setPadding(dp(16), dp(14), dp(16), dp(14))
        btnMirrorPlaying.setOnClickListener { mirrorTargets(JSONArray().put("ALL")) }
        val btnRefresh = findViewById<Button>(R.id.btnRefresh)
        btnRefresh.background = gradientBg(intArrayOf(violet, cyan), 10)
        btnRefresh.setPadding(dp(12), dp(10), dp(12), dp(10))
        btnRefresh.setOnClickListener { refreshDevices() }
        val btnStopAll = findViewById<Button>(R.id.btnStopAll)
        btnStopAll.background = gradientBg(intArrayOf(magenta, violet), 10)
        btnStopAll.setPadding(dp(12), dp(10), dp(12), dp(10))
        btnStopAll.setOnClickListener { stopAll() }
        btnReceive = findViewById(R.id.btnReceive)
        btnReceive.setBackgroundColor(darkBtn)
        btnReceive.setPadding(dp(12), dp(10), dp(12), dp(10))
        btnReceive.setOnClickListener { toggleReceiver() }
        targetSpinner = findViewById(R.id.targetSpinner)
        targetAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, spinnerItems) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(text)
                return v
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent) as TextView
                v.setTextColor(text)
                return v
            }
        }
        targetAdapter?.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        targetSpinner.adapter = targetAdapter
        val btnPlayAny = findViewById<Button>(R.id.btnPlayAny)
        btnPlayAny.background = gradientBg(intArrayOf(cyan, violet), 10)
        btnPlayAny.setPadding(dp(12), dp(10), dp(12), dp(10))
        btnPlayAny.setOnClickListener { playAnyLink() }
        val btnHomeDevices = findViewById<Button>(R.id.btnHomeDevices)
        btnHomeDevices.background = gradientBg(intArrayOf(cyan, magenta), 10)
        btnHomeDevices.setPadding(dp(12), dp(10), dp(12), dp(10))
        btnHomeDevices.setOnClickListener { startActivity(Intent(this, HomeDevicesActivity::class.java)) }
        val btnLog = findViewById<Button>(R.id.btnLog)
        btnLog.setBackgroundColor(darkBtn)
        btnLog.setPadding(dp(12), dp(10), dp(12), dp(10))
        btnLog.setOnClickListener { showCrashLog() }
    }

    override fun onDestroy() {
        try {
            dismissProgress()
        } catch (e: Exception) {
        }
        progressHandler.removeCallbacksAndMessages(null)
        try {
            if (receiverOn) stopReceiver()
        } catch (e: Exception) {
        }
        try {
            atvRemote.cancelPairing()
            uiScope.cancel()
        } catch (e: Exception) {
        }
        try {
            multicastLock?.release()
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    private fun py(): com.chaquo.python.PyObject {
        return Python.getInstance().getModule("castctl")
    }

    private fun pyPhones(): com.chaquo.python.PyObject {
        return Python.getInstance().getModule("phone_cast")
    }

    private fun pyAdb(): com.chaquo.python.PyObject {
        return Python.getInstance().getModule("adb")
    }

    private fun runPy(label: String, call: () -> String, quiet: Boolean = false, onDone: (String) -> Unit) {
        showProgress(progressMessage(label))
        if (!quiet) setStatus("Working: " + label + "...")
        MainThreadWatchdog.mark("py start: " + label)
        executor.execute {
            val result = try {
                call()
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", e.message.toString()).toString()
            }
            runOnUiThread {
                var isError = false
                var errMsg: String? = null
                try {
                    val obj = JSONObject(result)
                    if (obj.has("ok") && !obj.optBoolean("ok", true)) {
                        isError = true
                        errMsg = obj.optString("error", "failed")
                    }
                } catch (e: Exception) {
                }
                if (isError) {
                    showProgressError("Failed: " + (errMsg ?: "failed"))
                } else {
                    hideProgress()
                }
                MainThreadWatchdog.mark("py done: " + label)
                onDone(result)
            }
        }
    }

    private fun showCrashLog() {
        val diag = DiagLog.readSaved(this)
        val crash = CrashCapture.readSaved(this)
        val logText = "--- App log ---\n" + (diag ?: "No log recorded yet") + "\n\n--- Crash log ---\n" + (crash ?: "No crash recorded yet")
        val tv = TextView(this).apply {
            // v2.8: display is capped - Copy log still copies the full text.
            this.text = cappedForDisplay(logText)
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val sv = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle("App log")
            .setView(sv)
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy log") { _, _ -> copyTextToClipboard(this, logText) }
            .show()
    }
    private fun setStatus(text: String) {
        statusText.text = if (savedCrash != null) "Last crash recorded, see Log\n\n$text" else text
    }

    private fun timeNow(): String = SimpleDateFormat("h:mm a", Locale.US).format(Date())

    private fun setDeviceStatus(name: String, text: String) {
        val line = text + " " + timeNow()
        deviceStatus[name] = line
        deviceStatusViews[name]?.text = line
    }

    private fun mirrorTargetNames(targets: JSONArray): List<String> {
        val names = mutableListOf<String>()
        var all = false
        for (i in 0 until targets.length()) {
            val n = targets.optString(i, "")
            if (n == "ALL") all = true else if (n.isNotEmpty()) names.add(n)
        }
        if (all) {
            for (n in spinnerItems) {
                if (n != "No TVs - tap Refresh casting devices") names.add(n)
            }
            names.addAll(phoneTargets.keys)
        }
        return names
    }

    private fun progressMessage(label: String): String {
        val l = label.lowercase()
        return when {
            l.contains("discover") || l.contains("finding phones") -> "Discovering devices..."
            l.contains("mirror") -> "Mirroring..."
            l.contains("play") -> "Playing..."
            l.contains("stop") -> "Stopping..."
            l.contains("pars") -> "Preparing..."
            l.contains("send") -> "Sending..."
            else -> label.replaceFirstChar { it.uppercaseChar() } + "..."
        }
    }

    private fun showOrUpdateProgress(message: String) {
        val dlg = progressDialog
        if (dlg != null && dlg.isShowing) {
            progressText?.text = message
            return
        }
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setBackgroundColor(card)
        layout.setPadding(dp(24), dp(24), dp(24), dp(24))
        val pb = ProgressBar(this)
        pb.indeterminateTintList = ColorStateList.valueOf(violet)
        layout.addView(pb)
        val tv = TextView(this)
        tv.text = message
        tv.setTextColor(text)
        tv.textSize = 16f
        tv.setPadding(0, dp(16), 0, 0)
        layout.addView(tv)
        progressText = tv
        progressDialog = AlertDialog.Builder(this)
            .setView(layout)
            .setCancelable(false)
            .create()
        progressDialog?.window?.setBackgroundDrawable(ColorDrawable(card))
        progressDialog?.show()
    }

    private fun showProgress(message: String) {
        progressGen++
        showOrUpdateProgress(message)
    }

    private fun dismissProgress() {
        try {
            progressDialog?.dismiss()
        } catch (e: Exception) {
        }
        progressDialog = null
        progressText = null
    }

    private fun hideProgress() {
        progressGen++
        dismissProgress()
    }

    private fun showProgressError(message: String) {
        progressGen++
        val gen = progressGen
        showOrUpdateProgress(message)
        progressHandler.postDelayed({
            if (progressGen == gen) dismissProgress()
        }, 2500)
    }

    private fun phonesJson(): String {
        val arr = JSONArray()
        for ((name, hp) in phoneTargets) {
            arr.put(JSONObject().put("name", name).put("host", hp.first).put("port", hp.second))
        }
        return arr.toString()
    }

    private fun refreshDevices(updateStatus: Boolean = true) {
        runPy("discovering", { py().callAttr("discover").toString() }, quiet = !updateStatus) { result ->
            try {
                val arr = JSONArray(result)
                runPy("finding phones", { pyPhones().callAttr("discover_phones").toString() }, quiet = !updateStatus) { phoneResult ->
                    try {
                        val phones = JSONArray(phoneResult)
                        phoneTargets.clear()
                        for (i in 0 until phones.length()) {
                            val p = phones.getJSONObject(i)
                            val name = p.optString("name", "?")
                            phoneTargets[name] = Pair(p.optString("host", ""), p.optInt("port", 8766))
                            val entry = JSONObject()
                            entry.put("name", name)
                            entry.put("state", "PHONE")
                            entry.put("title", "Cast Remote")
                            arr.put(entry)
                        }
                        renderDevices(arr)
                        var tvCount = 0
                        var playing: String? = null
                        for (i in 0 until arr.length()) {
                            val dev = arr.getJSONObject(i)
                            if (dev.optString("state", "") == "PHONE") continue
                            tvCount++
                            if (dev.optString("state", "") == "PLAYING") { playing = dev.optString("name", "?") }
                        }
                        val phoneCount = phoneTargets.size
                        val found = StringBuilder("Found ")
                        if (tvCount > 0) found.append(tvCount).append(" TVs")
                        if (tvCount > 0 && phoneCount > 0) found.append(" and ")
                        if (phoneCount > 0) found.append(phoneCount).append(" phones")
                        if (tvCount == 0 && phoneCount == 0) found.append("0 devices")
                        found.append(".")
                        if (playing != null) {
                            found.append(" ").append(playing).append(" is playing - tap Mirror what's playing.")
                        }
                        if (updateStatus) setStatus(found.toString())
                    } catch (e: Exception) {
                        renderDevices(arr)
                        if (updateStatus) setStatus("Found TVs; phone scan failed.")
                    }
                }
            } catch (e: Exception) {
                if (updateStatus) setStatus("Refresh failed: " + e.message)
            }
        }
    }

    private fun togglePin(name: String) {
        if (pinnedDevices.contains(name)) {
            pinnedDevices.remove(name)
            setStatus("Unpinned $name.")
        } else {
            pinnedDevices.add(name)
            setStatus("Pinned $name to top.")
        }
        getSharedPreferences("cast_remote_prefs", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("pinned_device_names", pinnedDevices.toSet())
            .apply()
        lastDevicesArray?.let { renderDevices(it) }
    }

    private fun cardBg(isPinned: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            setColor(card)
            cornerRadius = dp(10).toFloat()
            if (isPinned) setStroke(dp(2), amber)
        }
    }

    private fun renderDevices(arr: JSONArray) {
        lastDevicesArray = arr
        devicesContainer.removeAllViews()
        deviceStatusViews.clear()

        // Sort pinned favorites to the top, alphabetical within each group.
        val sorted = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) {
            sorted.add(arr.getJSONObject(i))
        }
        sorted.sortWith(
            compareByDescending<JSONObject> { pinnedDevices.contains(it.optString("name", "?")) }
                .thenBy { it.optString("name", "?").lowercase() }
        )

        val tvs = mutableListOf<String>()
        for (dev in sorted) {
            if (dev.optString("state", "") != "PHONE") tvs.add(dev.optString("name", "?"))
        }
        spinnerItems.clear()
        if (tvs.isEmpty()) spinnerItems.add("No TVs - tap Refresh casting devices") else spinnerItems.addAll(tvs)
        targetAdapter?.notifyDataSetChanged()

        for (dev in sorted) {
            val name = dev.optString("name", "?")
            val state = dev.optString("state", "?")
            val title = dev.optString("title", "")
            val isPhone = state == "PHONE"
            val isPinned = pinnedDevices.contains(name)
            val row = LinearLayout(this)
            row.orientation = LinearLayout.VERTICAL
            row.background = cardBg(isPinned)
            row.setPadding(dp(12), dp(12), dp(12), dp(12))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(12)
            row.layoutParams = lp

            val headerRow = LinearLayout(this)
            headerRow.orientation = LinearLayout.HORIZONTAL
            headerRow.gravity = Gravity.CENTER_VERTICAL
            val label = TextView(this)
            label.text = name + " - " + state + if (title.isNotEmpty()) " - " + title else ""
            label.setTextColor(if (state == "PLAYING") cyan else if (isPhone) magenta else text)
            label.textSize = 15f
            label.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            headerRow.addView(label)

            val btnPin = Button(this)
            btnPin.text = if (isPinned) "★ Pinned" else "☆ Pin"
            btnPin.setTextColor(if (isPinned) amber else Color.parseColor("#9CA3AF"))
            btnPin.setBackgroundColor(darkBtn)
            btnPin.textSize = 12f
            btnPin.setPadding(dp(10), dp(4), dp(10), dp(4))
            btnPin.minHeight = 0
            btnPin.minimumHeight = 0
            btnPin.setOnClickListener { togglePin(name) }
            headerRow.addView(btnPin)
            row.addView(headerRow)

            if (!isPhone) {
                val modelLine = TextView(this)
                val manu = dev.optString("manufacturer", "")
                val mod = dev.optString("model", "")
                modelLine.text = if (manu.isEmpty() && mod.isEmpty()) "model unknown" else (manu + " " + mod).trim()
                modelLine.setTextColor(Color.parseColor("#9CA3AF"))
                modelLine.textSize = 13f
                modelLine.setPadding(0, dp(2), 0, dp(6))
                row.addView(modelLine)
            }
            val btnRow = LinearLayout(this)
            btnRow.orientation = LinearLayout.HORIZONTAL
            val btnPlay = Button(this)
            btnPlay.text = "Play here"
            btnPlay.setTextColor(Color.WHITE)
            btnPlay.background = gradientBg(intArrayOf(cyan, violet), 10)
            btnPlay.setOnClickListener { playHere(name) }
            val btnMirror = Button(this)
            btnMirror.text = "Mirror here"
            btnMirror.setTextColor(Color.WHITE)
            btnMirror.background = gradientBg(intArrayOf(magenta, violet), 10)
            btnMirror.setOnClickListener { mirrorTargets(JSONArray().put(name)) }
            val buttons = if (isPhone) arrayOf(btnPlay, btnMirror) else arrayOf(btnPlay, btnMirror, Button(this).apply {
                text = "Stop"
                setTextColor(Color.WHITE)
                setBackgroundColor(darkBtn)
                setOnClickListener { stopDevice(name) }
            })
            for (b in buttons) {
                val blp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                blp.rightMargin = dp(4)
                b.layoutParams = blp
                b.setPadding(dp(8), dp(8), dp(8), dp(8))
                btnRow.addView(b)
            }
            row.addView(btnRow)

            if (!isPhone) {
                val host = dev.optString("host", "")
                val isPaired = atvRemote.isPaired(name)
                val powerRow = LinearLayout(this)
                powerRow.orientation = LinearLayout.HORIZONTAL
                val powerLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                powerLp.topMargin = dp(8)
                powerRow.layoutParams = powerLp

                val btnOn = Button(this).apply {
                    text = "Turn On"
                    setTextColor(Color.WHITE)
                    setBackgroundColor(emerald)
                    textSize = 13f
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) }
                    setOnClickListener { powerOn(name, host) }
                }
                val btnOff = Button(this).apply {
                    text = "Turn Off"
                    setTextColor(Color.WHITE)
                    setBackgroundColor(crimson)
                    textSize = 13f
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) }
                    setOnClickListener { powerOff(name, host) }
                }
                val btnPair = Button(this).apply {
                    text = if (isPaired) "Paired ✓" else "Pair TV"
                    setTextColor(if (isPaired) emerald else text)
                    setBackgroundColor(darkBtn)
                    textSize = 12f
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    setOnClickListener { pairTV(name, host) }
                }
                powerRow.addView(btnOn)
                powerRow.addView(btnOff)
                powerRow.addView(btnPair)
                row.addView(powerRow)
            }

            val statusLine = TextView(this)
            statusLine.text = deviceStatus[name] ?: ""
            statusLine.setTextColor(text)
            statusLine.textSize = 12f
            statusLine.setPadding(0, dp(8), 0, 0)
            row.addView(statusLine)
            deviceStatusViews[name] = statusLine
            devicesContainer.addView(row)
        }
    }

    private fun playHere(name: String) {
        val link = linkInput.text.toString().trim()
        if (link.isEmpty()) {
            Toast.makeText(this, "Paste a link first", Toast.LENGTH_SHORT).show()
            setStatus("Paste a link first.")
            setDeviceStatus(name, "Last cast failed: Paste a link first")
            return
        }
        val phone = phoneTargets[name]
        runPy("parsing link", { py().callAttr("parse_link", link).toString() }) { parsed ->
            try {
                val obj = JSONObject(parsed)
                if (!obj.isNull("video_id")) {
                    val videoId = obj.getString("video_id")
                    val start = obj.optInt("start", 0)
                    if (phone != null) {
                        runPy("sending to " + name, { pyPhones().callAttr("send_to_phone", phone.first, phone.second, videoId, start).toString() }) { res ->
                            val o = runCatching { JSONObject(res) }.getOrNull()
                            if (o?.optBoolean("ok", false) == true) {
                                setStatus("Play on $name: ok")
                                setDeviceStatus(name, "Last cast: Playing (YouTube)")
                            } else {
                                val err = o?.optString("error", res) ?: res
                                setStatus("Play on $name failed: $err")
                                setDeviceStatus(name, "Last cast failed: $err")
                            }
                        }
                    } else {
                        runPy("playing on " + name, { py().callAttr("play_on", name, videoId, start).toString() }) { res ->
                            val o = runCatching { JSONObject(res) }.getOrNull()
                            if (o?.optBoolean("ok", false) == true) {
                                setStatus("Play on $name: ok")
                                setDeviceStatus(name, "Last cast: Playing (YouTube)")
                            } else {
                                val err = o?.optString("error", res) ?: res
                                setStatus("Play on $name failed: $err")
                                setDeviceStatus(name, "Last cast failed: $err")
                            }
                            refreshDevices(updateStatus = false)
                        }
                    }
                } else {
                    if (phone != null) {
                        Toast.makeText(this, "Phones can only play YouTube links", Toast.LENGTH_SHORT).show()
                        setStatus("Phones can only play YouTube links.")
                        setDeviceStatus(name, "Last cast failed: Phones can only play YouTube links")
                    } else {
                        runPy("playing link on " + name, { py().callAttr("play_url", name, link).toString() }) { res ->
                            try {
                                val o = JSONObject(res)
                                if (o.optBoolean("ok", false)) {
                                    val st = o.optString("state", "")
                                    val reason = o.optString("idle_reason", "")
                                    if (st.isEmpty()) {
                                        setStatus("Playing link on " + name + ".")
                                        setDeviceStatus(name, "Last cast: Playing")
                                    } else if (st == "IDLE") {
                                        val r = if (reason.isNotEmpty()) " (reason: " + reason + ")" else ""
                                        setStatus("Playing link on " + name + " (receiver: IDLE" + r + ")")
                                        setDeviceStatus(name, "Last cast: receiver IDLE" + r)
                                    } else {
                                        val r = if (reason.isNotEmpty()) ", reason: " + reason else ""
                                        setStatus("Playing link on " + name + " (receiver: " + st + r + ")")
                                        setDeviceStatus(name, "Last cast: Playing (receiver: " + st + r + ")")
                                    }
                                } else {
                                    setStatus("Play link failed: " + o.optString("error", "failed"))
                                    setDeviceStatus(name, "Last cast failed: " + o.optString("error", "failed"))
                                }
                            } catch (e: Exception) {
                                setStatus("Play link: " + res)
                                setDeviceStatus(name, "Last cast failed: " + res)
                            }
                            refreshDevices(updateStatus = false)
                        }
                    }
                }
            } catch (e: Exception) {
                setStatus("Play failed: " + e.message)
                setDeviceStatus(name, "Last cast failed: " + e.message)
            }
        }
    }

    private fun pairTV(name: String, host: String) {
        if (host.isEmpty()) {
            setStatus("No IP address for $name - tap Refresh casting devices first.")
            return
        }
        showProgress("Connecting to $name for PIN pairing...")
        setStatus("Connecting to $name for PIN pairing...")
        uiScope.launch {
            try {
                atvRemote.beginPairing(name, name, host)
                hideProgress()
                showPairingPinDialog(name)
            } catch (e: Exception) {
                showProgressError("Pair failed: ${e.message}")
                setStatus("Pair $name failed: ${e.message}")
                setDeviceStatus(name, "Pair failed: ${e.message}")
            }
        }
    }

    private fun showPairingPinDialog(name: String) {
        val input = EditText(this).apply {
            hint = "e.g. A1B2C3"
            inputType = InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextColor(text)
            setHintTextColor(Color.parseColor("#9CA3AF"))
            setBackgroundColor(bg)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            textSize = 18f
        }
        AlertDialog.Builder(this)
            .setTitle("Pair with $name")
            .setMessage("Enter the 6-character hex code shown on your TV screen to enable real Power On & Power Off.")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Confirm Pair") { _, _ ->
                val pin = input.text.toString().trim()
                showProgress("Verifying PIN...")
                uiScope.launch {
                    try {
                        atvRemote.completePairing(pin)
                        hideProgress()
                        setStatus("Paired with $name! Turn On & Turn Off are now enabled.")
                        setDeviceStatus(name, "Paired for TV Power")
                        lastDevicesArray?.let { renderDevices(it) }
                    } catch (e: Exception) {
                        showProgressError("PIN failed: ${e.message}")
                        setStatus("Pairing $name failed: ${e.message}")
                        atvRemote.cancelPairing()
                    }
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                atvRemote.cancelPairing()
                setStatus("Pairing cancelled for $name.")
            }
            .show()
    }

    private fun powerOn(name: String, host: String) {
        if (host.isEmpty()) {
            setStatus("No host for $name - tap Refresh casting devices first.")
            return
        }
        if (atvRemote.isPaired(name)) {
            showProgress("Turning on $name...")
            uiScope.launch {
                try {
                    atvRemote.sendPower(name, host, TVPowerAction.POWER_ON)
                    hideProgress()
                    setStatus("Turned on $name.")
                    setDeviceStatus(name, "Power: ON")
                    return@launch
                } catch (_: Exception) {
                    hideProgress()
                    // Fall through to Cast HDMI-CEC wake if TV remote socket is asleep.
                }
                wakeViaCastFallback(name)
            }
        } else {
            wakeViaCastFallback(name)
        }
    }

    private fun wakeViaCastFallback(name: String) {
        runPy("waking " + name, { py().callAttr("wake_device", name).toString() }) { res ->
            val o = runCatching { JSONObject(res) }.getOrNull()
            if (o?.optBoolean("ok", false) == true) {
                setStatus("Woke $name via Cast (tap Pair TV for full TV Power On/Off).")
                setDeviceStatus(name, "Power: Woke via Cast")
            } else {
                val err = o?.optString("error", res) ?: res
                setStatus("Turn on $name failed: $err")
                setDeviceStatus(name, "Power on failed: $err")
            }
        }
    }

    private fun powerOff(name: String, host: String) {
        if (host.isEmpty()) {
            setStatus("No host for $name - tap Refresh casting devices first.")
            return
        }
        if (atvRemote.isPaired(name)) {
            showProgress("Turning off $name...")
            uiScope.launch {
                try {
                    atvRemote.sendPower(name, host, TVPowerAction.POWER_OFF)
                    hideProgress()
                    setStatus("Turned off $name.")
                    setDeviceStatus(name, "Power: OFF")
                } catch (e: Exception) {
                    showProgressError("Power off failed: ${e.message}")
                    setStatus("Turn off $name failed: ${e.message}")
                    setDeviceStatus(name, "Power off failed: ${e.message}")
                }
            }
            return
        }

        // Not paired yet: try Android TV Remote v2 pairing first; if unsupported (legacy Chromecast),
        // fall back to ADB sleep and then Cast stop-to-idle.
        showProgress("Checking $name for TV power support...")
        uiScope.launch {
            try {
                atvRemote.beginPairing(name, name, host)
                hideProgress()
                showPairingPinDialog(name)
                return@launch
            } catch (_: Exception) {
                hideProgress()
            }
            runPy("stopping " + name, { py().callAttr("stop_device", name).toString() }) { res ->
                val o = runCatching { JSONObject(res) }.getOrNull()
                if (o?.optBoolean("ok", false) == true) {
                    setStatus("Stopped $name to idle (legacy Chromecast without Android TV has no TV power-off API).")
                    setDeviceStatus(name, "Stopped to idle")
                } else {
                    setStatus("Power off $name failed: ${o?.optString("error", res) ?: res}")
                }
            }
        }
    }

    private fun mirrorTargets(targets: JSONArray) {
        runPy("mirroring", { pyPhones().callAttr("mirror_with_phones", targets.toString(), phonesJson()).toString() }) { res ->
            try {
                val obj = JSONObject(res)
                if (obj.optBoolean("ok", false)) {
                    val tvs = obj.optInt("tvs", 0)
                    val phones = obj.optInt("phones", 0)
                    val parts = mutableListOf<String>()
                    if (tvs > 0) parts.add(tvs.toString() + " TVs")
                    if (phones > 0) parts.add(phones.toString() + " phones")
                    val dest = if (parts.isEmpty()) "nothing" else parts.joinToString(" and ")
                    setStatus("Mirroring from " + obj.optString("source", "?") + " to " + dest + ".")
                    for (n in mirrorTargetNames(targets)) setDeviceStatus(n, "Mirror: started")
                    refreshDevices(updateStatus = false)
                } else {
                    val err = obj.optString("error", "failed")
                    if (err.contains("nothing playing")) {
                        setStatus("Nothing playing to mirror. Start a video on one TV first, or paste a link below.")
                    } else if (err.contains("doesn't share")) {
                        setStatus(err)
                    } else {
                        setStatus("Mirror failed: " + err)
                    }
                    for (n in mirrorTargetNames(targets)) setDeviceStatus(n, "Mirror failed: " + err)
                }
            } catch (e: Exception) {
                setStatus("Mirror: " + res)
                for (n in mirrorTargetNames(targets)) setDeviceStatus(n, "Mirror failed: " + res)
            }
        }
    }

    private fun selectedTv(): String? {
        val sel = targetSpinner.selectedItem as? String
        if (sel == null || sel == "No TVs - tap Refresh casting devices") return null
        return sel
    }

    private fun playAnyLink() {
        val link = linkInput.text.toString().trim()
        if (link.isEmpty()) {
            Toast.makeText(this, "Paste a link first", Toast.LENGTH_SHORT).show()
            setStatus("Paste a link first.")
            selectedTv()?.let { setDeviceStatus(it, "Last cast failed: Paste a link first") }
            return
        }
        val target = selectedTv()
        if (target == null) {
            Toast.makeText(this, "Refresh casting devices to find TVs first", Toast.LENGTH_SHORT).show()
            setStatus("Refresh casting devices to find TVs first.")
            return
        }
        runPy("parsing link", { py().callAttr("parse_link", link).toString() }) { parsed ->
            try {
                val obj = JSONObject(parsed)
                if (!obj.isNull("video_id")) {
                    val videoId = obj.getString("video_id")
                    val start = obj.optInt("start", 0)
                    runPy("playing on " + target, { py().callAttr("play_on", target, videoId, start).toString() }) { res ->
                        val o = runCatching { JSONObject(res) }.getOrNull()
                        if (o?.optBoolean("ok", false) == true) {
                            setStatus("Play on $target: ok")
                            setDeviceStatus(target, "Last cast: Playing (YouTube)")
                        } else {
                            val err = o?.optString("error", res) ?: res
                            setStatus("Play on $target failed: $err")
                            setDeviceStatus(target, "Last cast failed: $err")
                        }
                        refreshDevices(updateStatus = false)
                    }
                } else {
                    runPy("playing link on " + target, { py().callAttr("play_url", target, link).toString() }) { res ->
                        try {
                            val o = JSONObject(res)
                            if (o.optBoolean("ok", false)) {
                                val st = o.optString("state", "")
                                val reason = o.optString("idle_reason", "")
                                if (st.isEmpty()) {
                                    setStatus("Playing link on " + target + ".")
                                    setDeviceStatus(target, "Last cast: Playing")
                                } else if (st == "IDLE") {
                                    val r = if (reason.isNotEmpty()) " (reason: " + reason + ")" else ""
                                    setStatus("Playing link on " + target + " (receiver: IDLE" + r + ")")
                                    setDeviceStatus(target, "Last cast: receiver IDLE" + r)
                                } else {
                                    val r = if (reason.isNotEmpty()) ", reason: " + reason else ""
                                    setStatus("Playing link on " + target + " (receiver: " + st + r + ")")
                                    setDeviceStatus(target, "Last cast: Playing (receiver: " + st + r + ")")
                                }
                            } else {
                                setStatus("Play link failed: " + o.optString("error", "failed"))
                                setDeviceStatus(target, "Last cast failed: " + o.optString("error", "failed"))
                            }
                        } catch (e: Exception) {
                            setStatus("Play link: " + res)
                            setDeviceStatus(target, "Last cast failed: " + res)
                        }
                        refreshDevices(updateStatus = false)
                    }
                }
            } catch (e: Exception) {
                setStatus("Play link failed: " + e.message)
                setDeviceStatus(target, "Last cast failed: " + e.message)
            }
        }
    }

    private fun stopDevice(name: String) {
        runPy("stopping " + name, { py().callAttr("stop_device", name).toString() }) { res ->
            setStatus("Stop " + name + ": " + res)
            setDeviceStatus(name, "Stopped")
            refreshDevices(updateStatus = false)
        }
    }

    private fun stopAll() {
        runPy("stopping all", { py().callAttr("stop_all").toString() }) { res ->
            setStatus("Stop all: " + res)
            for (n in spinnerItems) {
                if (n != "No TVs - tap Refresh casting devices") setDeviceStatus(n, "Stopped")
            }
            for (n in phoneTargets.keys) setDeviceStatus(n, "Stopped")
            refreshDevices(updateStatus = false)
        }
    }

    private fun toggleReceiver() {
        if (receiverOn) stopReceiver() else startReceiver()
    }

    private fun startReceiver() {
        try {
            val server = ServerSocket(8766)
            receiverServer = server
            receiverThread = Thread {
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        val socket = server.accept()
                        receiverPool.execute { handleCastRequest(socket) }
                    } catch (e: IOException) {
                        break
                    } catch (e: Exception) {
                        break
                    }
                }
            }
            receiverThread?.start()
            nsdManager = applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "Cast Remote Control " + Build.MODEL
                serviceType = "_castremote._tcp."
                port = 8766
            }
            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {}
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
                override fun onServiceUnregistered(info: NsdServiceInfo) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            }
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
            receiverOn = true
            btnReceive.text = "Stop receiving"
            setStatus("Receiving as Cast Remote Control " + Build.MODEL + " - keep this app open.")
        } catch (e: Exception) {
            setStatus("Could not start receiving: " + e.message)
        }
    }

    private fun stopReceiver() {
        try {
            registrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (e: Exception) {
        }
        try {
            receiverServer?.close()
        } catch (e: Exception) {
        }
        receiverThread?.interrupt()
        receiverServer = null
        receiverThread = null
        registrationListener = null
        receiverOn = false
        btnReceive.text = "Receive on this phone"
        setStatus("Not receiving.")
    }

    private fun handleCastRequest(socket: Socket) {
        try {
            socket.soTimeout = 4000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: ""
            var contentLength = 0
            var line: String?
            while (true) {
                line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.lowercase().startsWith("content-length:")) {
                    contentLength = (line.substringAfter(":").trim().toIntOrNull() ?: 0).coerceIn(0, 65536)
                }
            }
            var body = ""
            if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                body = String(buf, 0, read)
            }
            val out = socket.getOutputStream()
            if (requestLine.startsWith("POST /play")) {
                try {
                    val obj = JSONObject(body)
                    val videoId = obj.optString("video_id", "").trim()
                    val start = maxOf(0, obj.optInt("start", 0))
                    if (videoId.matches(Regex("^[A-Za-z0-9_-]{1,32}$"))) {
                        runOnUiThread {
                            setStatus("Incoming cast: " + videoId)
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + videoId + "&t=" + start + "s")))
                            } catch (e: Exception) {
                                Toast.makeText(this, "No app to play YouTube links", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                }
                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray())
            } else if (requestLine.startsWith("GET /ping")) {
                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray())
            } else {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            }
            out.flush()
        } catch (e: Exception) {
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
            }
        }
    }
}


