/**
 * @file GoogleHomePowerManager.kt
 * @summary Shared Google Home SDK power controller executing real TV Turn On (onOff.on) and Turn Off (onOff.off) commands.
 * @description Wraps Google HomeClient with CastingVideoPlayerDevice, GoogleTVDevice, and smart-home OnOff traits, caches live HomeDevice instances by name, and requests Home permissions when needed.
 * @testing Compiled with Google Home SDK 17.1.0 via Gradle 8.9 and verified against HomeDevicesActivity's OnOff trait resolution pattern.
 */
package com.screencontrol.cast

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

object GoogleHomePowerManager {

    private var client: HomeClient? = null
    private val devicesByLowerName = ConcurrentHashMap<String, HomeDevice>()
    @Volatile private var lastSyncMs = 0L

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

    @Synchronized
    fun getClient(context: Context): HomeClient {
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

    fun registerActivity(activity: ComponentActivity) {
        try {
            getClient(activity).registerActivityResultCallerForPermissions(activity)
        } catch (e: Exception) {
            DiagLog.log(activity, "GoogleHomePowerManager registerActivity: ${e.message}")
        }
    }

    fun cacheLiveDevices(devices: Collection<HomeDevice>) {
        for (d in devices) {
            devicesByLowerName[d.name.trim().lowercase()] = d
        }
        if (devices.isNotEmpty()) {
            lastSyncMs = System.currentTimeMillis()
        }
    }

    suspend fun findOnOff(device: HomeDevice): OnOff? {
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
            } catch (_: Exception) {
            }
        }
        return null
    }

    private suspend fun ensureSynced(activity: ComponentActivity, forceRefresh: Boolean = false): String? {
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
            val fresh = mutableListOf<HomeDevice>()
            for (s in structures) {
                try {
                    val devs = withTimeout(12_000) { s.devices().first() }
                    fresh.addAll(devs)
                } catch (_: Exception) {
                }
            }
            cacheLiveDevices(fresh)
        }
        return null
    }

    private fun lookupDevice(targetName: String): HomeDevice? {
        val norm = targetName.trim().lowercase()
        devicesByLowerName[norm]?.let { return it }
        // Fuzzy fallback: match if Google Home name contains or is contained by Cast friendly name
        // (e.g. "MB TV" vs "MB TV Chromecast").
        return devicesByLowerName.entries.firstOrNull { (k, _) ->
            k.contains(norm) || norm.contains(k)
        }?.value
    }

    /**
     * Turns a TV/device ON or OFF via Google Home API OnOff trait (exact same path as Google Assistant).
     * Returns Result.success(matchedHomeDeviceName) or Result.failure(reason).
     */
    suspend fun setPower(activity: ComponentActivity, deviceName: String, turnOn: Boolean): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val permErr = ensureSynced(activity, forceRefresh = false)
                if (permErr != null) return@withContext Result.failure(IllegalStateException(permErr))

                var dev = lookupDevice(deviceName)
                if (dev == null) {
                    ensureSynced(activity, forceRefresh = true)
                    dev = lookupDevice(deviceName)
                }
                if (dev == null) {
                    return@withContext Result.failure(IllegalStateException("'$deviceName' not found in Google Home"))
                }

                val onOff = findOnOff(dev)
                    ?: return@withContext Result.failure(IllegalStateException("'${dev.name}' has no Google Home OnOff power trait"))

                if (turnOn) {
                    onOff.on()
                } else {
                    onOff.off()
                }
                DiagLog.log(activity, "GoogleHomePowerManager: ${if (turnOn) "ON" else "OFF"} sent to '${dev.name}'")
                Result.success(dev.name)
            } catch (e: Exception) {
                DiagLog.log(activity, "GoogleHomePowerManager failed for '$deviceName': ${e.message}")
                Result.failure(e)
            }
        }
}
