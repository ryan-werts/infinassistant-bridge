package com.w6tech.infinassistant.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * WireText.fit, which cuts strings for the watch's fixed char buffers, and the home-control key of
 * the layout import, which the HA card spells "homeControl".
 */
class WireTextAndHomeControlImportTest {

    @Test
    fun `fit leaves a string that fits alone`() {
        assertEquals("Standup", WireText.fit("Standup", 47))
        assertEquals("", WireText.fit("", 5))
        assertEquals("09:30", WireText.fit("09:30", 5))
    }

    @Test
    fun `fit cuts ASCII at the byte budget`() {
        assertEquals("Garag", WireText.fit("Garage door", 5))
    }

    @Test
    fun `fit never splits a character`() {
        // "é" is 2 bytes and the emoji 4 in UTF-8.
        assertEquals("caf", WireText.fit("café", 4))
        assertEquals("café", WireText.fit("café", 5))
        assertEquals("a", WireText.fit("a😀b", 4))
        assertEquals("a😀", WireText.fit("a😀b", 5))
    }

    @Test
    fun `a cut string always fits its budget and decodes to a prefix`() {
        val text = "Réunion d'équipe — planification 2026 ✓ Überprüfung"
        for (budget in 0..80) {
            val cut = WireText.fit(text, budget)
            assertTrue("budget $budget", WireText.utf8Length(cut) <= budget)
            assertTrue(text.startsWith(cut))
        }
    }

    private fun layoutJson(homeControlKey: String) = """
        {
          "version": 2,
          "screens": [{"screenType": 1, "tileLayout": 4, "tiles": []}],
          "$homeControlKey": {"devices": [{"entityId": "light.kitchen", "name": "Kitchen"}, {"entityId": "lock.front"}]}
        }
    """.trimIndent()

    private fun parseDevices(json: String): List<WatchHomeControlDevice> =
        when (val result = WatchFaceParser.parseWatchFaceJson(json)) {
            is ParseResult.Success -> result.payload.homeControlConfig?.devices.orEmpty()
            else -> {
                fail("parse failed: $result")
                emptyList()
            }
        }

    @Test
    fun `the HA card's homeControl key is read`() {
        val devices = parseDevices(layoutJson("homeControl"))
        assertEquals(listOf("light.kitchen", "lock.front"), devices.map { it.entityId })
        assertEquals("Kitchen", devices[0].name)
    }

    @Test
    fun `the older home_control spelling is still read`() {
        assertEquals(listOf("light.kitchen", "lock.front"), parseDevices(layoutJson("home_control")).map { it.entityId })
    }

    private fun parseLogbookApp(key: String): WatchLogbookAppConfig? {
        val json = """
            {
              "version": 2,
              "screens": [{"screenType": 1, "tileLayout": 4, "tiles": []}],
              "$key": {"entityIds": ["binary_sensor.front_door", "lock.front"], "maxEntries": 8, "hoursBack": 6}
            }
        """.trimIndent()
        return when (val result = WatchFaceParser.parseWatchFaceJson(json)) {
            is ParseResult.Success -> result.payload.logbookApp
            else -> {
                fail("parse failed: $result")
                null
            }
        }
    }

    @Test
    fun `the HA card's logbookApp key is read`() {
        val app = parseLogbookApp("logbookApp")
        assertEquals(listOf("binary_sensor.front_door", "lock.front"), app?.entityIds)
        assertEquals(8, app?.maxEntries)
        assertEquals(6, app?.hoursBack)
    }

    @Test
    fun `the older logbook_app spelling is still read`() {
        assertEquals(listOf("binary_sensor.front_door", "lock.front"), parseLogbookApp("logbook_app")?.entityIds)
    }
}
