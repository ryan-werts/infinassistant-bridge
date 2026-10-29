package com.w6tech.infinassistant.core.data.bridge

import android.util.Log
import com.w6tech.infinassistant.core.common.AppResult
import com.w6tech.infinassistant.core.domain.*
import com.w6tech.infinassistant.core.ha.HaState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * LOGBOOK_UPDATE against what the firmware reads (BridgeController::HandleLogbookUpdate:
 * {"entries": [{"t", "s", "n"}]}, "t" and "s" required, 8 entries kept from 2.4.91). Until 2.1.97 the entry text
 * went out as "x" with no "s", so every entry was dropped.
 */
class LogbookWireTest {

    private val handler = ProtocolHandler(WireTestSupport.silentLogger, StateManager()) { _, _, _, _ -> }

    @Test
    fun `a logbook update carries the keys the watch reads`() = runTest {
        val watch = WireTestSupport.Recorder()
        handler.sendToWatch(watch.transport, Protocol.Type.LOGBOOK_UPDATE, 5, LogbookUpdatePayload(
            screenIndex = LogbookOrchestrator.APP_SCREEN_INDEX,
            entries = listOf(LogbookEntry(time = "21:05", state = "ON", subject = "Kitchen"))))

        val (type, bytes) = watch.sent.single()
        assertEquals(Protocol.Type.LOGBOOK_UPDATE.wire, type)
        val map = WireTestSupport.decodeMap(bytes)
        assertTrue(map.containsKey("entries"))
        assertEquals(listOf(mapOf("t" to "21:05", "s" to "ON", "n" to "Kitchen")), map["entries"])
    }

    private fun event(epochSeconds: Double, entityId: String, state: String? = null, message: String? = null, name: String? = null) =
        buildJsonObject {
            put("when", epochSeconds)
            put("entity_id", entityId)
            state?.let { put("state", it) }
            message?.let { put("message", it) }
            name?.let { put("name", it) }
        }

    @Test
    fun `entries are newest first, with short states and names that fit`() {
        val base = 1_791_640_000.0   // 2026-10-10 13:46:40 UTC
        val events = listOf(   // oldest first, as logbook/get_events returns them
            event(base, "light.kitchen", state = "on", name = "Kitchen"),
            event(base + 60, "binary_sensor.front_door", message = "opened", name = "Front door"),
            event(base + 120, "lock.front", message = "was unlocked"),
            event(base + 180, "person.ryan", message = "changed to Home"),
            event(base + 240, "binary_sensor.main_floor_motion", message = "detected motion",
                name = "Main floor motion sensor in the hallway by the stairs"),
            event(base + 300, "light.kitchen", state = "off", name = "Kitchen"),
        )
        val entries = LogbookOrchestrator.toEntries(events, mapOf("lock.front" to "Front Lock"), ZoneId.of("UTC"))

        assertEquals(6, entries.size)
        assertEquals(listOf("13:51", "13:50", "13:49", "13:48", "13:47", "13:46"), entries.map { it.time })
        assertEquals(listOf("OFF", "TRGD", "HOME", "ULKD", "OPN", "ON"), entries.map { it.state })
        assertEquals("Kitchen", entries[0].subject)
        assertEquals("Ryan", entries[2].subject)   // no name anywhere: from the entity ID
        assertEquals("Front Lock", entries[3].subject)   // the friendly name from the states
        assertTrue(WireText.utf8Length(entries[1].subject) <= LogbookOrchestrator.MAX_SUBJECT_BYTES)
        assertTrue("Main floor motion sensor in the hallway by the stairs".startsWith(entries[1].subject))
    }

    @Test
    fun `states and messages shorten to what the watch's seven bytes hold`() {
        val cases = mapOf(
            "on" to "ON", "turned off" to "OFF", "open" to "OPN", "closed" to "CLSD",
            "locked" to "LCKD", "was unlocked" to "ULKD", "home" to "HOME", "not_home" to "AWAY",
            "changed to Away" to "AWAY", "unavailable" to "N/A", "became unavailable" to "N/A",
            "unknown" to "?", "" to "?", "detected motion" to "TRGD", "cleared (no motion detected)" to "CLR",
            "playing" to "PLAY", "21.5" to "21.5", "changed to Heat Cool Mode" to "HEAT CO",
        )
        for ((raw, short) in cases) {
            assertEquals(raw, short, LogbookOrchestrator.shortState(raw))
        }
    }

    private fun layoutWithLogbook(vararg entityIds: String) = WatchFaceLayoutPayload(
        version = 1,
        header = WatchHeader(timeFormat = "HH:mm"),
        logbookApp = WatchLogbookAppConfig(entityIds = entityIds.toList()),
    )

    private fun named(entityId: String, state: String, name: String) =
        HaState(entityId = entityId, state = state, attributes = JsonObject(mapOf("friendly_name" to JsonPrimitive(name))))

    /**
     * HA with [states], as the bridge has it: its state cache watches [layout]'s entities and
     * [more], and hands its changes to [orchestrator].
     */
    private fun TestScope.connect(
        orchestrator: LogbookOrchestrator,
        watch: WireTestSupport.Recorder,
        layout: WatchFaceLayoutPayload,
        vararg states: HaState,
        more: List<String> = emptyList(),
    ): WireTestSupport.FakeHa {
        val ha = WireTestSupport.FakeHa(states.toList())
        ha.connect(this, handler.stateCache, { HaStateCache.watchedEntities(more, layout) }) {
            orchestrator.onStateChange(it, ha.client, watch.transport, this)
        }
        return ha
    }

    @Test
    fun `a state change of a logbook entity pushes the entries once, two seconds later`() = runTest {
        val now = Instant.now()
        val layout = layoutWithLogbook("lock.front")
        val orchestrator = LogbookOrchestrator(WireTestSupport.silentLogger, handler, { layout }) { _, _, _, _ -> }
        val watch = WireTestSupport.Recorder()
        val ha = connect(orchestrator, watch, layout,
            named("lock.front", "locked", "Front Lock"), HaState(entityId = "light.not_in_the_logbook", state = "off"),
            more = listOf("light.not_in_the_logbook"))
        coEvery { ha.client.getLogbook(any(), any(), any()) } returns AppResult.Ok(listOf(
            event(now.epochSecond.toDouble(), "lock.front", state = "unlocked")))

        ha.change("lock.front", "unlocked", at = now)
        ha.change("light.not_in_the_logbook", "on")
        advanceTimeBy(1_000)
        ha.change("lock.front", "locked", at = now.plusSeconds(1))
        advanceTimeBy(1_500)
        assertTrue("nothing before the delay", watch.sent.isEmpty())
        advanceUntilIdle()

        val (type, bytes) = watch.sent.single()
        assertEquals(Protocol.Type.LOGBOOK_UPDATE.wire, type)
        @Suppress("UNCHECKED_CAST")
        val entries = WireTestSupport.decodeMap(bytes)["entries"] as List<Map<String, Any?>>
        // HA's logbook has the unlock; the lock a second later is not in it yet.
        assertEquals(listOf("LCKD", "ULKD"), entries.map { it["s"] })
        assertEquals(listOf("Front Lock", "Front Lock"), entries.map { it["n"] })
        coVerify(exactly = 1) { ha.client.getLogbook(listOf("lock.front"), any(), LogbookOrchestrator.FETCH_LIMIT) }
    }

    @Test
    fun `nothing is pushed when the face has neither logbook entities nor motion sensors`() = runTest {
        val orchestrator = LogbookOrchestrator(WireTestSupport.silentLogger, handler, { layoutWithLogbook() }) { _, _, _, _ -> }
        val watch = WireTestSupport.Recorder()

        orchestrator.sendLogbookApp(mockk(), watch.transport, this)
        val change = HaStateChange("lock.front", HaState(entityId = "lock.front", state = "locked"),
            HaState(entityId = "lock.front", state = "unlocked", lastChanged = Instant.now().toString()))
        orchestrator.onStateChange(change, mockk(), watch.transport, this)
        advanceUntilIdle()

        assertTrue(watch.sent.isEmpty())
    }

    private val motionSensors = listOf(
        "binary_sensor.main_floor_motion", "binary_sensor.downstairs_motion", "binary_sensor.kid_s_room_motion",
    )

    /** A dashboard with a door and the three motion sensors, as M, D and K. */
    private fun faceWithMotion(vararg logbookEntityIds: String) = layoutWithLogbook(*logbookEntityIds).copy(
        screens = listOf(WatchScreen(screenType = 0, dashboard = WatchDashboard(statusGroups = listOf(
            WatchStatusGroup("doors", listOf(WatchGlyph("F", "binary_sensor.front_door"))),
            WatchStatusGroup("motion", listOf("M", "D", "K").zip(motionSensors) { s, e -> WatchGlyph(s, e) }),
        )))),
    )

    /** The user's face (core-domain's reference layout), parsed as a push from HA is. */
    private fun referenceLayout(): WatchFaceLayoutPayload {
        val relative = "core-domain/src/test/resources/user-layout-2026-09-24.json"
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, relative).isFile) dir = dir.parentFile
        val json = File(requireNotNull(dir) { "$relative not found" }, relative).readText()
        // WatchFaceParser logs through android.util.Log, which is a stub in this module's unit tests.
        mockkStatic(Log::class)
        try {
            every { Log.d(any<String>(), any<String>()) } returns 0
            every { Log.w(any<String>(), any<String>()) } returns 0
            return (WatchFaceParser.parseWatchFaceJson(json) as ParseResult.Success).payload
        } finally {
            unmockkStatic(Log::class)
        }
    }

    @Test
    fun `with no entities of its own the logbook app logs the face's motion sensors`() {
        val face = referenceLayout()
        assertEquals(emptyList<String>(), face.logbookApp?.entityIds)   // as the card exported it
        assertEquals(WatchLogbook(motionSensors, maxEntries = 10, hoursBack = 12), LogbookOrchestrator.appLogbook(face))

        val own = face.copy(logbookApp = WatchLogbookAppConfig(entityIds = listOf("lock.front"), hoursBack = 24))
        assertEquals(WatchLogbook(listOf("lock.front"), maxEntries = 10, hoursBack = 24), LogbookOrchestrator.appLogbook(own))
        assertEquals(motionSensors, LogbookOrchestrator.appLogbook(faceWithMotion())?.entityIds)
    }

    @Test
    fun `a motion sensor's change pushes the entries when the app lists no entities`() = runTest {
        val face = faceWithMotion()
        val orchestrator = LogbookOrchestrator(WireTestSupport.silentLogger, handler, { face }) { _, _, _, _ -> }
        val watch = WireTestSupport.Recorder()
        val ha = connect(orchestrator, watch, face,
            HaState(entityId = "binary_sensor.front_door", state = "off"), named(motionSensors[2], "off", "Kid's Room Motion"))
        coEvery { ha.client.getLogbook(any(), any(), any()) } returns AppResult.Ok(listOf(
            event(1_791_640_000.0, motionSensors[2], state = "on")))

        ha.change("binary_sensor.front_door", "on")
        // At the time the logbook has: the live change is the logbook's own event.
        ha.change(motionSensors[2], "on", at = Instant.ofEpochSecond(1_791_640_000))
        runCurrent()
        advanceUntilIdle()

        @Suppress("UNCHECKED_CAST")
        val entry = (WireTestSupport.decodeMap(watch.sent.single().second)["entries"] as List<Map<String, Any?>>).single()
        assertEquals("ON", entry["s"])
        assertEquals("Kid's Room Motion", entry["n"])
        assertTrue(Regex("\\d\\d:\\d\\d").matches(entry["t"] as String))   // in the phone's time zone
        coVerify(exactly = 1) { ha.client.getLogbook(motionSensors, 12, LogbookOrchestrator.FETCH_LIMIT) }
    }

    @Test
    fun `a change HA's logbook does not list yet still goes out, first`() = runTest {
        // HA commits its recorder every 5 s: 2 s after the change, logbook/get_events lacks it.
        val face = faceWithMotion()
        val orchestrator = LogbookOrchestrator(WireTestSupport.silentLogger, handler, { face }) { _, _, _, _ -> }
        val watch = WireTestSupport.Recorder()
        val ha = connect(orchestrator, watch, face, named(motionSensors[1], "off", "Downstairs Motion"))
        val older = Instant.now().minusSeconds(3600)
        coEvery { ha.client.getLogbook(any(), any(), any()) } returns AppResult.Ok(listOf(
            event(older.epochSecond.toDouble(), motionSensors[0], state = "off", name = "Main Floor Motion")))

        ha.change(motionSensors[1], "on")
        ha.change(motionSensors[1], "on", attributes = mapOf("battery" to "80"))   // attributes only
        runCurrent()
        advanceUntilIdle()

        @Suppress("UNCHECKED_CAST")
        val entries = WireTestSupport.decodeMap(watch.sent.single().second)["entries"] as List<Map<String, Any?>>
        assertEquals(listOf("Downstairs Motion" to "ON", "Main Floor Motion" to "OFF"), entries.map { it["n"] to it["s"] })
    }

    @Test
    fun `a live change the logbook already lists is not shown twice`() {
        val at = Instant.parse("2026-10-10T13:46:40.250Z")
        val locked = HaState(entityId = "lock.front", state = "locked")
        val unlocked = HaState(entityId = "lock.front", state = "unlocked", lastChanged = "2026-10-10T13:46:40.250000+00:00")
        val live = LogbookOrchestrator.liveEvent(HaStateChange("lock.front", locked, unlocked))!!
        assertNull("an attribute change", LogbookOrchestrator.liveEvent(HaStateChange("lock.front", unlocked, unlocked.copy(lastUpdated = "2026-10-10T13:50:00+00:00"))))
        assertNull("an entity's first state", LogbookOrchestrator.liveEvent(HaStateChange("lock.front", null, unlocked)))
        val listed = event(at.epochSecond + 0.25, "lock.front", state = "unlocked")
        val merged = LogbookOrchestrator.withLive(listOf(listed), listOf(live), setOf("lock.front"), at.minusSeconds(60))
        assertEquals(1, merged.size)

        val elsewhere = LogbookOrchestrator.withLive(emptyList(), listOf(live), setOf("light.kitchen"), at.minusSeconds(60))
        assertTrue("an entity no longer logged", elsewhere.isEmpty())
        val tooOld = LogbookOrchestrator.withLive(emptyList(), listOf(live), setOf("lock.front"), at.plusSeconds(1))
        assertTrue("before the window", tooOld.isEmpty())
    }

    @Test
    fun `entries are cut to what one frame holds, newest kept`() = runTest {
        val layout = layoutWithLogbook("lock.front")
        val orchestrator = LogbookOrchestrator(WireTestSupport.silentLogger, handler, { layout }) { _, _, _, _ -> }
        val watch = WireTestSupport.Recorder()
        val ha = connect(orchestrator, watch, layout)
        val now = Instant.now().epochSecond.toDouble()
        coEvery { ha.client.getLogbook(any(), any(), any()) } returns AppResult.Ok((0 until 12).map { i ->
            event(now - 600 + i * 30, "lock.front", state = if (i % 2 == 0) "locked" else "unlocked",
                name = "Front lock $i by the garage")
        })

        orchestrator.sendLogbookApp(ha.client, watch.transport, this)
        advanceUntilIdle()

        val bytes = watch.sent.single().second
        assertTrue("${bytes.size} bytes", bytes.size <= WireFrame.SINGLE_FRAME_PAYLOAD)
        @Suppress("UNCHECKED_CAST")
        val entries = WireTestSupport.decodeMap(bytes)["entries"] as List<Map<String, Any?>>
        assertTrue(entries.size in 4 until LogbookOrchestrator.MAX_ENTRIES)
        assertEquals("Front lock 11 by the garage", entries[0]["n"])   // the newest
    }

    @Test
    fun `nothing is sent when HA's logbook cannot be read, so the watch keeps its entries`() = runTest {
        val layout = layoutWithLogbook("lock.front")
        val orchestrator = LogbookOrchestrator(WireTestSupport.silentLogger, handler, { layout }) { _, _, _, _ -> }
        val watch = WireTestSupport.Recorder()
        val ha = connect(orchestrator, watch, layout)
        coEvery { ha.client.getLogbook(any(), any(), any()) } returns AppResult.Err(IllegalStateException("no response"))

        orchestrator.sendLogbookApp(ha.client, watch.transport, this)
        advanceUntilIdle()

        assertTrue(watch.sent.isEmpty())
    }
}
