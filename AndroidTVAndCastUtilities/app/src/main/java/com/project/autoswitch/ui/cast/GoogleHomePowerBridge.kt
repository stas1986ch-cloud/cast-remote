/**
 * @file GoogleHomePowerBridge.kt
 * @summary Kotlin-to-Java bridge exposing Google Home SDK OnOff.on()/off() TV power control to CastRemoteActivity.
 * @description Initializes HomeClient with CastingVideoPlayerDevice, GoogleTVDevice, and OnOff traits, caches live HomeDevice entries, and provides callback-based setPower for Java callers.
 * @testing Compiled with AGP 9.1.0 and Google Home SDK 17.1.0 via Gradle release APK build.
 */
package com.project.autoswitch.ui.cast

import android.content.Context
import androidx.activity.ComponentActivity
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

object GoogleHomePowerBridge {

    fun interface PowerCallback {
        fun onResult(success: Boolean, message: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: HomeClient? = null
    private val devicesByLowerName = ConcurrentHashMap<String, HomeDevice>()
    @Volatile private var lastSyncMs = 0L

    private fun buildRegistry(): FactoryRegistry = FactoryRegistry(
        types = listOf(
            CastingVideoPlayerDevice,
            GoogleTVDevice,
            GoogleDisplayDevice,
            SpeakerDevice,
            OnOffLightDevice,
            OnOffPluginUnitDevice,
            OnOffLightSwitchDevice,
            DimmableLightDevice,
            ExtendedColorLightDevice,
            ColorTemperatureLightDevice,
            FanDevice,
            GenericSwitchDevice,
            RootNodeDevice
        ),
        traits = listOf(
            OnOff,
            MediaPlayback,
            MediaInput,
            BasicInformation,
            AudioOutput,
            LevelControl,
            KeypadInput,
            WakeOnLan,
            LowPower,
            ApplicationLauncher,
            Volume
        )
    )

    @Synchronized
    private fun getClient(context: Context): HomeClient {
        client?.let { return it }
        val config = HomeConfig(
            coroutineContext = Dispatchers.IO,
            factoryRegistry = buildRegistry(),
            homePlatformScope = HomeConfig.HomePlatformScope.HOME_PLATFORM_SCOPE_VERSION_1
        )
        return Home.getClient(context.applicationContext, homeConfig = config).also {
            client = it
        }
    }

    @JvmStatic
    fun registerActivity(activity: ComponentActivity) {
        try {
            getClient(activity).registerActivityResultCallerForPermissions(activity)
        } catch (_: Exception) {
        }
    }

    private suspend fun findOnOff(device: HomeDevice): OnOff? {
        val tries: List<suspend () -> OnOff?> = listOf(
            { withTimeout(3_000) { device.typeOrNull(CastingVideoPlayerDevice).firstOrNull() }?.standardTraits?.onOff },
            { withTimeout(3_000) { device.typeOrNull(GoogleTVDevice).firstOrNull() }?.standardTraits?.onOff },
            { withTimeout(3_000) { device.typeOrNull(GoogleDisplayDevice).firstOrNull() }?.standardTraits?.onOff },
            { withTimeout(3_000) { device.typeOrNull(OnOffLightDevice).firstOrNull() }?.standardTraits?.onOff },
            { withTimeout(3_000) { device.typeOrNull(OnOffPluginUnitDevice).firstOrNull() }?.standardTraits?.onOff },
            { withTimeout(3_000) { device.typeOrNull(FanDevice).firstOrNull() }?.standardTraits?.onOff }
        )
        for (t in tries) {
            try {
                val v = t()
                if (v != null) return v
            } catch (_: Exception) {
            }
        }
        return null
    }

    private suspend fun ensureSynced(activity: ComponentActivity, forceRefresh: Boolean): String? {
        val hc = getClient(activity)
        val permState = withTimeout(12_000) {
            hc.hasPermissions().first { it != PermissionsState.PERMISSIONS_STATE_UNINITIALIZED }
        }
        if (permState != PermissionsState.GRANTED) {
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
            if (res.status != PermissionsResultStatus.SUCCESS) {
                return "Google Home permission not granted (${res.status})"
            }
        }
        val stale = (System.currentTimeMillis() - lastSyncMs) > 5 * 60 * 1000L
        if (forceRefresh || devicesByLowerName.isEmpty() || stale) {
            val structures = withTimeout(12_000) { hc.structures().first() }
            for (s in structures) {
                try {
                    val devs = withTimeout(12_000) { s.devices().first() }
                    for (d in devs) {
                        devicesByLowerName[d.name.trim().lowercase()] = d
                    }
                } catch (_: Exception) {
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

    @JvmStatic
    fun setPower(activity: ComponentActivity, deviceName: String, turnOn: Boolean, callback: PowerCallback) {
        scope.launch {
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
                callback.onResult(true, dev.name)
            } catch (e: Exception) {
                callback.onResult(false, e.message ?: "Google Home power error")
            }
        }
    }
}
