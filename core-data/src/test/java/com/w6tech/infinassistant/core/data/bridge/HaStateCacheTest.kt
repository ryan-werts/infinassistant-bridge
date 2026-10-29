package com.w6tech.infinassistant.core.data.bridge

import com.w6tech.infinassistant.core.common.AppResult
import com.w6tech.infinassistant.core.domain.*
import com.w6tech.infinassistant.core.ha.HaErrorResult
import com.w6tech.infinassistant.core.ha.HaState
import com.w6tech.infinassistant.core.ha.HaSubscriber
import com.w6tech.infinassistant.core.ha.HaWebSocketClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state cache: one subscribe_entities subscription to the entities the watch needs, which
 * every consumer reads and follows; state_changed and get_states on an HA without it.
 */
class HaStateCacheTest {

    private val cache = HaStateCache(WireTestSupport.silentLogger)

    private fun state(entityId: String, state: String, vararg attributes: Pair<String, String>) =
        HaState(entityId = entityId, state = state, attributes = JsonObject(attributes.associate { (k, v) -> k to JsonPrimitive(v) }))

    /** What the changes say, entity by entity: (entity, (old state, new state)). */
    private fun List<HaStateChange>.said() = map { it.entityId to (it.old?.state to it.new?.state) }

    private fun TestScope.follow(): MutableList<HaStateChange> {
        val changes = mutableListOf<HaStateChange>()
        backgroundScope.launch { cache.changes.collect { changes += it } }
        runCurrent()
        return changes
    }

    // ── the entities ──

    private val face = WatchFaceLayoutPayload(
        version = 1,
        header = WatchHeader(timeFormat = "HH:mm"),
        screens = listOf(
            WatchScreen(screenType = 0, dashboard = WatchDashboard(
                // A glyph of two characters is not one the watch keeps, nor one the dashboard reads.
                statusGroups = listOf(WatchStatusGroup("motion", listOf(WatchGlyph("M", "binary_sensor.hall_motion"), WatchGlyph("Fr", "binary_sensor.fridge")))),
                sensorGroups = listOf(WatchSensorGroup("thermostats", listOf(WatchSensor("sensor.attic")))),
                weather = WatchWeather(entityId = "weather.home"),
            )),
            WatchScreen(screenType = 1, tiles = listOf(
                WatchTile(label = "PORCH", entityId = "light.porch", color = "#FFFFFF"),
                WatchTile(label = "GARAGE", entityId = "device:f42859bf90e3b114eb0ef6aee9e24b7c", color = "#FFFFFF"),
                WatchTile(label = "", entityId = "", color = "#FFFFFF"),
            )),
        ),
        homeControlConfig = WatchHomeControlConfig(listOf(WatchHomeControlDevice("fan.bedroom"))),
        logbookApp = WatchLogbookAppConfig(entityIds = listOf("lock.front")),
    )

    @Test
    fun `the entities watched are the tiles', the dashboard's, home control's and the logbook app's`() {
        assertEquals(
            setOf("light.garage_side", "light.porch", "binary_sensor.hall_motion", "sensor.attic",
                "weather.home", "fan.bedroom", "lock.front"),
            HaStateCache.watchedEntities(listOf("light.garage_side"), face),
        )
        // The defaults: home control's are the face's lights, switches and fans, the logbook's its motion sensors.
        assertEquals(
            setOf("light.porch", "binary_sensor.hall_motion", "sensor.attic", "weather.home", "binary_sensor.fridge"),
            HaStateCache.watchedEntities(emptyList(), face.copy(homeControlConfig = null, logbookApp = null)),
        )
        // No layout stored: the tiles', and never what HA cannot hold.
        assertEquals(
            setOf("light.garage_side"),
            HaStateCache.watchedEntities(listOf("light.garage_side", "device:abc", ""), null),
        )
    }

    // ── the subscription ──

    @Test
    fun `the first answer is the states, and the changes after it carry the old and the new state`() = runTest {
        val ha = WireTestSupport.FakeHa(listOf(
            state("light.kitchen", "off"), state("sensor.attic", "68.2", "unit_of_measurement" to "°F"), state("light.porch", "on"),
        ))
        val changes = follow()
        ha.connect(this, cache, { setOf("light.kitchen", "sensor.attic", "lock.front") })

        assertEquals(setOf("light.kitchen", "sensor.attic", "lock.front"), ha.subscriptions.single())
        assertTrue(cache.isReady)
        assertEquals("off", cache["light.kitchen"]?.state)
        assertNull("not watched", cache["light.porch"])
        assertNull("not in HA", cache["lock.front"])
        assertTrue("the first answer is no change", changes.isEmpty())

        ha.change("light.kitchen", "on")
        ha.change("sensor.attic", "68.2", attributes = mapOf("battery" to "80"))
        ha.change("lock.front", "locked")   // new to HA
        ha.remove("sensor.attic")
        runCurrent()

        assertEquals(
            listOf(
                "light.kitchen" to ("off" to "on"),
                "sensor.attic" to ("68.2" to "68.2"),
                "lock.front" to (null to "locked"),
                "sensor.attic" to ("68.2" to null),
            ),
            changes.said(),
        )
        assertEquals(JsonPrimitive("80"), changes[1].new!!.attributes["battery"])
        assertEquals(JsonPrimitive("°F"), changes[1].new!!.attributes["unit_of_measurement"])
        assertEquals(changes[0].new, cache["light.kitchen"])
        assertEquals("locked", cache["lock.front"]?.state)
        assertNull(cache["sensor.attic"])
    }

    @Test
    fun `new entities are subscribed again, and one that left is gone at once`() = runTest {
        val ha = WireTestSupport.FakeHa(listOf(state("light.kitchen", "on"), state("light.porch", "off"), state("lock.front", "locked")))
        var watched = setOf("light.kitchen", "light.porch")
        val changes = follow()
        ha.connect(this, cache, { watched })

        // The same entities: nothing to do.
        cache.sync(ha.client)
        assertEquals(1, ha.subscriptions.size)

        // The porch leaves and the lock comes, while the kitchen light goes off; HA's answer is on its way.
        watched = setOf("light.kitchen", "lock.front")
        ha.answerLater = true
        cache.sync(ha.client)
        assertEquals("the old subscription ended", listOf(1), ha.unsubscribed)
        assertEquals(setOf("light.kitchen", "lock.front"), ha.subscriptions.last())
        assertNull("gone before HA answers", cache["light.porch"])
        assertFalse(cache.isReady)
        ha.states["light.kitchen"] = state("light.kitchen", "off")

        ha.answer()
        runCurrent()
        assertTrue(cache.isReady)
        assertEquals("locked", cache["lock.front"]?.state)
        // The lock is new here, no change; the light changed between the two subscriptions.
        assertEquals(listOf("light.kitchen" to ("on" to "off")), changes.said())
    }

    @Test
    fun `a change made while HA was away is a change when it answers again`() = runTest {
        val ha = WireTestSupport.FakeHa(listOf(state("light.kitchen", "off"), state("light.porch", "on")))
        val changes = follow()
        ha.connect(this, cache, { setOf("light.kitchen", "light.porch") })

        ha.drop()
        ha.states["light.kitchen"] = state("light.kitchen", "on")
        ha.states.remove("light.porch")
        ha.connected = true
        assertTrue(cache.subscribe(ha.client) is AppResult.Ok)
        runCurrent()

        assertEquals(listOf("light.porch" to ("on" to null), "light.kitchen" to ("off" to "on")), changes.said())
    }

    @Test
    fun `no entities subscribe nothing, HA reading an empty list as every entity`() = runTest {
        val ha = WireTestSupport.FakeHa(listOf(state("light.kitchen", "on")))
        ha.connect(this, cache, { emptySet() })

        coVerify(exactly = 0) { ha.client.subscribeEntities(any(), any()) }
        assertTrue(cache.isReady)
        assertNull(cache["light.kitchen"])
    }

    @Test
    fun `the end of the connection leaves nothing served until HA answers again`() = runTest {
        val ha = WireTestSupport.FakeHa(listOf(state("light.kitchen", "on")))
        ha.connect(this, cache, { setOf("light.kitchen") })
        assertTrue(cache.awaitSnapshot(ha.client))

        ha.drop()
        assertFalse(cache.isReady)
        assertFalse("no connection, no wait", cache.awaitSnapshot(ha.client))

        // Back, but HA refuses the subscription, and state_changed too: nothing is served.
        ha.connected = true
        ha.refuses = true
        assertTrue(cache.subscribe(ha.client) is AppResult.Err)
        assertFalse(cache.awaitSnapshot(ha.client))

        ha.refuses = false
        assertTrue(cache.subscribe(ha.client) is AppResult.Ok)
        assertTrue(cache.awaitSnapshot(ha.client))
        assertEquals("on", cache["light.kitchen"]?.state)
    }

    @Test
    fun `a subscription lost under a client still connected is made again by the next read`() = runTest {
        // HaApi connects the session's client too: to another URL, that replaces its socket.
        val ha = WireTestSupport.FakeHa(listOf(state("light.kitchen", "on")))
        ha.connect(this, cache, { setOf("light.kitchen") })
        ha.drop()
        ha.connected = true

        assertTrue(cache.awaitSnapshot(ha.client))
        assertEquals(2, ha.subscriptions.size)
        assertEquals("on", cache["light.kitchen"]?.state)
    }

    @Test
    fun `fetchSelectedStates waits for HA's first answer, a few seconds at most`() = runTest {
        val handler = ProtocolHandler(WireTestSupport.silentLogger, StateManager()) { _, _, _, _ -> }
        val ha = WireTestSupport.FakeHa(listOf(state("light.porch", "on"), state("sensor.attic", "68")))
        ha.answerLater = true
        handler.stateCache.wanted = { setOf("light.porch", "sensor.attic") }
        // Right after HA (re)connects: subscribed, the answer still on its way.
        assertTrue(handler.stateCache.subscribe(ha.client) is AppResult.Ok)
        val asked = listOf(EntityConfig("light.porch"), EntityConfig("sensor.attic"))

        val read = async { handler.fetchSelectedStates(ha.client, asked) }
        advanceTimeBy(2_000)
        assertFalse("waits for the answer", read.isCompleted)
        ha.answer()
        runCurrent()
        assertEquals(listOf("on", "68"), (read.await() as AppResult.Ok).value.map { it.state })

        // An answer that never comes: the read gives up.
        assertTrue(handler.stateCache.subscribe(ha.client) is AppResult.Ok)
        val late = async { handler.fetchSelectedStates(ha.client, asked) }
        advanceTimeBy(HaStateCache.SNAPSHOT_WAIT_MS - 1)
        assertFalse(late.isCompleted)
        advanceTimeBy(2)
        assertTrue(late.await() is AppResult.Err)
    }

    // ── what HA sends after the subscription moved on ──

    /** A subscribe_entities event of subscription [id]. */
    private fun entitiesEvent(id: Int, event: JsonObject) = buildJsonObject {
        put("id", id)
        put("type", "event")
        put("event", event)
    }

    @Test
    fun `the events of a subscription left behind are ignored`() = runTest {
        val client = mockk<HaWebSocketClient>()
        val subscribers = mutableListOf<HaSubscriber>()
        every { client.isConnected() } returns true
        coEvery { client.subscribeEntities(any(), any()) } answers {
            subscribers += secondArg<HaSubscriber>()
            AppResult.Ok(subscribers.size)
        }
        coEvery { client.unsubscribe(any()) } returns AppResult.Ok(Unit)
        var watched = setOf("light.kitchen")
        cache.wanted = { watched }

        cache.subscribe(client)
        subscribers[0].onEvent(entitiesEvent(1, buildJsonObject {
            putJsonObject("a") { put("light.kitchen", WireTestSupport.compressed(state("light.kitchen", "on"))) }
        }))
        watched = setOf("light.kitchen", "light.porch")
        cache.sync(client)
        // HA sent this before it took the unsubscribe.
        subscribers[0].onEvent(entitiesEvent(1, buildJsonObject {
            putJsonObject("c") { putJsonObject("light.kitchen") { putJsonObject("+") { put("s", "off"); put("lc", 1_791_640_100.0) } } }
        }))
        subscribers[0].onEnded()
        assertFalse(cache.isReady)
        subscribers[1].onEvent(entitiesEvent(2, buildJsonObject {
            putJsonObject("a") {
                put("light.kitchen", WireTestSupport.compressed(state("light.kitchen", "on")))
                put("light.porch", WireTestSupport.compressed(state("light.porch", "off")))
            }
        }))

        assertTrue(cache.isReady)
        assertEquals("on", cache["light.kitchen"]?.state)
        assertEquals("off", cache["light.porch"]?.state)
    }

    // ── an HA without subscribe_entities (older than 2022.4) ──

    /** A state_changed event of subscription [id]; a null [state] is the entity removed. */
    private fun stateChanged(id: Int, entityId: String, state: String?) = buildJsonObject {
        put("id", id)
        put("type", "event")
        putJsonObject("event") {
            put("event_type", "state_changed")
            putJsonObject("data") {
                put("entity_id", entityId)
                putJsonObject("old_state") { put("entity_id", entityId); put("state", "unknown") }
                if (state == null) {
                    put("new_state", JsonNull)
                } else {
                    putJsonObject("new_state") {
                        put("entity_id", entityId)
                        put("state", state)
                        putJsonObject("attributes") {}
                        put("last_changed", "2026-10-10T13:46:40.250000+00:00")
                        put("last_updated", "2026-10-10T13:46:40.250000+00:00")
                    }
                }
            }
        }
    }

    @Test
    fun `no answer to subscribe_entities is no refusal, and keeps the house-wide feed off`() = runTest {
        // A slow HA, or a socket replaced under the request: the next connection subscribes again.
        val client = mockk<HaWebSocketClient>()
        every { client.isConnected() } returns true
        coEvery { client.subscribeEntities(any(), any()) } returns AppResult.Err(IllegalStateException("no response"))
        cache.wanted = { setOf("light.kitchen") }

        assertTrue(cache.subscribe(client) is AppResult.Err)
        assertFalse(cache.isReady)
        coVerify(exactly = 0) { client.subscribeEvents(any(), any()) }
        coVerify(exactly = 0) { client.getStates() }
    }

    @Test
    fun `entity IDs HA would refuse are not asked for`() {
        val layout = WatchFaceLayoutPayload(version = 1, header = WatchHeader(timeFormat = "HH:mm"), screens = emptyList())
        val ids = HaStateCache.watchedEntities(
            listOf("light.kitchen", "light.a__b", "_light.x", "light._x", "light.x_", "Light.Upper", "device:abc", ""),
            layout,
        )
        assertEquals(setOf("light.kitchen"), ids)
    }

    @Test
    fun `an HA that refuses subscribe_entities is followed by state_changed and get_states`() = runTest {
        val client = mockk<HaWebSocketClient>()
        var subscriber: HaSubscriber? = null
        var subscriptionId = 10
        var reads = 0
        every { client.isConnected() } returns true
        coEvery { client.subscribeEntities(any(), any()) } returns
            AppResult.Err(HaErrorResult("unknown_command", "subscribe_entities failed: Unknown command."))
        coEvery { client.subscribeEvents("state_changed", any()) } answers {
            subscriber = secondArg()
            AppResult.Ok(++subscriptionId)
        }
        coEvery { client.unsubscribe(any()) } returns AppResult.Ok(Unit)
        coEvery { client.getStates() } coAnswers {
            // The kitchen light goes on while HA answers the first read: the answer is older.
            if (reads++ == 0) subscriber!!.onEvent(stateChanged(subscriptionId, "light.kitchen", "on"))
            AppResult.Ok(listOf(state("light.kitchen", "off"), state("light.porch", "on"), state("sensor.attic", "68")))
        }
        var watched = setOf("light.kitchen", "sensor.attic")
        cache.wanted = { watched }
        val changes = follow()

        assertTrue(cache.subscribe(client) is AppResult.Ok)
        assertTrue(cache.isReady)
        assertEquals("the change, not the older answer", "on", cache["light.kitchen"]?.state)
        assertEquals("2026-10-10T13:46:40.250000+00:00", cache["light.kitchen"]?.lastChanged)
        assertEquals("68", cache["sensor.attic"]?.state)
        assertNull("not watched", cache["light.porch"])

        subscriber!!.onEvent(stateChanged(subscriptionId, "sensor.attic", "69"))
        subscriber!!.onEvent(stateChanged(subscriptionId, "light.porch", "off"))   // not watched
        subscriber!!.onEvent(stateChanged(subscriptionId, "light.kitchen", null))   // gone from HA
        runCurrent()
        assertEquals("69", cache["sensor.attic"]?.state)
        assertNull(cache["light.kitchen"])
        assertEquals(
            listOf("light.kitchen" to (null to "on"), "sensor.attic" to ("68" to "69"), "light.kitchen" to ("on" to null)),
            changes.said(),
        )

        // The entities change: subscribed again, and read again.
        watched = setOf("sensor.attic", "light.porch")
        cache.sync(client)
        coVerify { client.unsubscribe(11) }
        assertEquals(2, reads)
        assertTrue(cache.isReady)
        assertEquals("on", cache["light.porch"]?.state)
    }
}
