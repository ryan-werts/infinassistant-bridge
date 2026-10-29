package com.w6tech.infinassistant.core.data.bridge

import com.w6tech.infinassistant.core.common.AppResult
import com.w6tech.infinassistant.core.domain.EntityConfig
import com.w6tech.infinassistant.core.ha.HaState
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The watch keeps the first 16 entity states it is sent, and only the face's tiles read them.
 * Until 2.1.104 the states went out in HA's order, behind the settings entities, and with the
 * watch's 6 slots the later tiles read "---".
 */
class TileStateOrderTest {

    private fun state(entityId: String, state: String) = HaState(entityId = entityId, state = state, attributes = JsonObject(emptyMap()))

    @Test
    fun `states come back in the order asked for, not HA's`() = runTest {
        val ha = WireTestSupport.FakeHa(listOf(
            state("sensor.outside_temperature", "58"),
            state("cover.garage_door", "open"),
            state("light.porch_light", "on"),
            state("switch.master_bed_fan", "off"),
        ))
        val handler = ProtocolHandler(WireTestSupport.silentLogger, StateManager()) { _, _, _, _ -> }

        val tiles = listOf("light.porch_light", "switch.master_bed_fan", "cover.garage_door")
        val asked = (tiles + "sensor.outside_temperature" + "light.porch_light" + "light.not_in_ha").map { EntityConfig(entityId = it) }
        ha.connect(this, handler.stateCache, { asked.map { it.entityId }.toSet() })
        val result = handler.fetchSelectedStates(ha.client, asked)

        val items = (result as AppResult.Ok).value
        assertEquals(tiles + "sensor.outside_temperature", items.map { it.entityId })
        assertEquals(listOf("on", "off", "open", "58"), items.map { it.state })
    }
}
