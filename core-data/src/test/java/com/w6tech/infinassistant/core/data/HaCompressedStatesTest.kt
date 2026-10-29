package com.w6tech.infinassistant.core.data

import com.w6tech.infinassistant.core.ha.HaCompressedStates
import com.w6tech.infinassistant.core.ha.HaState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HA's compressed states (subscribe_entities), as State.as_compressed_state writes them and
 * websocket_api/messages.py diffs them: the messages below are the shapes HA sends.
 */
class HaCompressedStatesTest {

    private fun event(message: String): JsonObject = Json.parseToJsonElement(message).jsonObject["event"]!!.jsonObject

    @Test
    fun `the first event has each entity's whole state, its times as ISO instants`() {
        val parsed = HaCompressedStates.parseEvent(event("""
            {"id":7,"type":"event","event":{"a":{
              "light.kitchen":{"s":"on","a":{"friendly_name":"Kitchen","brightness":180},"c":"01JADJ8A9SXTHZ5E8Z1G4N3Q2R","lc":1791640000.25},
              "sensor.attic":{"s":"68.2","a":{"unit_of_measurement":"°F"},
                "c":{"id":"01JADJ8B00000000000000000","parent_id":null,"user_id":"8a7c"},"lc":1791640000.0,"lu":1791640060.5}
            }}}
        """))

        assertEquals(setOf("light.kitchen", "sensor.attic"), parsed.added.keys)
        assertTrue(parsed.changed.isEmpty() && parsed.removed.isEmpty())
        val kitchen = parsed.added.getValue("light.kitchen")
        assertEquals("on", kitchen.state)
        assertEquals(JsonPrimitive("Kitchen"), kitchen.attributes["friendly_name"])
        assertEquals(JsonPrimitive(180), kitchen.attributes["brightness"])
        assertEquals("2026-10-10T13:46:40.250Z", kitchen.lastChanged)
        // No "lu": it equals "lc".
        assertEquals(kitchen.lastChanged, kitchen.lastUpdated)
        val attic = parsed.added.getValue("sensor.attic")
        assertEquals("2026-10-10T13:46:40Z", attic.lastChanged)
        assertEquals("2026-10-10T13:47:40.500Z", attic.lastUpdated)
    }

    private val kitchen = HaState(
        entityId = "light.kitchen",
        state = "off",
        attributes = Json.parseToJsonElement("""{"friendly_name":"Kitchen","brightness":180,"color_mode":"brightness"}""").jsonObject,
        lastChanged = "2026-10-10T13:46:40Z",
        lastUpdated = "2026-10-10T13:46:40Z",
    )

    @Test
    fun `a change keeps what it does not name`() {
        val parsed = HaCompressedStates.parseEvent(event("""
            {"id":7,"type":"event","event":{"c":{"light.kitchen":{"+":{"s":"on","a":{"brightness":255},"c":"01JADJ9","lc":1791640120.5}}}}}
        """))

        val now = parsed.changed.getValue("light.kitchen").applyTo(kitchen)
        assertEquals("on", now.state)
        assertEquals(JsonPrimitive(255), now.attributes["brightness"])
        assertEquals(JsonPrimitive("Kitchen"), now.attributes["friendly_name"])
        assertEquals(JsonPrimitive("brightness"), now.attributes["color_mode"])
        // A new last_changed is a new last_updated.
        assertEquals("2026-10-10T13:48:40.500Z", now.lastChanged)
        assertEquals(now.lastChanged, now.lastUpdated)
    }

    @Test
    fun `an attribute change moves last_updated only, and drops the attributes it lists`() {
        val parsed = HaCompressedStates.parseEvent(event("""
            {"id":7,"type":"event","event":{"c":{"light.kitchen":{"+":{"c":"01JADJA","lu":1791640180.0},"-":{"a":["color_mode"]}}}}}
        """))

        val now = parsed.changed.getValue("light.kitchen").applyTo(kitchen)
        assertEquals("off", now.state)
        assertEquals(setOf("friendly_name", "brightness"), now.attributes.keys)
        assertEquals(kitchen.lastChanged, now.lastChanged)
        assertEquals("2026-10-10T13:49:40Z", now.lastUpdated)
    }

    @Test
    fun `a context change alone changes nothing kept`() {
        val parsed = HaCompressedStates.parseEvent(event("""
            {"id":7,"type":"event","event":{"c":{"light.kitchen":{"+":{"c":{"user_id":"8a7c"}}}}}}
        """))
        assertEquals(kitchen, parsed.changed.getValue("light.kitchen").applyTo(kitchen))
    }

    @Test
    fun `removed entities, and an entity without a state left out`() {
        val removed = HaCompressedStates.parseEvent(event("""{"id":7,"type":"event","event":{"r":["light.kitchen","sensor.attic"]}}"""))
        assertEquals(listOf("light.kitchen", "sensor.attic"), removed.removed)

        val malformed = HaCompressedStates.parseEvent(event("""
            {"id":7,"type":"event","event":{"a":{"light.kitchen":{"a":{}},"sensor.attic":{"s":"68"}},"c":{"light.porch":[]}}}
        """))
        assertEquals(setOf("sensor.attic"), malformed.added.keys)
        assertNull("no time from HA", malformed.added.getValue("sensor.attic").lastChanged)
        assertTrue(malformed.changed.isEmpty())
    }

    @Test
    fun `times keep HA's microseconds`() {
        assertEquals("2026-10-10T13:46:40.250123Z", HaCompressedStates.isoTime(1_791_640_000.250123))
        assertEquals("2026-10-10T13:46:40Z", HaCompressedStates.isoTime(1_791_640_000.0))
        assertEquals("2026-10-10T13:46:41Z", HaCompressedStates.isoTime(1_791_640_000.9999996))
    }
}
