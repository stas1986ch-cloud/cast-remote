/**
 * @file MainActivity.kt
 * @summary Entry point and Jetpack Compose dark-neon UI for Cast Remote on Android.
 * @description Renders device discovery, favorite pinning, YouTube cast/mirror/stop controls, TV Power On/Off, and Android TV PIN pairing dialog.
 * @testing Compiled and packaged into a signed APK via Android Gradle CI on Ubuntu.
 */
package com.screencontrol.cast

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Violet = Color(0xFF7C3AED)
private val Cyan = Color(0xFF06B6D4)
private val Magenta = Color(0xFFEC4899)
private val Amber = Color(0xFFF59E0B)
private val Emerald = Color(0xFF10B981)
private val Crimson = Color(0xFFEF4444)
private val BgDark = Color(0xFF0B0B14)
private val CardBg = Color(0xFF17172A)
private val TextMain = Color(0xFFE5E7EB)
private val DarkBtn = Color(0xFF2A2A3A)
private val Muted = Color(0xFF9CA3AF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                CastRemoteScreen()
            }
        }
    }
}

@Composable
private fun GradientButton(
    colors: List<Color>,
    label: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(if (enabled) colors else colors.map { it.copy(alpha = 0.4f) })
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            maxLines = 1
        )
    }
}

@Composable
fun CastRemoteScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { CastController(context, scope) }
    var link by remember { mutableStateOf("") }
    var pinInput by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        controller.refresh()
    }
    DisposableEffect(Unit) {
        onDispose { controller.cleanup() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "GOOGLE SCREEN CONTROL AND CAST",
            style = TextStyle(
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                brush = Brush.horizontalGradient(listOf(Violet, Cyan, Magenta, Amber))
            )
        )

        GradientButton(
            colors = listOf(Violet, Cyan, Magenta, Amber),
            label = "Mirror what's playing",
            enabled = !controller.isBusy,
            modifier = Modifier.fillMaxWidth()
        ) { controller.mirror(null) }

        Text(
            text = controller.status,
            color = Muted,
            fontSize = 14.sp
        )

        OutlinedTextField(
            value = link,
            onValueChange = { link = it },
            placeholder = { Text("or paste a YouTube link", color = Muted) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = CardBg,
                unfocusedContainerColor = CardBg,
                focusedTextColor = TextMain,
                unfocusedTextColor = TextMain,
                focusedBorderColor = Cyan,
                unfocusedBorderColor = DarkBtn
            )
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            GradientButton(
                colors = listOf(Violet, Cyan),
                label = "Refresh",
                modifier = Modifier.weight(1f)
            ) { controller.refresh() }

            GradientButton(
                colors = listOf(Magenta, Amber),
                label = "Stop all",
                enabled = !controller.isBusy,
                modifier = Modifier.weight(1f)
            ) { controller.stopAll() }
        }

        Button(
            onClick = { controller.toggleReceiving() },
            colors = ButtonDefaults.buttonColors(containerColor = DarkBtn),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            Text(
                text = if (controller.isReceiving) "Stop receiving" else "Receive on this phone",
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (controller.isReceiving) {
            Text(
                text = "Receiving as ${controller.receiveName} - keep this app open.",
                color = Muted,
                fontSize = 13.sp
            )
        }
        controller.lastIncomingVideoId?.let { incoming ->
            Text(
                text = "Incoming cast: $incoming",
                color = Cyan,
                fontSize = 13.sp
            )
        }

        for (device in controller.devices) {
            DeviceCard(
                device = device,
                isBusy = controller.isBusy,
                onTogglePin = { controller.togglePin(device) },
                onPlayHere = { controller.playHere(device, link) },
                onMirrorHere = { controller.mirror(device) },
                onStop = { controller.stop(device) },
                onPowerOn = { controller.powerOn(device) },
                onPowerOff = { controller.powerOff(device) },
                onPair = { controller.pairTV(device) }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    controller.pairingTarget?.let { target ->
        AlertDialog(
            onDismissRequest = {
                controller.cancelPairing()
                pinInput = ""
            },
            containerColor = CardBg,
            titleContentColor = TextMain,
            textContentColor = TextMain,
            title = { Text("Pair with ${target.name}", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Enter the 6-character hex code shown on your TV screen to enable real Power On & Power Off.",
                        color = Muted,
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { pinInput = it.uppercase().take(6) },
                        placeholder = { Text("A1B2C3", color = Muted) },
                        singleLine = true,
                        textStyle = TextStyle(
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = TextMain
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = BgDark,
                            unfocusedContainerColor = BgDark,
                            focusedBorderColor = Cyan,
                            unfocusedBorderColor = DarkBtn
                        )
                    )
                    controller.pairingError?.let { err ->
                        Text(err, color = Crimson, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val pin = pinInput
                        controller.submitPairingPIN(pin)
                        pinInput = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Violet)
                ) { Text("Confirm Pair", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = {
                    controller.cancelPairing()
                    pinInput = ""
                }) { Text("Cancel", color = Muted) }
            }
        )
    }
}

@Composable
private fun DeviceCard(
    device: CastDeviceItem,
    isBusy: Boolean,
    onTogglePin: () -> Unit,
    onPlayHere: () -> Unit,
    onMirrorHere: () -> Unit,
    onStop: () -> Unit,
    onPowerOn: () -> Unit,
    onPowerOff: () -> Unit,
    onPair: () -> Unit
) {
    val borderColor = if (device.isPinned) Amber.copy(alpha = 0.65f) else Color.Transparent
    val titleColor = when {
        device.state == "PLAYING" -> Cyan
        device.isPhone -> Magenta
        else -> TextMain
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CardBg)
            .border(BorderStroke(1.5.dp, borderColor), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (!device.isPhone && device.isPowerOn != null) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (device.isPowerOn) Emerald else Muted)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            Text(
                text = "${device.name} - ${device.state}" + if (device.title.isEmpty()) "" else " - ${device.title}",
                color = titleColor,
                fontSize = 15.sp,
                fontWeight = if (device.isPinned) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            IconButton(
                onClick = onTogglePin,
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(DarkBtn)
            ) {
                Icon(
                    imageVector = if (device.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    contentDescription = if (device.isPinned) "Unpin" else "Pin to top",
                    tint = if (device.isPinned) Amber else Muted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            GradientButton(
                colors = listOf(Cyan, Violet),
                label = "Play here",
                enabled = !isBusy,
                modifier = Modifier.weight(1f),
                onClick = onPlayHere
            )
            GradientButton(
                colors = listOf(Magenta, Violet),
                label = "Mirror here",
                enabled = !isBusy,
                modifier = Modifier.weight(1f),
                onClick = onMirrorHere
            )
            if (!device.isPhone) {
                Button(
                    onClick = onStop,
                    enabled = !isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = DarkBtn),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(0.8f),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    Text("Stop", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        }

        if (!device.isPhone) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onPowerOn,
                    enabled = !isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = Emerald.copy(alpha = 0.85f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp, horizontal = 6.dp)
                ) {
                    Icon(Icons.Filled.PowerSettingsNew, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Turn On", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }

                Button(
                    onClick = onPowerOff,
                    enabled = !isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = Crimson.copy(alpha = 0.85f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp, horizontal = 6.dp)
                ) {
                    Icon(Icons.Filled.PowerSettingsNew, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Turn Off", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }

                Button(
                    onClick = onPair,
                    enabled = !isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = DarkBtn),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp, horizontal = 10.dp)
                ) {
                    Icon(
                        imageVector = if (device.isPaired) Icons.Filled.CheckCircle else Icons.Filled.Link,
                        contentDescription = null,
                        tint = if (device.isPaired) Emerald else TextMain,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (device.isPaired) "Paired" else "Pair TV",
                        color = if (device.isPaired) Emerald else TextMain,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
