package com.w6tech.infinassistant.core.data.bridge

import com.w6tech.infinassistant.core.common.Logger
import com.w6tech.infinassistant.core.ha.HaState
import io.mockk.mockk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Dashboard sensor values: "no reading" must be distinguishable from zero.
 *
 * The watch rendered a freezer group as "0 2 0" and a beacons group as
 * "0 0 0" because extractSensorValue collapsed three different situations onto
 * the same 0 — entity missing, state non-numeric, and a genuine zero reading.
 * Only the last should reach the face; the others are now dropped so the
 * firmware's `if (count == 0) continue` skips them instead of rendering a
 * number that was never measured.
 */
class SensorValueExtractionTest {

    private lateinit var orchestrator: WatchFaceOrchestrator

    private val silentLogger = object : Logger {
        override fun d(tag: String, msg: String, tr: Throwable?) {}
        override fun i(tag: String, msg: String, tr: Throwable?) {}
        override fun w(tag: String, msg: String, tr: Throwable?) {}
        override fun e(tag: String, msg: String, tr: Throwable?) {}
    }

    @Before
    fun setup() {
        val stateManager = StateManager()
        orchestrator = WatchFaceOrchestrator(
            logger = silentLogger,
            haApi = mockk(relaxed = true),
            settingsRepo = mockk(relaxed = true),
            protocolHandler = ProtocolHandler(
                logger = silentLogger,
                stateManager = stateManager,
                logCallback = { _, _, _, _ -> },
            ),
            stateManager = stateManager,
            eventBroker = mockk(relaxed = true),
            logCallback = { _, _, _, _ -> },
        )
    }

    private fun state(entityId: String, value: String, attrs: Map<String, JsonPrimitive> = emptyMap()) =
        HaState(entityId = entityId, state = value, attributes = JsonObject(attrs))

    @Test
    fun `a real reading is kept, including a genuine zero`() {
        assertEquals(21, orchestrator.extractSensorValue("sensor.a", state("sensor.a", "21.2")))
        assertEquals(0, orchestrator.extractSensorValue("sensor.b", state("sensor.b", "0")))
    }

    @Test
    fun `readings round rather than truncate toward zero`() {
        // toInt() truncated: a freezer at -17.8 read as -17 and 2.6 read as 2.
        assertEquals(-18, orchestrator.extractSensorValue("sensor.freezer", state("sensor.freezer", "-17.8")))
        assertEquals(3, orchestrator.extractSensorValue("sensor.freezer", state("sensor.freezer", "2.6")))
    }

    @Test
    fun `a missing entity yields no value rather than zero`() {
        assertNull(orchestrator.extractSensorValue("sensor.absent", null))
    }

    @Test
    fun `unavailable and unknown yield no value rather than zero`() {
        assertNull(orchestrator.extractSensorValue("sensor.x", state("sensor.x", "unavailable")))
        assertNull(orchestrator.extractSensorValue("sensor.x", state("sensor.x", "unknown")))
    }

    @Test
    fun `a device_tracker yields no value — this is the beacons group's 0 0 0`() {
        // The user's beacons group points device_tracker entities at
        // displayType "temperature". These report home/not_home and can never
        // produce a number, so the whole group should simply not render.
        assertNull(
            orchestrator.extractSensorValue(
                "device_tracker.burby_beacon",
                state("device_tracker.burby_beacon", "home"),
            )
        )
        assertNull(
            orchestrator.extractSensorValue(
                "device_tracker.jessicas_iphone_2",
                state("device_tracker.jessicas_iphone_2", "not_home"),
            )
        )
    }

    @Test
    fun `climate entities read current_temperature and round it`() {
        val s = state(
            "climate.main",
            "heat",
            mapOf("current_temperature" to JsonPrimitive(72.6)),
        )
        assertEquals(73, orchestrator.extractSensorValue("climate.main", s))
    }
}
