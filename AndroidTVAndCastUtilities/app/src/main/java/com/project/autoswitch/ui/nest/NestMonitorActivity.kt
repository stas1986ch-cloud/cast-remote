/**
 * @file NestMonitorActivity.kt
 * @summary Dark-neon Nest Thermostat Monitor activity with project-standard drag-and-drop, shrink/expand, hide/show blocks, alert rules, and 1-tap temp reset.
 * @description Renders the Alert Rules block and all Nest Thermostat blocks in a unified persisted drag-and-drop order with collapse/hide controls and a carved-out Hidden Blocks toggle.
 * @testing Compiled and lint-verified via Gradle testDebugUnitTest and assembleRelease.
 */
package com.project.autoswitch.ui.nest

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.DragEvent
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
import com.project.autoswitch.ui.cast.GoogleHomePowerBridge
import org.json.JSONArray
import java.util.Locale

class NestMonitorActivity : AppCompatActivity() {

    companion object {
        private const val BLOCK_PREFS = "NestMonitorBlockPrefs"
        private const val KEY_ORDER = "nest_block_order_v1"
        private const val KEY_COLLAPSED = "nest_block_collapsed_v1"
        private const val KEY_HIDDEN = "nest_block_hidden_v1"
        private const val RULES_BLOCK_ID = "__nest_alert_rules__"
    }

    private lateinit var statusText: TextView
    private lateinit var hiddenBlocksBtn: Button
    private lateinit var blocksContainer: LinearLayout

    private var monitorSwitch: SwitchCompat? = null
    private var deltaInput: EditText? = null
    private var minTempInput: EditText? = null
    private var resetTempInput: EditText? = null
    private var pollMinInput: EditText? = null

    private var currentSnapshots: List<ThermostatSnapshot> = emptyList()
    private val blockOrder = mutableListOf<String>()
    private val collapsedBlocks = mutableSetOf<String>()
    private val hiddenBlocks = mutableSetOf<String>()
    private var showHiddenBlocks = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GoogleHomePowerBridge.registerActivity(this)
        loadBlockPrefs()
        setContentView(buildRootView())
        renderAllBlocks()
        refreshThermostats()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun roundedBg(fillHex: String, strokeHex: String? = null, radiusDp: Int = 12): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.parseColor(fillHex))
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeHex != null) setStroke(dp(1), Color.parseColor(strokeHex))
        }

    // MARK: - Block Order / Collapse / Hide Persistence

    private fun loadBlockPrefs() {
        val prefs = getSharedPreferences(BLOCK_PREFS, Context.MODE_PRIVATE)
        collapsedBlocks.clear()
        collapsedBlocks.addAll(prefs.getStringSet(KEY_COLLAPSED, emptySet()) ?: emptySet())
        hiddenBlocks.clear()
        hiddenBlocks.addAll(prefs.getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet())
        blockOrder.clear()
        try {
            val raw = prefs.getString(KEY_ORDER, null)
            if (!raw.isNullOrEmpty()) {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val id = arr.optString(i, "")
                    if (id.isNotEmpty() && !blockOrder.contains(id)) blockOrder.add(id)
                }
            }
        } catch (_: Exception) {}
    }

    private fun saveBlockOrder() {
        val arr = JSONArray()
        blockOrder.forEach { arr.put(it) }
        getSharedPreferences(BLOCK_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ORDER, arr.toString())
            .apply()
    }

    private fun buildEffectiveOrder(): List<String> {
        val allIds = mutableListOf(RULES_BLOCK_ID)
        currentSnapshots.forEach { allIds.add(it.key) }
        val result = mutableListOf<String>()
        for (id in blockOrder) {
            if (allIds.contains(id) && !result.contains(id)) result.add(id)
        }
        for (id in allIds) {
            if (!result.contains(id)) result.add(id)
        }
        return result
    }

    private fun reorderBlocks(draggedId: String, targetId: String) {
        if (draggedId == targetId) return
        val order = buildEffectiveOrder().toMutableList()
        val from = order.indexOf(draggedId)
        val to = order.indexOf(targetId)
        if (from < 0 || to < 0) return
        order.removeAt(from)
        order.add(to, draggedId)
        blockOrder.clear()
        blockOrder.addAll(order)
        saveBlockOrder()
        renderAllBlocks()
    }

    private fun toggleCollapse(blockId: String) {
        if (collapsedBlocks.contains(blockId)) collapsedBlocks.remove(blockId) else collapsedBlocks.add(blockId)
        getSharedPreferences(BLOCK_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_COLLAPSED, HashSet(collapsedBlocks))
            .apply()
        renderAllBlocks()
    }

    private fun toggleHide(blockId: String) {
        if (hiddenBlocks.contains(blockId)) hiddenBlocks.remove(blockId) else hiddenBlocks.add(blockId)
        getSharedPreferences(BLOCK_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_HIDDEN, HashSet(hiddenBlocks))
            .apply()
        renderAllBlocks()
    }

    // MARK: - Root Layout

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
            setPadding(dp(16), dp(14), dp(16), dp(8))
        }
        val backBtn = makePillButton("← Hub", "#22304A", "#FFFFFF") { finish() }
        val titleTv = TextView(this).apply {
            text = "NEST MONITOR"
            setTextColor(Color.parseColor("#00FFFF"))
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            }
        }
        val refreshBtn = makePillButton("Refresh", "#0E7490", "#FFFFFF") { refreshThermostats() }
        topBar.addView(backBtn)
        topBar.addView(titleTv)
        topBar.addView(refreshBtn)
        root.addView(topBar)

        // Carved-out always-visible Hidden Blocks toolbar
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        statusText = TextView(this).apply {
            text = "Loading Nest thermostats..."
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        hiddenBlocksBtn = makePillButton("Hidden (0)", "#1E293B", "#9CA3AF") {
            showHiddenBlocks = !showHiddenBlocks
            renderAllBlocks()
        }
        toolbar.addView(statusText)
        toolbar.addView(hiddenBlocksBtn)
        root.addView(toolbar)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(28))
        }

        blocksContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(blocksContainer)

        val noteTv = TextView(this).apply {
            text = "Note: Google's Nest Thermostat hardware and APIs do not support displaying custom text messages on the thermostat screen. Critical buzz alerts are delivered directly to your phone."
            setTextColor(Color.parseColor("#6B7280"))
            textSize = 12f
            setPadding(dp(6), dp(10), dp(6), dp(8))
        }
        content.addView(noteTv)

        scroll.addView(content)
        root.addView(scroll)
        return root
    }

    // MARK: - Block Rendering (Unified Drag-and-Drop, Shrink/Expand, Hide/Show)

    private fun renderAllBlocks() {
        blocksContainer.removeAllViews()

        val hiddenCount = hiddenBlocks.size
        hiddenBlocksBtn.text = if (showHiddenBlocks) "Showing Hidden ($hiddenCount)" else "Hidden ($hiddenCount)"
        hiddenBlocksBtn.setTextColor(
            Color.parseColor(
                when {
                    showHiddenBlocks -> "#00FFFF"
                    hiddenCount > 0 -> "#F59E0B"
                    else -> "#9CA3AF"
                }
            )
        )

        val snapByKey = currentSnapshots.associateBy { it.key }
        val order = buildEffectiveOrder()
        for (id in order) {
            if (!showHiddenBlocks && hiddenBlocks.contains(id)) continue
            if (id == RULES_BLOCK_ID) {
                blocksContainer.addView(buildAlertRulesBlock())
            } else {
                snapByKey[id]?.let { blocksContainer.addView(buildThermostatBlock(it)) }
            }
        }
    }

    private fun attachDragAndDrop(card: LinearLayout, cardBg: GradientDrawable, defaultStrokeHex: String, blockId: String): TextView {
        card.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DRAG_ENTERED -> {
                    cardBg.setStroke(dp(2), Color.parseColor("#00FFFF"))
                    true
                }
                DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> {
                    cardBg.setStroke(dp(1), Color.parseColor(defaultStrokeHex))
                    true
                }
                DragEvent.ACTION_DROP -> {
                    cardBg.setStroke(dp(1), Color.parseColor(defaultStrokeHex))
                    (event.localState as? String)?.let { dragged -> reorderBlocks(dragged, blockId) }
                    true
                }
                else -> true
            }
        }

        return TextView(this).apply {
            text = "☰"
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(8), dp(4))
            setOnLongClickListener {
                val data = ClipData.newPlainText("nest_block_id", blockId)
                val shadow = View.DragShadowBuilder(card)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    card.startDragAndDrop(data, shadow, blockId, 0)
                } else {
                    @Suppress("DEPRECATION")
                    card.startDrag(data, shadow, blockId, 0)
                }
                true
            }
            setOnClickListener {
                Toast.makeText(this@NestMonitorActivity, "Hold ☰ and drag onto another block to reorder", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addBlockHeader(
        card: LinearLayout,
        cardBg: GradientDrawable,
        defaultStrokeHex: String,
        blockId: String,
        titleText: String,
        titleColorHex: String
    ): Boolean {
        val isHidden = hiddenBlocks.contains(blockId)
        val isExpanded = !collapsedBlocks.contains(blockId)
        if (isHidden) card.alpha = 0.55f

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(attachDragAndDrop(card, cardBg, defaultStrokeHex, blockId))

        val titleTv = TextView(this).apply {
            text = titleText
            setTextColor(Color.parseColor(titleColorHex))
            textSize = 15.5f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { toggleCollapse(blockId) }
        }
        header.addView(titleTv)

        val collapseBtn = makeSmallPill(if (isExpanded) "▾" else "▸", "#22304A", "#00FFFF") {
            toggleCollapse(blockId)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(32)).apply { marginEnd = dp(4) }
        }
        header.addView(collapseBtn)

        val hideBtn = makeSmallPill(
            if (isHidden) "Show" else "Hide",
            if (isHidden) "#10B981" else "#22304A",
            if (isHidden) "#FFFFFF" else "#9CA3AF"
        ) {
            toggleHide(blockId)
        }
        header.addView(hideBtn)

        card.addView(header)
        return isExpanded
    }

    private fun buildAlertRulesBlock(): View {
        val rules = NestThermostatBridge.loadRules(this)
        val bg = roundedBg("#151C2C", "#2A3954", 12)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }

        val expanded = addBlockHeader(card, bg, "#2A3954", RULES_BLOCK_ID, "ALERT RULES & CRITICAL BUZZ", "#00FFFF")
        if (!expanded) return card

        val switchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        val switchLabel = TextView(this).apply {
            text = "Background Temp Alerts"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = SwitchCompat(this).apply {
            isChecked = rules.enabled
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) requestNotificationPermissionIfNeeded()
                saveRulesFromUi(showToast = false)
            }
        }
        monitorSwitch = sw
        switchRow.addView(switchLabel)
        switchRow.addView(sw)
        card.addView(switchRow)

        deltaInput = addLabeledNumberRow(card, "Notify if temp changes by (±°F):", String.format(Locale.US, "%.1f", rules.deltaDegreesF), true)
        minTempInput = addLabeledNumberRow(card, "Notify if temp falls to or below (°F):", String.format(Locale.US, "%.1f", rules.minTempF), true)
        resetTempInput = addLabeledNumberRow(card, "Default Reset Temp (°F):", String.format(Locale.US, "%.1f", rules.resetTempF), true)
        pollMinInput = addLabeledNumberRow(card, "Check interval (minutes):", rules.pollIntervalMin.toString(), false)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        val saveBtn = makePillButton("Save Rules", "#7C3AED", "#FFFFFF") {
            saveRulesFromUi(showToast = true)
            renderAllBlocks()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
        }
        val testBtn = makePillButton("Test Critical Buzz", "#EF4444", "#FFFFFF") {
            requestNotificationPermissionIfNeeded()
            NestThermostatBridge.postAlertNotification(
                this,
                9901,
                "Nest Critical Test Alert",
                "Critical alarm buzz is active! Tap to open Nest Monitor."
            )
            Toast.makeText(this, "Critical test alert sent.", Toast.LENGTH_SHORT).show()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnRow.addView(saveBtn)
        btnRow.addView(testBtn)
        card.addView(btnRow)
        return card
    }

    private fun buildThermostatBlock(snap: ThermostatSnapshot): View {
        val rules = NestThermostatBridge.loadRules(this)
        val bg = roundedBg("#17172A", "#2E2E4A", 12)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }

        val headerTitle = if (snap.homeName.isNotEmpty()) "${snap.name} · ${snap.homeName}" else snap.name
        val expanded = addBlockHeader(card, bg, "#2E2E4A", snap.key, headerTitle, "#FFFFFF")

        val ambStr = snap.ambientF?.let { String.format(Locale.US, "%.1f°F", it) } ?: "--°F"
        val targetStr = snap.activeTargetF?.let { String.format(Locale.US, "%.1f°F", it) } ?: "--°F"
        val tempRow = TextView(this).apply {
            text = "Room: $ambStr   |   Target: $targetStr (${snap.modeLabel})"
            setTextColor(
                if (snap.ambientF != null && snap.ambientF <= rules.minTempF) Color.parseColor("#EF4444")
                else Color.parseColor("#00FFFF")
            )
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(6), dp(4), dp(2))
        }
        card.addView(tempRow)

        if (!expanded) return card

        val baseAmb = NestThermostatBridge.getBaselineF(this, snap.key)
        val baseTgt = NestThermostatBridge.getTargetBaselineF(this, snap.key)
        val baseAmbStr = baseAmb?.let { String.format(Locale.US, "%.1f°F", it) } ?: "--°F"
        val baseTgtStr = baseTgt?.let { String.format(Locale.US, "%.1f°F", it) } ?: "--°F"
        val baseRow = TextView(this).apply {
            text = "Baselines: Room $baseAmbStr · Target $baseTgtStr (±${String.format(Locale.US, "%.1f", rules.deltaDegreesF)}°F) · Low ≤${String.format(Locale.US, "%.1f", rules.minTempF)}°F"
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 12f
            setPadding(dp(4), 0, dp(4), dp(10))
        }
        card.addView(baseRow)

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val currentTarget = snap.activeTargetF ?: snap.ambientF ?: rules.resetTempF.toDouble()

        val minusBtn = makePillButton("-1°", "#2A2A3A", "#FFFFFF") {
            applyTemp(snap.key, currentTarget - 1.0)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
        }
        val plusBtn = makePillButton("+1°", "#2A2A3A", "#FFFFFF") {
            applyTemp(snap.key, currentTarget + 1.0)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
        }
        val resetLabel = String.format(Locale.US, "Reset %.0f°F", rules.resetTempF)
        val resetBtn = makePillButton(resetLabel, "#10B981", "#FFFFFF") {
            applyTemp(snap.key, rules.resetTempF.toDouble())
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
        }
        val baseBtn = makePillButton("Baseline", "#334155", "#E5E7EB") {
            snap.ambientF?.toFloat()?.let { NestThermostatBridge.setBaselineF(this, snap.key, it) }
            snap.activeTargetF?.toFloat()?.let { NestThermostatBridge.setTargetBaselineF(this, snap.key, it) }
            Toast.makeText(this, "${snap.name} room & target baselines reset to current.", Toast.LENGTH_SHORT).show()
            renderAllBlocks()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        controls.addView(minusBtn)
        controls.addView(plusBtn)
        controls.addView(resetBtn)
        controls.addView(baseBtn)
        card.addView(controls)
        return card
    }

    private fun addLabeledNumberRow(parent: LinearLayout, label: String, initialValue: String, allowDecimal: Boolean): EditText {
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
            setText(initialValue)
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

    private fun makeSmallPill(text: String, bgHex: String, textHex: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(Color.parseColor(textHex))
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            background = roundedBg(bgHex, null, 8)
            minHeight = dp(32)
            minimumHeight = dp(32)
            minWidth = dp(32)
            minimumWidth = dp(32)
            setPadding(dp(8), dp(2), dp(8), dp(2))
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

    private fun saveRulesFromUi(showToast: Boolean) {
        val current = NestThermostatBridge.loadRules(this)
        val delta = deltaInput?.text?.toString()?.toFloatOrNull() ?: current.deltaDegreesF
        val minT = minTempInput?.text?.toString()?.toFloatOrNull() ?: current.minTempF
        val resetT = resetTempInput?.text?.toString()?.toFloatOrNull() ?: current.resetTempF
        val pollM = pollMinInput?.text?.toString()?.toIntOrNull() ?: current.pollIntervalMin
        val enabled = monitorSwitch?.isChecked ?: current.enabled
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
            renderAllBlocks()
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
