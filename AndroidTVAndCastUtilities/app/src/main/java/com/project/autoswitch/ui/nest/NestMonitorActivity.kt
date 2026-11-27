/**
 * @file NestMonitorActivity.kt
 * @summary Dark-neon Android activity for monitoring Nest thermostats, configuring delta/low-temp alerts, and resetting temperatures.
 * @description Displays live ambient/setpoint readings from NestThermostatBridge, manages NestMonitorService background alerts, and provides 1-tap temperature reset and +/-1°F controls.
 * @testing Compiled and lint-verified via Gradle assembleRelease.
 */
package com.project.autoswitch.ui.nest

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import com.project.autoswitch.ui.cast.GoogleHomePowerBridge
import java.util.Locale

class NestMonitorActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var monitorSwitch: SwitchCompat
    private lateinit var deltaInput: EditText
    private lateinit var minTempInput: EditText
    private lateinit var resetTempInput: EditText
    private lateinit var pollMinInput: EditText
    private lateinit var thermostatListContainer: LinearLayout

    private var currentSnapshots: List<ThermostatSnapshot> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GoogleHomePowerBridge.registerActivity(this)
        setContentView(buildRootView())
        loadRulesIntoUi()
        refreshThermostats()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun roundedBg(fillHex: String, strokeHex: String? = null, radiusDp: Int = 12): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.parseColor(fillHex))
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeHex != null) setStroke(dp(1), Color.parseColor(strokeHex))
        }

    private fun buildRootView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0E17"))
            fitsSystemWindows = true
        }

        // Top Bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }
        val backBtn = makePillButton("← Hub", "#22304A", "#FFFFFF") { finish() }
        val titleTv = TextView(this).apply {
            text = "NEST MONITOR"
            setTextColor(Color.parseColor("#00FFFF"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            }
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this, 11, 20, 1, TypedValue.COMPLEX_UNIT_SP
            )
        }
        val refreshBtn = makePillButton("Refresh", "#0E7490", "#FFFFFF") { refreshThermostats() }
        topBar.addView(backBtn)
        topBar.addView(titleTv)
        topBar.addView(refreshBtn)
        root.addView(topBar)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(28))
        }

        statusText = TextView(this).apply {
            text = "Loading Nest thermostats from Google Home..."
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 14f
            setPadding(dp(4), 0, dp(4), dp(10))
        }
        content.addView(statusText)

        // Alert Rules Card
        val rulesCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg("#151C2C", "#2A3954", 12)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }

        val switchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val switchLabel = TextView(this).apply {
            text = "Background Temp Alerts"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        monitorSwitch = SwitchCompat(this).apply {
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) requestNotificationPermissionIfNeeded()
                saveRulesFromUi(showToast = false)
            }
        }
        switchRow.addView(switchLabel)
        switchRow.addView(monitorSwitch)
        rulesCard.addView(switchRow)

        deltaInput = addLabeledNumberRow(rulesCard, "Notify if temp changes by (±°F):", "2.0", allowDecimal = true)
        minTempInput = addLabeledNumberRow(rulesCard, "Notify if temp falls to or below (°F):", "62.0", allowDecimal = true)
        resetTempInput = addLabeledNumberRow(rulesCard, "Default Reset Temp (°F):", "70.0", allowDecimal = true)
        pollMinInput = addLabeledNumberRow(rulesCard, "Check interval (minutes):", "5", allowDecimal = false)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        val saveBtn = makePillButton("Save Rules", "#7C3AED", "#FFFFFF") {
            saveRulesFromUi(showToast = true)
            renderThermostats()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        val testBtn = makePillButton("Test Alert", "#2A2A3A", "#00FFFF") {
            requestNotificationPermissionIfNeeded()
            NestThermostatBridge.postAlertNotification(
                this,
                9901,
                "Nest Monitor Test Alert",
                "Notifications are active! Tap to open Nest Monitor."
            )
            Toast.makeText(this, "Test notification sent.", Toast.LENGTH_SHORT).show()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnRow.addView(saveBtn)
        btnRow.addView(testBtn)
        rulesCard.addView(btnRow)
        content.addView(rulesCard)

        // Thermostat list container
        thermostatListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(thermostatListContainer)

        // Honest API capability note about Nest screen messages
        val noteTv = TextView(this).apply {
            text = "Note: Google's Nest Thermostat hardware and APIs do not support displaying custom text messages on the thermostat screen. Alerts are delivered directly to your phone."
            setTextColor(Color.parseColor("#6B7280"))
            textSize = 12f
            setPadding(dp(6), dp(12), dp(6), dp(8))
        }
        content.addView(noteTv)

        scroll.addView(content)
        root.addView(scroll)
        return root
    }

    private fun addLabeledNumberRow(parent: LinearLayout, label: String, hint: String, allowDecimal: Boolean): EditText {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val tv = TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#D1D5DB"))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val input = EditText(this).apply {
            this.hint = hint
            setHintTextColor(Color.parseColor("#6B7280"))
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            inputType = if (allowDecimal) {
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            } else {
                InputType.TYPE_CLASS_NUMBER
            }
            background = roundedBg("#0B0F1A", "#334155", 8)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(76), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        row.addView(tv)
        row.addView(input)
        parent.addView(row)
        return input
    }

    private fun makePillButton(text: String, bgHex: String, textHex: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(Color.parseColor(textHex))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            background = roundedBg(bgHex, null, 8)
            minHeight = dp(38)
            minimumHeight = dp(38)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { onClick() }
        }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 401)
            }
        }
    }

    private fun loadRulesIntoUi() {
        val r = NestThermostatBridge.loadRules(this)
        monitorSwitch.isChecked = r.enabled
        deltaInput.setText(String.format(Locale.US, "%.1f", r.deltaDegreesF))
        minTempInput.setText(String.format(Locale.US, "%.1f", r.minTempF))
        resetTempInput.setText(String.format(Locale.US, "%.1f", r.resetTempF))
        pollMinInput.setText(r.pollIntervalMin.toString())
    }

    private fun saveRulesFromUi(showToast: Boolean) {
        val delta = deltaInput.text.toString().toFloatOrNull() ?: 2.0f
        val minT = minTempInput.text.toString().toFloatOrNull() ?: 62.0f
        val resetT = resetTempInput.text.toString().toFloatOrNull() ?: 70.0f
        val pollM = pollMinInput.text.toString().toIntOrNull() ?: 5
        val enabled = monitorSwitch.isChecked
        val rules = NestMonitorRules(enabled, delta, minT, resetT, pollM)
        NestThermostatBridge.saveRules(this, rules)
        if (enabled) {
            NestMonitorService.start(this)
        } else {
            NestMonitorService.stop(this)
        }
        if (showToast) {
            Toast.makeText(
                this,
                if (enabled) "Rules saved & background monitor running." else "Rules saved (monitor paused).",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun refreshThermostats(forceReconsent: Boolean = false) {
        statusText.text = if (forceReconsent) "Opening Google Home access..." else "Syncing Nest thermostats..."
        NestThermostatBridge.syncThermostats(
            activity = this,
            autoPrompt = true,
            forceReconsent = forceReconsent
        ) { list, err ->
            currentSnapshots = list
            if (err != null) {
                statusText.text = err
            } else if (list.isEmpty()) {
                statusText.text = "No Nest thermostats found. Tap Refresh to select your Google Home structure."
            } else {
                statusText.text = "Found ${list.size} Nest thermostat(s)."
            }
            renderThermostats()
        }
    }

    private fun renderThermostats() {
        thermostatListContainer.removeAllViews()
        val rules = NestThermostatBridge.loadRules(this)

        for (snap in currentSnapshots) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBg("#17172A", "#2E2E4A", 12)
                setPadding(dp(14), dp(14), dp(14), dp(14))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(12) }
            }

            val header = TextView(this).apply {
                text = if (snap.homeName.isNotEmpty()) "${snap.name} · ${snap.homeName}" else snap.name
                setTextColor(Color.WHITE)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
            }
            card.addView(header)

            val ambStr = snap.ambientF?.let { String.format(Locale.US, "%.1f°F", it) } ?: "--°F"
            val targetStr = snap.activeTargetF?.let { String.format(Locale.US, "%.1f°F", it) } ?: "--°F"
            val baseAmb = NestThermostatBridge.getBaselineF(this, snap.key)
            val baseTgt = NestThermostatBridge.getTargetBaselineF(this, snap.key)
            val baseAmbStr = baseAmb?.let { String.format(Locale.US, "%.1f°F", it) } ?: "--°F"
            val baseTgtStr = baseTgt?.let { String.format(Locale.US, "%.1f°F", it) } ?: "--°F"

            val tempRow = TextView(this).apply {
                text = "Room: $ambStr   |   Target: $targetStr (${snap.modeLabel})"
                setTextColor(
                    if (snap.ambientF != null && snap.ambientF <= rules.minTempF) Color.parseColor("#EF4444")
                    else Color.parseColor("#00FFFF")
                )
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(6), 0, dp(2))
            }
            card.addView(tempRow)

            val baseRow = TextView(this).apply {
                text = "Baselines: Room $baseAmbStr · Target $baseTgtStr (±${String.format(Locale.US, "%.1f", rules.deltaDegreesF)}°F) · Low ≤${String.format(Locale.US, "%.1f", rules.minTempF)}°F"
                setTextColor(Color.parseColor("#9CA3AF"))
                textSize = 12f
                setPadding(0, 0, 0, dp(10))
            }
            card.addView(baseRow)

            val controls = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val currentTarget = snap.activeTargetF ?: snap.ambientF ?: rules.resetTempF.toDouble()

            val minusBtn = makePillButton("-1°", "#2A2A3A", "#FFFFFF") {
                applyTemp(snap.key, currentTarget - 1.0)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(6)
                }
            }
            val plusBtn = makePillButton("+1°", "#2A2A3A", "#FFFFFF") {
                applyTemp(snap.key, currentTarget + 1.0)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(6)
                }
            }
            val resetLabel = String.format(Locale.US, "Reset %.0f°F", rules.resetTempF)
            val resetBtn = makePillButton(resetLabel, "#10B981", "#FFFFFF") {
                applyTemp(snap.key, rules.resetTempF.toDouble())
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6)
                }
            }
            val baseBtn = makePillButton("Baseline", "#334155", "#E5E7EB") {
                snap.ambientF?.toFloat()?.let { NestThermostatBridge.setBaselineF(this, snap.key, it) }
                snap.activeTargetF?.toFloat()?.let { NestThermostatBridge.setTargetBaselineF(this, snap.key, it) }
                Toast.makeText(this, "${snap.name} room & target baselines reset to current.", Toast.LENGTH_SHORT).show()
                renderThermostats()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            controls.addView(minusBtn)
            controls.addView(plusBtn)
            controls.addView(resetBtn)
            controls.addView(baseBtn)
            card.addView(controls)

            thermostatListContainer.addView(card)
        }
    }

    private fun applyTemp(key: String, targetF: Double) {
        statusText.text = String.format(Locale.US, "Setting thermostat to %.1f°F...", targetF)
        NestThermostatBridge.setTargetTemperature(this, key, targetF) { ok, msg ->
            statusText.text = msg
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            if (ok) refreshThermostats()
        }
    }
}
