package com.w6tech.infinassistant.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REQ009-FR-B4: `WatchFaceWire.stripNonRendered` leaves every field the firmware renders
 * alone and puts each field it never renders at its default, so the encoder omits it.
 * The send-path bytes are checked in `WatchFaceSendPathTest` and `ShortKeyFixtureTest`.
 */
class WatchFaceWireStripTest {

    private val full = WatchFaceFixtures.full
    private val stripped = WatchFaceWire.stripNonRendered(full)

    @Test
    fun `stripping twice changes nothing more`() {
        assertEquals(stripped, WatchFaceWire.stripNonRendered(stripped))
    }

    @Test
    fun `the phone-side copy is not modified`() {
        // WatchFaceLayoutPayload is immutable, so this checks the fixture still carries what was stripped.
        assertEquals("#FF8800", full.theme.accent)
        assertEquals(2, full.header.presenceIcons!!.size)
        assertTrue(full.statusBar.battery)
        assertEquals("doors", full.screens[0].dashboard!!.statusGroups[0].type)
    }

    @Test
    fun `every field the firmware never renders is at its default`() {
        assertEquals(WatchTheme(background = "", accent = ""), stripped.theme)
        assertEquals("", stripped.header.timeAlign)
        assertNull(stripped.header.presenceIcons)
        assertEquals(WatchStatusBar(false, false, false, false, false), stripped.statusBar)
        for (screen in stripped.screens) assertEquals(4, screen.tileLayout)
        val dash = stripped.screens[0].dashboard!!
        assertTrue(dash.notifications.isEmpty())
        assertTrue(dash.statusGroups.all { it.type == "" && it.glyphs.all { g -> g.entityId == null } })
        assertTrue(dash.sensorGroups.all { it.type == "" && it.sensors.all { s -> s.entityId == "" && s.displayType == "" } })
        assertEquals("", dash.weather!!.entityId)
        assertEquals(0, dash.weather!!.temp)
    }

    @Test
    fun `every field the firmware renders is unchanged`() {
        assertEquals(full.version, stripped.version)
        assertEquals(full.header.timeFormat, stripped.header.timeFormat)
        assertEquals(full.defaultActiveColor, stripped.defaultActiveColor)
        assertEquals(full.defaultInactiveColor, stripped.defaultInactiveColor)
        assertEquals(full.tiles, stripped.tiles)
        assertEquals(full.screens.map { it.screenType }, stripped.screens.map { it.screenType })
        assertEquals(full.screens.map { it.tiles }, stripped.screens.map { it.tiles })
        val d = full.screens[0].dashboard!!
        val s = stripped.screens[0].dashboard!!
        assertEquals(d.statusGroups.map { g -> g.glyphs.map { it.symbol to it.active } }, s.statusGroups.map { g -> g.glyphs.map { it.symbol to it.active } })
        assertEquals(d.sensorGroups.map { g -> g.sensors.map { it.value } }, s.sensorGroups.map { g -> g.sensors.map { it.value } })
        assertEquals(d.weather!!.condition, s.weather!!.condition)
        assertEquals(d.weather!!.hi, s.weather!!.hi)
        assertEquals(d.weather!!.lo, s.weather!!.lo)
        assertEquals(d.events, s.events)
        assertEquals(d.lastEnrichedAt, s.lastEnrichedAt)
        assertEquals(d.configurableValueLabel, s.configurableValueLabel)
        assertEquals(d.configurableValueValue, s.configurableValueValue)
        assertEquals(d.inlineMetric0Label, s.inlineMetric0Label)
        assertEquals(d.inlineMetric0Value, s.inlineMetric0Value)
        assertEquals(d.inlineMetric1Label, s.inlineMetric1Label)
        assertEquals(d.inlineMetric1Value, s.inlineMetric1Value)
    }

    @Test
    fun `the encoded bytes carry none of the stripped keys and are smaller, in both spellings`() {
        for (short in listOf(false, true)) {
            val before = ProtocolCbor.encodeWatchFaceLayout(full, short).bytes
            val after = ProtocolCbor.encodeWatchFaceLayout(stripped, short).bytes
            assertTrue("short=$short: ${after.size} must be under ${before.size}", after.size < before.size)
        }
        val long = String(ProtocolCbor.encodeWatchFaceLayout(stripped, shortKeys = false).bytes, Charsets.ISO_8859_1)
        for (key in listOf("theme", "background", "accent", "timeAlign", "presenceIcons", "statusBar", "tileLayout", "notifications")) {
            assertTrue("$key is absent from the long bytes", !long.contains(key))
        }
        // Rendered keys are still there.
        for (key in listOf("timeFormat", "screens", "glyphs", "symbol", "condition", "defaultActiveColor")) {
            assertTrue("$key is present in the long bytes", long.contains(key))
        }
    }

    @Test
    fun `the stripped bytes decode to the stripped layout`() {
        val bytes = ProtocolCbor.encodeWatchFaceLayout(stripped, shortKeys = false).bytes
        assertEquals(stripped, ProtocolCbor.decode(WatchFaceLayoutPayload.serializer(), bytes))
    }
}
