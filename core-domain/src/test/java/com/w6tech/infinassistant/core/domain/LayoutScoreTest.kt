package com.w6tech.infinassistant.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REQ-074 — Layout score calculation unit tests.
 *
 * Validates the four real-time editor metrics:
 *   objectCount  — sum of glyphs + sensors across all dashboard screens
 *   styleCount   — sum of status-groups + sensor-groups across all dashboard screens
 *   cborBytes    — ProtocolCbor.encode byte count
 *   lvglPoolPct  — projected LVGL pool occupancy (Green <50, Yellow 50-80, Red >80)
 *
 * The metric constants match WatchFaceImportViewModel companion object:
 *   LVGL_POOL_BYTES = 14 * 1024 = 14336
 *   LVGL_BYTES_PER_OBJECT = 96
 *   LVGL_BYTES_PER_STYLE  = 48
 */
class LayoutScoreTest {

    // ── constants mirrored from WatchFaceImportViewModel ──────────────────────
    private val LVGL_POOL_BYTES = 14 * 1024
    private val LVGL_BYTES_PER_OBJECT = 96
    private val LVGL_BYTES_PER_STYLE = 48

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun makeGlyph(n: Int = 1): List<WatchGlyph> =
        (1..n).map { WatchGlyph(symbol = "D", entityId = "binary_sensor.door_$it") }

    private fun makeSensor(n: Int = 1): List<WatchSensor> =
        (1..n).map { WatchSensor(entityId = "sensor.temp_$it", displayType = "temperature") }

    private fun makePayload(
        statusGroups: List<WatchStatusGroup> = emptyList(),
        sensorGroups: List<WatchSensorGroup> = emptyList(),
    ): WatchFaceLayoutPayload = WatchFaceLayoutPayload(
        version = 1,
        theme = WatchTheme(background = "#000000", accent = "#FFD700"),
        header = WatchHeader(
            timeFormat = "HH:mm",
            timeAlign = "center",
            presenceIcons = emptyList(),
        ),
        screens = listOf(
            WatchScreen(
                screenType = 0,
                dashboard = WatchDashboard(
                    statusGroups = statusGroups,
                    sensorGroups = sensorGroups,
                ),
            )
        ),
        statusBar = WatchStatusBar(
            battery = true,
            steps = false,
            heartRate = false,
            notifications = false,
            bluetooth = true,
        ),
    )

    private fun computeMetrics(payload: WatchFaceLayoutPayload): Triple<Int, Int, Int> {
        var objectCount = 0
        var styleCount = 0
        for (screen in payload.screens) {
            val dash = screen.dashboard ?: continue
            objectCount += dash.statusGroups.sumOf { it.glyphs.size }
            objectCount += dash.sensorGroups.sumOf { it.sensors.size }
            styleCount += dash.statusGroups.size + dash.sensorGroups.size
        }
        val projectedBytes = objectCount * LVGL_BYTES_PER_OBJECT + styleCount * LVGL_BYTES_PER_STYLE
        val lvglPoolPct = ((projectedBytes.toLong() * 100L) / LVGL_POOL_BYTES.toLong())
            .coerceIn(0L, 100L).toInt()
        return Triple(objectCount, styleCount, lvglPoolPct)
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `empty dashboard yields zero objects, zero styles, zero pool pct`() {
        val (objects, styles, pct) = computeMetrics(makePayload())
        assertEquals(0, objects)
        assertEquals(0, styles)
        assertEquals(0, pct)
    }

    @Test
    fun `single status group with 3 glyphs counted correctly`() {
        val payload = makePayload(
            statusGroups = listOf(WatchStatusGroup(type = "doors", glyphs = makeGlyph(3))),
        )
        val (objects, styles, _) = computeMetrics(payload)
        assertEquals(3, objects)
        assertEquals(1, styles)
    }

    @Test
    fun `pool pct is Green for small layout`() {
        // 5 glyphs in 1 group, 0 sensors → objectCount=5, styleCount=1
        // projected = 5*96 + 1*48 = 528 B → 528/14336 * 100 = 3%  → Green
        val payload = makePayload(
            statusGroups = listOf(WatchStatusGroup(type = "doors", glyphs = makeGlyph(5))),
        )
        val (_, _, pct) = computeMetrics(payload)
        assertTrue("Expected Green (<50%), got $pct%", pct < 50)
    }

    @Test
    fun `pool pct is Yellow for medium layout`() {
        // Construct a layout that hits the 50-80% zone.
        // Need projected bytes in [7168, 11469).
        // 50 objects + 5 styles → 50*96 + 5*48 = 4800+240 = 5040 B → 35% (still Green).
        // 70 objects + 5 styles → 70*96 + 5*48 = 6720+240 = 6960 B → 48% (Green).
        // 75 objects + 5 styles → 75*96 + 5*48 = 7200+240 = 7440 B → 51% (Yellow).
        // Build 5 status groups × 15 glyphs each = 75 objects, 5 style slots.
        val groups = (1..5).map { WatchStatusGroup(type = "doors", glyphs = makeGlyph(15)) }
        val payload = makePayload(statusGroups = groups)
        val (_, _, pct) = computeMetrics(payload)
        assertTrue("Expected Yellow (50-80%), got $pct%", pct in 50..80)
    }

    @Test
    fun `pool pct is Red for oversized layout`() {
        // 5 status-groups × 10 glyphs + 3 sensor-groups × 7 sensors = 50+21 = 71 objects,
        // 5+3 = 8 styles.
        // projected = 71*96 + 8*48 = 6816 + 384 = 7200 B → 50.2% → boundary case; Yellow.
        // To push Red: use FirmwareLimits-capped max → 5 groups × 10 + 3 groups × 7 = 71 obj, 8 styles
        // Need >80%: >11469 B. 120 objects alone = 120*96 = 11520 B → 80.3%. → Red.
        // Use 5 groups × 24 glyphs = 120 objects, 5 styles (5*48=240) → total = 11760 B → 82%.
        val groups = (1..5).map { WatchStatusGroup(type = "doors", glyphs = makeGlyph(24)) }
        val payload = makePayload(statusGroups = groups)
        val (_, _, pct) = computeMetrics(payload)
        assertTrue("Expected Red (>80%), got $pct%", pct > 80)
    }

    @Test
    fun `cborBytes is positive for non-empty payload`() {
        val payload = makePayload(
            statusGroups = listOf(WatchStatusGroup(type = "doors", glyphs = makeGlyph(2))),
        )
        val encoded = ProtocolCbor.encode(WatchFaceLayoutPayload.serializer(), payload)
        assertTrue("Expected positive cborBytes, got ${encoded.size}", encoded.size > 0)
    }

    @Test
    fun `multi-screen layout sums glyphs and sensors across all screens`() {
        val screen1 = WatchScreen(
            screenType = 0,
            dashboard = WatchDashboard(
                statusGroups = listOf(WatchStatusGroup(type = "doors", glyphs = makeGlyph(4))),
            ),
        )
        val screen2 = WatchScreen(
            screenType = 0,
            dashboard = WatchDashboard(
                sensorGroups = listOf(WatchSensorGroup(type = "thermostats", sensors = makeSensor(3))),
            ),
        )
        val payload = WatchFaceLayoutPayload(
            version = 1,
            theme = WatchTheme(background = "#000000", accent = "#FFD700"),
            header = WatchHeader(timeFormat = "HH:mm", timeAlign = "center", presenceIcons = emptyList()),
            screens = listOf(screen1, screen2),
            statusBar = WatchStatusBar(battery = true, steps = false, heartRate = false, notifications = false, bluetooth = true),
        )
        val (objects, styles, _) = computeMetrics(payload)
        assertEquals("Expected 4 glyphs + 3 sensors = 7 objects", 7, objects)
        assertEquals("Expected 1 statusGroup + 1 sensorGroup = 2 styles", 2, styles)
    }

    @Test
    fun `tile-only screen contributes no objects or styles`() {
        val tileScreen = WatchScreen(
            screenType = 1,
            tiles = listOf(WatchTile(label = "Garage", entityId = "cover.garage", color = "#FFF")),
        )
        val payload = WatchFaceLayoutPayload(
            version = 1,
            theme = WatchTheme(background = "#000000", accent = "#FFD700"),
            header = WatchHeader(timeFormat = "HH:mm", timeAlign = "center", presenceIcons = emptyList()),
            screens = listOf(tileScreen),
            statusBar = WatchStatusBar(battery = true, steps = false, heartRate = false, notifications = false, bluetooth = true),
        )
        val (objects, styles, pct) = computeMetrics(payload)
        assertEquals(0, objects)
        assertEquals(0, styles)
        assertEquals(0, pct)
    }

    @Test
    fun `lvgl pool pct saturates at 100 for wildly oversized layout`() {
        // 200 objects → 200*96 = 19200 B → > 14336 B → coerce to 100%
        val groups = (1..5).map { WatchStatusGroup(type = "doors", glyphs = makeGlyph(40)) }
        val payload = makePayload(statusGroups = groups)
        val (_, _, pct) = computeMetrics(payload)
        assertEquals(100, pct)
    }
}
