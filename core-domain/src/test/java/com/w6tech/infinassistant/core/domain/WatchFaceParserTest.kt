package com.w6tech.infinassistant.core.domain

import org.junit.Test
import org.junit.Assert.*

class WatchFaceParserTest {

    @Test
    fun `parse dashboard JSON with calendar events`() {
        val json = """
{
  "version": 1,
  "theme": {
    "background": "#000000",
    "accent": "#00FF00"
  },
  "header": {
    "time": {
      "format": "HH:mm",
      "align": "center"
    },
    "presenceIcons": []
  },
  "statusBar": {
    "battery": true,
    "steps": true,
    "heartRate": false,
    "notifications": true,
    "bluetooth": true
  },
  "screens": [
    {
      "screenType": 0,
      "tiles": [],
      "actions": [],
      "dashboard": {
        "events": [
          {
            "calendar": "calendar.personal"
          },
          {
            "calendar": "calendar.work"
          }
        ],
        "weather": {
          "entity": "weather.home"
        },
        "statusGroups": [],
        "sensorGroups": [],
        "notifications": []
      }
    }
  ],
  "tiles": [],
  "actions": []
}
        """.trimIndent()

        println("=== Testing Dashboard JSON Parsing ===")
        println()

        val result = WatchFaceParser.parseWatchFaceJson(json)

        when (result) {
            is ParseResult.Success -> {
                val payload = result.payload
                println("✓ Parse succeeded!")
                println()
                println("Payload structure:")
                println("  Version: ${payload.version}")
                println("  Screens: ${payload.screens.size}")
                println()

                payload.screens.forEachIndexed { idx, screen ->
                    println("Screen $idx:")
                    println("  screenType: ${screen.screenType} (0=dashboard, 1=tiles)")
                    println("  tiles: ${screen.tiles.size}")
                    println("  hasDashboard: ${screen.dashboard != null}")
                    println()

                    screen.dashboard?.let { dash ->
                        println("  Dashboard structure:")
                        println("    events: ${dash.events.size}")
                        dash.events.forEachIndexed { i, event ->
                            println("      Event $i: time='${event.time}' title='${event.title}'")
                        }
                        println("    weather: ${dash.weather != null}")
                        dash.weather?.let { w ->
                            println("      entityId: ${w.entityId}")
                        }
                        println("    statusGroups: ${dash.statusGroups.size}")
                        println("    sensorGroups: ${dash.sensorGroups.size}")
                        println("    notifications: ${dash.notifications.size}")
                        println()
                    }
                }

                // Assertions
                assertEquals(1, payload.screens.size)
                val screen = payload.screens[0]
                assertEquals(0, screen.screenType) // dashboard type
                assertNotNull(screen.dashboard)

                val dashboard = screen.dashboard!!
                assertEquals(2, dashboard.events.size)
                assertEquals("calendar:calendar.personal", dashboard.events[0].title)
                assertEquals("calendar:calendar.work", dashboard.events[1].title)
                assertNotNull(dashboard.weather)
                assertEquals("weather.home", dashboard.weather?.entityId)

                println("✓ All assertions passed!")
                println()
                println("RESULT: Dashboard is being parsed correctly!")
                println("  - 2 calendar events created")
                println("  - Weather widget configured")
                println("  - Data should be sent to watch")

            }
            is ParseResult.Error -> {
                println("✗ Parse failed!")
                println("Error: ${result.message}")
                fail("Parse should have succeeded")
            }
        }
    }

    // ── the dashboard's calendars ──

    /** A layout with one dashboard screen: [dashboardEvents] and [faceEvents] are JSON, or absent. */
    private fun dashboardLayout(dashboardEvents: String? = null, faceEvents: String? = null): String = """
        {
          "version": 2,
          "theme": { "background": "#000000", "accent": "#00FF00" },
          "header": { "time": { "format": "HH:mm", "align": "center" } },
          "statusBar": {},
          ${faceEvents?.let { "\"events\": $it," } ?: ""}
          "screens": [{
            "screenType": 0,
            "dashboard": {
              ${dashboardEvents?.let { "\"events\": $it," } ?: ""}
              "statusGroups": [],
              "sensorGroups": []
            }
          }]
        }
    """.trimIndent()

    private fun dashboardCalendars(json: String): List<String> {
        val result = WatchFaceParser.parseWatchFaceJson(json)
        val payload = (result as? ParseResult.Success)?.payload ?: throw AssertionError("parse failed: $result")
        return payload.screens.single().dashboard!!.events.map { it.title.removePrefix("calendar:") }
    }

    @Test
    fun `a dashboard without calendars of its own reads every calendar of the face`() {
        // maxEvents is how many events show, not how many calendars they are read from.
        val face = """{ "enabled": true, "calendars": ["calendar.a", "calendar.b", "calendar.c", "calendar.d"], "maxEvents": 2 }"""
        assertEquals(listOf("calendar.a", "calendar.b", "calendar.c", "calendar.d"), dashboardCalendars(dashboardLayout(faceEvents = face)))
    }

    @Test
    fun `blank and repeated calendars are left out`() {
        val face = """{ "enabled": true, "calendars": ["calendar.a", "", "calendar.b", "calendar.a"], "maxEvents": 1 }"""
        assertEquals(listOf("calendar.a", "calendar.b"), dashboardCalendars(dashboardLayout(faceEvents = face)))
        val own = """[{ "calendar": "calendar.x" }, { "calendar": " " }, { "calendar": "calendar.x" }]"""
        assertEquals(listOf("calendar.x"), dashboardCalendars(dashboardLayout(dashboardEvents = own)))
    }

    @Test
    fun `a dashboard's own calendars are its calendars, however many`() {
        val face = """{ "enabled": true, "calendars": ["calendar.a"], "maxEvents": 2 }"""
        val own = (1..5).joinToString(prefix = "[", postfix = "]") { """{ "calendar": "calendar.own$it" }""" }
        assertEquals((1..5).map { "calendar.own$it" }, dashboardCalendars(dashboardLayout(dashboardEvents = own, faceEvents = face)))
    }

    @Test
    fun `with the face's events off, a dashboard without calendars of its own has none`() {
        val face = """{ "enabled": false, "calendars": ["calendar.a", "calendar.b"], "maxEvents": 2 }"""
        assertEquals(emptyList<String>(), dashboardCalendars(dashboardLayout(faceEvents = face)))
    }

    // ── the screen count ──

    /** A layout of [count] screens: the first a dashboard, the rest tile screens. */
    private fun layoutOfScreens(count: Int): String {
        val screens = (0 until count).joinToString(",") { i ->
            if (i == 0) """{ "screenType": 0, "dashboard": { "statusGroups": [], "sensorGroups": [] } }"""
            else """{ "screenType": 1, "tileLayout": 4, "tiles": [] }"""
        }
        return """
            {
              "version": 2,
              "theme": { "background": "#000000", "accent": "#00FF00" },
              "header": { "time": { "format": "HH:mm", "align": "center" } },
              "statusBar": {},
              "screens": [$screens]
            }
        """.trimIndent()
    }

    @Test
    fun `a layout of six screens is accepted`() {
        val result = WatchFaceParser.parseWatchFaceJson(layoutOfScreens(6))
        val payload = (result as? ParseResult.Success)?.payload ?: throw AssertionError("parse failed: $result")
        assertEquals(6, payload.screens.size)
    }

    @Test
    fun `a layout of seven screens is refused, naming the maximum of six`() {
        val result = WatchFaceParser.parseWatchFaceJson(layoutOfScreens(7))
        val message = (result as? ParseResult.Error)?.message ?: throw AssertionError("expected an error: $result")
        assertTrue(message, message.contains("Too many screens: 7") && message.contains("Maximum is 6"))
    }
}
