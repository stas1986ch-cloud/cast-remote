/**
 * @file CastController.kt
 * @summary Coordinates mDNS discovery, favorite pinning, parallel Cast/Mirror/Stop, PhoneReceiver, and Android TV power control.
 * @description Uses Android NsdManager with a WifiManager MulticastLock to discover Chromecasts and peer phones, persists pinned IDs in SharedPreferences, and exposes reactive Compose state.
 * @testing Verified for thread-safe state updates, pinned-first sorting, and fallback behavior between Android TV Remote v2 and Cast v2.
 */
package com.screencontrol.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class CastDeviceItem(
    val id: String,
    val name: String,
    val state: String,
    val title: String,
    val contentId: String?,
    val position: Double,
    val isPhone: Boolean,
    val host: String,
    val port: Int,
    val isPinned: Boolean = false,
    val isPowerOn: Boolean? = null,
    val isPaired: Boolean = false
)

class CastController(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val PREFS_NAME = "cast_remote_prefs"
        private const val KEY_PINNED = "pinned_device_ids"
        private const val CAST_SERVICE_TYPE = "_googlecast._tcp."
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    val atvRemote = AndroidTVRemoteManager(context)
    val phoneReceiver = PhoneReceiver(context, scope) { videoId ->
        scope.launch(Dispatchers.Main) { lastIncomingVideoId = videoId }
    }

    var devices by mutableStateOf<List<CastDeviceItem>>(emptyList())
        private set
    var status by mutableStateOf("Tap Refresh to find TVs.")
        private set
    var isBusy by mutableStateOf(false)
        private set
    var isReceiving by mutableStateOf(false)
        private set
    var receiveName by mutableStateOf("")
        private set
    var lastIncomingVideoId by mutableStateOf<String?>(null)
        private set
    var pairingTarget by mutableStateOf<PairingTarget?>(null)
        private set
    var pairingError by mutableStateOf<String?>(null)
        private set

    private val castMap = ConcurrentHashMap<String, CastDeviceItem>()
    private val phoneMap = ConcurrentHashMap<String, CastDeviceItem>()
    private val powerStates = ConcurrentHashMap<String, Boolean>()
    private var pinnedIds: MutableSet<String> =
        prefs.getStringSet(KEY_PINNED, emptySet())?.toMutableSet() ?: mutableSetOf()

    private var castListener: NsdManager.DiscoveryListener? = null
    private var phoneListener: NsdManager.DiscoveryListener? = null
    private var refreshJob: Job? = null

    fun togglePin(device: CastDeviceItem) {
        if (pinnedIds.contains(device.id)) {
            pinnedIds.remove(device.id)
            status = "Unpinned ${device.name}."
        } else {
            pinnedIds.add(device.id)
            status = "Pinned ${device.name} to top."
        }
        prefs.edit().putStringSet(KEY_PINNED, pinnedIds.toSet()).apply()
        rebuildDevices()
    }

    fun toggleReceiving() {
        if (phoneReceiver.isReceiving) {
            phoneReceiver.stopReceiving()
            isReceiving = false
            receiveName = ""
        } else {
            val ok = phoneReceiver.startReceiving()
            isReceiving = ok
            receiveName = if (ok) phoneReceiver.receiveName else ""
            if (!ok) status = "Could not start phone receiver on port 8766."
        }
    }

    fun refresh() {
        stopDiscovery()
        status = "Working: discovering..."
        acquireMulticastLock()

        castListener = startBrowse(CAST_SERVICE_TYPE) { info ->
            @Suppress("DEPRECATION")
            val host = info.host?.hostAddress ?: return@startBrowse
            val attrs = info.attributes
            val friendly = attrs["fn"]?.let { String(it, Charsets.UTF_8) }?.takeIf { it.isNotBlank() }
                ?: info.serviceName
            val uid = attrs["id"]?.let { String(it, Charsets.UTF_8) }?.takeIf { it.isNotBlank() }
                ?: info.serviceName
            val existing = castMap[uid]
            castMap[uid] = CastDeviceItem(
                id = uid,
                name = friendly,
                state = existing?.state ?: "IDLE",
                title = existing?.title ?: "",
                contentId = existing?.contentId,
                position = existing?.position ?: 0.0,
                isPhone = false,
                host = host,
                port = if (info.port > 0) info.port else 8009
            )
            scope.launch(Dispatchers.Main) { rebuildDevices() }
        }

        phoneListener = startBrowse(PhoneReceiver.SERVICE_TYPE) { info ->
            @Suppress("DEPRECATION")
            val host = info.host?.hostAddress ?: return@startBrowse
            val name = info.serviceName
            val id = "phone:$name"
            phoneMap[id] = CastDeviceItem(
                id = id,
                name = name,
                state = "PHONE",
                title = "Cast Remote",
                contentId = null,
                position = 0.0,
                isPhone = true,
                host = host,
                port = if (info.port > 0) info.port else PhoneReceiver.PORT
            )
            scope.launch(Dispatchers.Main) { rebuildDevices() }
        }

        refreshJob?.cancel()
        refreshJob = scope.launch {
            delay(5000)
            stopDiscovery()
            // Query live media state on discovered TVs in parallel.
            val tvs = castMap.values.toList()
            tvs.map { tv ->
                async(Dispatchers.IO) {
                    val st = runCatching { CastV2Client.getMediaStatus(tv.host, tv.port) }.getOrNull()
                    if (st != null) {
                        castMap[tv.id] = tv.copy(
                            state = st.state,
                            title = st.title,
                            contentId = st.contentId,
                            position = st.position
                        )
                    }
                }
            }.awaitAll()
            rebuildDevices()
            updateStatusSummary()
        }
    }

    @Suppress("DEPRECATION")
    private fun startBrowse(serviceType: String, onResolved: (NsdServiceInfo) -> Unit): NsdManager.DiscoveryListener {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                runCatching {
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            onResolved(serviceInfo)
                        }
                    })
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                runCatching { nsdManager.stopServiceDiscovery(this) }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        runCatching { nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener) }
        return listener
    }

    private fun stopDiscovery() {
        castListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        phoneListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        castListener = null
        phoneListener = null
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        runCatching {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("CastRemoteNsd")?.apply {
                    setReferenceCounted(false)
                }
            }
            multicastLock?.acquire()
        }
    }

    private fun releaseMulticastLock() {
        runCatching { if (multicastLock?.isHeld == true) multicastLock?.release() }
    }

    private fun rebuildDevices() {
        val paired = atvRemote.getPairedIds()
        val combined = mutableListOf<CastDeviceItem>()
        for (tv in castMap.values) {
            combined.add(
                tv.copy(
                    isPinned = pinnedIds.contains(tv.id),
                    isPowerOn = powerStates[tv.id],
                    isPaired = paired.contains(tv.id)
                )
            )
        }
        for (ph in phoneMap.values) {
            combined.add(ph.copy(isPinned = pinnedIds.contains(ph.id)))
        }
        combined.sortWith(
            compareByDescending<CastDeviceItem> { it.isPinned }
                .thenBy { it.name.lowercase() }
        )
        devices = combined
    }

    private fun updateStatusSummary() {
        val tvCount = castMap.size
        val phoneCount = phoneMap.size
        val text = buildString {
            append("Found ")
            if (tvCount > 0) append("$tvCount TVs")
            if (tvCount > 0 && phoneCount > 0) append(" and ")
            if (phoneCount > 0) append("$phoneCount phones")
            if (tvCount == 0 && phoneCount == 0) append("0 devices")
            append(".")
            devices.firstOrNull { it.state == "PLAYING" }?.let {
                append(" ${it.name} is playing - tap Mirror what's playing.")
            }
        }
        status = text
    }

    // MARK: - Power Control & Pairing

    fun pairTV(device: CastDeviceItem) {
        if (device.isPhone || isBusy) return
        scope.launch {
            isBusy = true
            status = "Connecting to ${device.name} for PIN pairing..."
            try {
                val target = atvRemote.beginPairing(device.id, device.name, device.host)
                pairingError = null
                pairingTarget = target
                status = "Enter the 6-character code shown on ${device.name}."
            } catch (e: Exception) {
                status = "Pair ${device.name} failed: ${e.message}"
            } finally {
                isBusy = false
            }
        }
    }

    fun submitPairingPIN(pin: String) {
        scope.launch {
            try {
                val name = pairingTarget?.name ?: "TV"
                atvRemote.completePairing(pin)
                pairingTarget = null
                pairingError = null
                status = "Paired with $name! Power On/Off is now enabled."
                rebuildDevices()
            } catch (e: Exception) {
                pairingError = e.message ?: "Pairing failed"
            }
        }
    }

    fun cancelPairing() {
        atvRemote.cancelPairing()
        pairingTarget = null
        pairingError = null
    }

    fun powerOn(device: CastDeviceItem) {
        if (device.isPhone || isBusy) return
        scope.launch {
            isBusy = true
            try {
                if (atvRemote.isPaired(device.id)) {
                    status = "Turning on ${device.name}..."
                    val ok = runCatching {
                        atvRemote.sendPower(device.id, device.host, TVPowerAction.POWER_ON)
                    }
                    if (ok.isSuccess) {
                        powerStates[device.id] = true
                        status = "Turned on ${device.name}."
                        rebuildDevices()
                        return@launch
                    }
                }
                status = "Waking ${device.name} via Cast..."
                CastV2Client.wakeViaCast(device.host, device.port)
                powerStates[device.id] = true
                rebuildDevices()
                status = if (atvRemote.isPaired(device.id)) {
                    "Woke ${device.name} via Cast."
                } else {
                    "Woke ${device.name} via Cast. Tap Pair TV for full Power Off."
                }
            } catch (e: Exception) {
                status = "Turn on ${device.name} failed: ${e.message}"
            } finally {
                isBusy = false
            }
        }
    }

    fun powerOff(device: CastDeviceItem) {
        if (device.isPhone || isBusy) return
        scope.launch {
            isBusy = true
            try {
                if (atvRemote.isPaired(device.id)) {
                    status = "Turning off ${device.name}..."
                    atvRemote.sendPower(device.id, device.host, TVPowerAction.POWER_OFF)
                    powerStates[device.id] = false
                    castMap[device.id]?.let { castMap[device.id] = it.copy(state = "IDLE", title = "") }
                    rebuildDevices()
                    status = "Turned off ${device.name}."
                    return@launch
                }

                // Try initiating Android TV PIN pairing; fall back to Cast Stop if not Android TV.
                status = "Checking ${device.name} for TV power support..."
                val pairAttempt = runCatching { atvRemote.beginPairing(device.id, device.name, device.host) }
                if (pairAttempt.isSuccess) {
                    pairingError = null
                    pairingTarget = pairAttempt.getOrNull()
                    status = "Enter the 6-character code on ${device.name} to enable Power Off."
                    return@launch
                }

                CastV2Client.stopApp(device.host, device.port)
                powerStates[device.id] = false
                castMap[device.id]?.let { castMap[device.id] = it.copy(state = "IDLE", title = "") }
                rebuildDevices()
                status = "Stopped ${device.name} to idle (legacy Chromecast has no TV power-off API)."
            } catch (e: Exception) {
                status = "Power off ${device.name} failed: ${e.message}"
            } finally {
                isBusy = false
            }
        }
    }

    // MARK: - Play / Mirror / Stop

    fun playHere(device: CastDeviceItem, rawLink: String) {
        val parsed = YouTubeLink.parse(rawLink)
        val videoId = parsed.videoId
        if (videoId == null) {
            status = "No video id in link."
            return
        }
        if (isBusy) return
        scope.launch {
            isBusy = true
            status = "Working: playing on ${device.name}..."
            try {
                if (device.isPhone) {
                    phoneReceiver.sendToPhone(device.host, device.port, videoId, parsed.startSeconds)
                } else {
                    val screenId = CastV2Client.launchYouTubeAndGetScreenId(device.host, device.port)
                    YouTubeLoungeService(screenId).playVideo(videoId, startTime = parsed.startSeconds.toString())
                    castMap[device.id]?.let {
                        castMap[device.id] = it.copy(state = "PLAYING", contentId = videoId)
                    }
                    rebuildDevices()
                }
                status = "Play on ${device.name}: ok"
            } catch (e: Exception) {
                status = "Play on ${device.name} failed: ${e.message}"
            } finally {
                isBusy = false
            }
        }
    }

    fun mirror(target: CastDeviceItem?) {
        if (isBusy) return
        scope.launch {
            isBusy = true
            status = "Working: mirroring..."
            try {
                val source = findPlayingSource()
                val videoId = source?.contentId
                if (source == null || videoId == null) {
                    status = "Nothing playing to mirror. Start a video on one TV first, or paste a link below."
                    return@launch
                }
                val startSec = source.position.toInt()
                val targets = if (target != null) {
                    listOf(target)
                } else {
                    devices.filter { it.id != source.id && !it.name.lowercase().endsWith("group") }
                }

                var tvCount = 0
                var phoneCount = 0
                val results = targets.map { dev ->
                    async(Dispatchers.IO) {
                        runCatching {
                            if (dev.isPhone) {
                                phoneReceiver.sendToPhone(dev.host, dev.port, videoId, startSec)
                                "phone"
                            } else {
                                val screenId = CastV2Client.launchYouTubeAndGetScreenId(dev.host, dev.port)
                                YouTubeLoungeService(screenId).playVideo(videoId, startTime = startSec.toString())
                                "tv"
                            }
                        }.getOrNull()
                    }
                }.awaitAll()

                for (r in results) {
                    if (r == "tv") tvCount++ else if (r == "phone") phoneCount++
                }
                val parts = mutableListOf<String>()
                if (tvCount > 0) parts.add("$tvCount TVs")
                if (phoneCount > 0) parts.add("$phoneCount phones")
                val dest = if (parts.isEmpty()) "nothing" else parts.joinToString(" and ")
                status = "Mirroring from ${source.name} to $dest."
            } catch (e: Exception) {
                status = "Mirror failed: ${e.message}"
            } finally {
                isBusy = false
            }
        }
    }

    private suspend fun findPlayingSource(): CastDeviceItem? = withContext(Dispatchers.IO) {
        for (tv in castMap.values) {
            val st = runCatching { CastV2Client.getMediaStatus(tv.host, tv.port) }.getOrNull()
            if (st != null && st.contentId != null && st.state in setOf("PLAYING", "PAUSED", "BUFFERING")) {
                val updated = tv.copy(
                    state = st.state,
                    title = st.title,
                    contentId = st.contentId,
                    position = st.position
                )
                castMap[tv.id] = updated
                return@withContext updated
            }
        }
        null
    }

    fun stop(device: CastDeviceItem) {
        if (device.isPhone || isBusy) return
        scope.launch {
            isBusy = true
            status = "Working: stopping ${device.name}..."
            try {
                CastV2Client.stopApp(device.host, device.port)
                castMap[device.id]?.let { castMap[device.id] = it.copy(state = "IDLE", title = "") }
                rebuildDevices()
                status = "Stop ${device.name}: ok"
            } catch (e: Exception) {
                status = "Stop ${device.name} failed: ${e.message}"
            } finally {
                isBusy = false
            }
        }
    }

    fun stopAll() {
        if (isBusy) return
        scope.launch {
            isBusy = true
            status = "Working: stopping all..."
            try {
                castMap.values.map { tv ->
                    async(Dispatchers.IO) {
                        runCatching { CastV2Client.stopApp(tv.host, tv.port) }
                        castMap[tv.id] = tv.copy(state = "IDLE", title = "")
                    }
                }.awaitAll()
                rebuildDevices()
                status = "Stop all: ok"
            } finally {
                isBusy = false
            }
        }
    }

    fun cleanup() {
        stopDiscovery()
        phoneReceiver.stopReceiving()
        atvRemote.cancelPairing()
    }
}
