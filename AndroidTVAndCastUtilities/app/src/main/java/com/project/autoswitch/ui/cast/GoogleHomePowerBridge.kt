/**
 * @file GoogleHomePowerBridge.kt
 * @summary Safe Kotlin-to-Java bridge for Google Home SDK OnOff.on()/off() with crash-proof permission handling and fallback latching.
 * @description Creates/binds HomeClient per-Activity in onCreate, serializes permission consent with an AtomicBoolean, catches all Throwables, and latches consent failure to prevent login kick-out loops.
 * @testing Compiled and lint-checked in Gradle signed assembleRelease (v2.3).
 */
package com.project.autoswitch.ui.cast

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
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object GoogleHomePowerBridge {

    fun interface PowerCallback {
        fun onResult(success: Boolean, message: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val consentInFlight = AtomicBoolean(false)
    private val devicesByLowerName = ConcurrentHashMap<String, HomeDevice>()

    @Volatile private var boundClient: HomeClient? = null
    @Volatile private var boundActivityRef: WeakReference<ComponentActivity>? = null
    @Volatile private var lastSyncMs = 0L
    @Volatile private var homeConsentFailedReason: String? = null

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

    @JvmStatic
    @Synchronized
    fun registerActivity(activity: ComponentActivity) {
        try {
            val config = HomeConfig(
                coroutineContext = Dispatchers.IO,
                factoryRegistry = buildRegistry(),
                homePlatformScope = HomeConfig.HomePlatformScope.HOME_PLATFORM_SCOPE_VERSION_1
            )
            val hc = Home.getClient(activity.applicationContext, homeConfig = config)
            hc.registerActivityResultCallerForPermissions(activity)
            boundClient = hc
            boundActivityRef = WeakReference(activity)
        } catch (t: Throwable) {
            boundClient = null
            homeConsentFailedReason = "Google Home init unavailable: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    @JvmStatic
    @Synchronized
    fun unregisterActivity(activity: ComponentActivity) {
        if (boundActivityRef?.get() === activity) {
            boundActivityRef = null
            boundClient = null
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
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private suspend fun ensureSynced(activity: ComponentActivity, forceRefresh: Boolean): String? {
        homeConsentFailedReason?.let { return it }
        val hc = boundClient ?: return "Google Home client not ready"
        if (boundActivityRef?.get() !== activity || activity.isFinishing || activity.isDestroyed) {
            return "Activity no longer active"
        }

        val permState = withTimeout(10_000) {
            hc.hasPermissions().first { it != PermissionsState.PERMISSIONS_STATE_UNINITIALIZED }
        }
        if (permState != PermissionsState.GRANTED) {
            if (!consentInFlight.compareAndSet(false, true)) {
                return "Google Home sign-in already in progress"
            }
            try {
                val res = withContext(Dispatchers.Main) {
                    if (activity.isFinishing || activity.isDestroyed) {
                        throw IllegalStateException("Activity closed")
                    }
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
                    val reason = "Google Home login not authorized (${res.status})"
                    homeConsentFailedReason = reason
                    return reason
                }
            } catch (t: Throwable) {
                val reason = "Google Home login unavailable (${t.message ?: t.javaClass.simpleName})"
                homeConsentFailedReason = reason
                return reason
            } finally {
                consentInFlight.set(false)
            }
        }

        val stale = (System.currentTimeMillis() - lastSyncMs) > 5 * 60 * 1000L
        if (forceRefresh || devicesByLowerName.isEmpty() || stale) {
            val structures = withTimeout(10_000) { hc.structures().first() }
            for (s in structures) {
                try {
                    val devs = withTimeout(10_000) { s.devices().first() }
                    for (d in devs) {
                        devicesByLowerName[d.name.trim().lowercase()] = d
                    }
                } catch (_: Throwable) {
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
            } catch (t: Throwable) {
                callback.onResult(false, t.message ?: t.javaClass.simpleName)
            }
        }
    }
}
