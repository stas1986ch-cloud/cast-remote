/**
 * @file NestThermostatAlertTest.kt
 * @summary JUnit 4 unit tests validating Nest Monitor delta, target setpoint, ambient temp, and low-threshold alert detection.
 * @description Covers the exact First Floor Thermostat 75°F -> 71°F regression scenario for both target setpoint and ambient temperature drops, plus noise filtering and low-temp limits.
 * @testing Executed via ./gradlew testReleaseUnitTest.
 */
package com.project.autoswitch.ui.nest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NestThermostatAlertTest {

    private val defaultRules = NestMonitorRules(
        enabled = true,
        deltaDegreesF = 2.0f,
        minTempF = 62.0f,
        resetTempF = 70.0f,
        pollIntervalMin = 5
    )

    @Test
    fun firstFloorTargetDrop75To71TriggersAlertImmediately() {
        val snap = ThermostatSnapshot(
            key = "nest::Home::First Floor Thermostat",
            homeName = "Home",
            name = "First Floor Thermostat",
            ambientF = 75.0,
            heatSetpointF = 71.0,
            coolSetpointF = 78.0,
            modeLabel = "HEAT"
        )
        val (events, nextBases) = NestAlertEvaluator.evaluateSingle(
            snap = snap,
            rules = defaultRules,
            ambientBaselineF = 75.0f,
            targetBaselineF = 75.0f,
            canFireLowAlert = true
        )
        assertEquals("Expected 1 target-change alert for 75 -> 71", 1, events.size)
        assertTrue(events[0].title.contains("First Floor Thermostat"))
        assertTrue(events[0].body.contains("lowered by 4.0°F"))
        assertEquals(71.0f, nextBases.second!!, 0.01f)
    }

    @Test
    fun firstFloorAmbientDrop75To71TriggersAlert() {
        val snap = ThermostatSnapshot(
            key = "nest::Home::First Floor Thermostat",
            homeName = "Home",
            name = "First Floor Thermostat",
            ambientF = 71.0,
            heatSetpointF = 72.0,
            coolSetpointF = 78.0,
            modeLabel = "HEAT"
        )
        val (events, nextBases) = NestAlertEvaluator.evaluateSingle(
            snap = snap,
            rules = defaultRules,
            ambientBaselineF = 75.0f,
            targetBaselineF = 72.0f,
            canFireLowAlert = true
        )
        assertEquals("Expected 1 ambient-change alert for 75 -> 71", 1, events.size)
        assertTrue(events[0].body.contains("dropped by 4.0°F"))
        assertEquals(71.0f, nextBases.first!!, 0.01f)
    }

    @Test
    fun smallFluctuationBelowDeltaDoesNotFalseAlarm() {
        val snap = ThermostatSnapshot(
            key = "nest::Home::First Floor Thermostat",
            homeName = "Home",
            name = "First Floor Thermostat",
            ambientF = 74.2,
            heatSetpointF = 74.5,
            coolSetpointF = 78.0,
            modeLabel = "HEAT"
        )
        val (events, _) = NestAlertEvaluator.evaluateSingle(
            snap = snap,
            rules = defaultRules,
            ambientBaselineF = 75.0f,
            targetBaselineF = 75.0f,
            canFireLowAlert = true
        )
        assertTrue("Expected no alerts for < 2.0F change", events.isEmpty())
    }

    @Test
    fun lowTempThresholdTriggersLowAlert() {
        val snap = ThermostatSnapshot(
            key = "nest::Home::First Floor Thermostat",
            homeName = "Home",
            name = "First Floor Thermostat",
            ambientF = 61.5,
            heatSetpointF = 68.0,
            coolSetpointF = 76.0,
            modeLabel = "HEAT"
        )
        val (events, _) = NestAlertEvaluator.evaluateSingle(
            snap = snap,
            rules = defaultRules,
            ambientBaselineF = 62.5f,
            targetBaselineF = 68.0f,
            canFireLowAlert = true
        )
        assertTrue("Expected low temp alert at 61.5F <= 62.0F", events.any { it.isLowTempAlert })
    }
}
