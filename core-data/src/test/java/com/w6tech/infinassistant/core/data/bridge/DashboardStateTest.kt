package com.w6tech.infinassistant.core.data.bridge

import com.w6tech.infinassistant.core.ble.FrameCodec
import com.w6tech.infinassistant.core.common.AppResult
import com.w6tech.infinassistant.core.data.CalendarEvent
import com.w6tech.infinassistant.core.data.HaApi
import com.w6tech.infinassistant.core.domain.*
import com.w6tech.infinassistant.core.ha.HaState
import com.w6tech.infinassistant.core.ha.HaWebSocketClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DASHBOARD_STATE (0x20): what the app computes and sends, against what firmware 2.4.96 reads
 * (DashboardState.cpp, and StatusFingerprint in ActiveScreenView.cpp).
 */
class DashboardStateTest {

    private fun glyph(symbol: String, entityId: String) = WatchGlyph(symbol = symbol, entityId = entityId)

    private fun group(type: String, symbols: String, prefix: String = type) =
        WatchStatusGroup(type = type, glyphs = symbols.mapIndexed { i, c -> glyph(c.toString(), "binary_sensor.${prefix}_$i") })

    /** The owner's dashboard: the groups of the golden vector, three sensor groups and the weather. */
    private val ownerDash = WatchDashboard(
        statusGroups = listOf(
            group("doors", "FSMDD"), group("garage", "??"), group("motion", "MDK"),
            group("lights", "PKW"), group("media", "LHM?"),
        ),
        sensorGroups = listOf(
            WatchSensorGroup("thermostats", listOf(WatchSensor("climate.main"), WatchSensor("sensor.attic"))),
            WatchSensorGroup("freezers", listOf(WatchSensor("sensor.f1"), WatchSensor("sensor.f2"), WatchSensor("sensor.f3"))),
            WatchSensorGroup("beacons", listOf(WatchSensor("device_tracker.a"), WatchSensor("device_tracker.b"), WatchSensor("person.c"))),
        ),
        weather = WatchWeather(entityId = "weather.home"),
        events = listOf(WatchEvent(time = "", title = "calendar:calendar.work")),
    )

    private fun state(entityId: String, state: String, attrs: Map<String, Any> = emptyMap()) = HaState(
        entityId = entityId,
        state = state,
        attributes = JsonObject(attrs.mapValues { (_, v) -> if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) }),
    )

    private val ownerStates = listOf(
        state("binary_sensor.doors_1", "on"),
        state("binary_sensor.garage_0", "open"),
        state("binary_sensor.lights_0", "on"),
        state("binary_sensor.lights_1", "on"),
        state("binary_sensor.media_1", "playing"),
        state("climate.main", "heat", mapOf("current_temperature" to 71.6)),
        state("sensor.attic", "68.2", mapOf("unit_of_measurement" to "°F")),
        state("sensor.f1", "0.3", mapOf("unit_of_measurement" to "°F")),
        state("sensor.f2", "unavailable"),
        state("sensor.f3", "16.0", mapOf("unit_of_measurement" to "°F")),
        state("device_tracker.a", "home"),
        state("device_tracker.b", "not_home"),
        state("person.c", "Work"),
        state("weather.home", "partlycloudy", mapOf("temperature" to 58.4)),
    ).associateBy { it.entityId }

    private fun event(startEpoch: Long, title: String) = CalendarEvent(time = "", title = title, startEpoch = startEpoch)

    private val now = 1_791_910_000L

    private fun build(
        dash: WatchDashboard = ownerDash,
        states: Map<String, HaState> = ownerStates,
        events: List<CalendarEvent> = emptyList(),
        alarm: (String, Int?, Boolean) -> Boolean = { _, _, _ -> false },
    ) = DashboardStateBuilder.build(dash, states, 65 to 48, events, now, alarm)

    // ── the fingerprint ──

    @Test
    fun `the fingerprint is the watch's golden vector`() {
        // dashboard_state_test.cpp hashes the same five groups to the same value, and both read it
        // from the golden vectors (firmware/infinitime_overlay/vectors/protocol-vectors.json).
        assertEquals(
            GoldenVectors.groups("status_fingerprint"),
            ownerDash.statusGroups.map { g -> g.glyphs.joinToString("") { it.symbol } },
        )
        assertEquals(GoldenVectors.value("status_fingerprint"), DashboardStateBuilder.fingerprint(ownerDash))
    }

    @Test
    fun `the bits follow the glyphs the watch keeps`() {
        // LayoutDecoder.cpp drops a glyph whose symbol is not one ASCII character, so B is the
        // second glyph on the watch and C the third.
        val dash = WatchDashboard(statusGroups = listOf(WatchStatusGroup("doors", listOf(
            glyph("A", "x.a"), glyph("Fr", "x.fr"), glyph("B", "x.b"), glyph("é", "x.e"), glyph("C", "x.c"),
        ))))
        val states = listOf(state("x.a", "on"), state("x.fr", "on"), state("x.e", "on"), state("x.c", "open")).associateBy { it.entityId }
        val p = build(dash, states)
        assertEquals(listOf(DashStateGroup(type = 1, bits = 0b101)), p.groups)
        val asStored = WatchDashboard(statusGroups = listOf(WatchStatusGroup("doors", listOf(
            glyph("A", "x.a"), glyph("B", "x.b"), glyph("C", "x.c"),
        ))))
        assertEquals(DashboardStateBuilder.fingerprint(asStored), p.fingerprint)
    }

    @Test
    fun `glyphs past the watch's ten have no bit`() {
        val dash = WatchDashboard(statusGroups = listOf(group("lights", "ABCDEFGHIJKL")))
        val states = (0 until 12).associate { "binary_sensor.lights_$it" to state("binary_sensor.lights_$it", "on") }
        assertEquals(0x3FF, build(dash, states).groups.single().bits)
    }

    @Test
    fun `the status bits of the owner's dashboard`() {
        val p = build()
        assertEquals(listOf(1 to 0b10, 2 to 0b1, 3 to 0, 4 to 0b11, 5 to 0b10), p.groups.map { it.type to it.bits })
    }

    // ── sensors, weather, events ──

    @Test
    fun `sensors read as the watch draws them`() {
        val p = build(alarm = { id, _, _ -> id == "sensor.f3" })
        assertEquals(
            listOf(
                DashStateSensors(type = 1, values = listOf(72, 68)),
                DashStateSensors(type = 2, values = listOf(0, null, 16), alerts = 0b100),
                DashStateSensors(type = 3, values = listOf(1, 0, 0)),
            ),
            p.sensors,
        )
    }

    @Test
    fun `a beacon HA does not know is no reading, not away`() {
        val states = ownerStates + ("device_tracker.a" to state("device_tracker.a", "unavailable"))
        assertEquals(listOf(null, 0, 0), build(states = states).sensors[2].values)
    }

    @Test
    fun `the weather is a code with today's high and low`() {
        assertEquals(DashStateWeather(code = 3, temp = 58, hi = 65, lo = 48), build().weather)
        val unknown = ownerStates + ("weather.home" to state("weather.home", "unavailable"))
        assertNull(build(states = unknown).weather)
        val noTemp = ownerStates + ("weather.home" to state("weather.home", "sunny"))
        assertNull(build(states = noTemp).weather)
        assertEquals(DashStateWeather(code = 4, temp = 58), DashboardStateBuilder.build(
            ownerDash, ownerStates + ("weather.home" to state("weather.home", "cloudy", mapOf("temperature" to 58))),
            null, emptyList(), now) { _, _, _ -> false }.weather)
    }

    @Test
    fun `events are the next two, soonest first, titles cut to 24 bytes`() {
        val events = listOf(
            event(now + 7200, "Dentist"),
            event(now - 3600, "Long gone"),                       // over: left out
            event(now - 300, "Standup"),                          // began 5 min ago: the watch says "now"
            event(0, "All day"),                                  // no start: left out
            event(now + 600, "Design review with the whole team"),
        )
        val p = build(events = events)
        assertEquals(listOf(now - 300, now + 600), p.events.map { it.start })
        assertEquals("Standup", p.events[0].title)
        val short = build(events = listOf(event(now + 600, "Design review with the whole team"))).events.single().title
        assertEquals("Design review with the w", short)
        assertEquals(24, short.toByteArray().size)
        val utf8 = build(events = listOf(event(now + 60, "Café crème brûlée réunion"))).events.single().title
        assertTrue(utf8.toByteArray().size <= 24)
        assertTrue("Café crème brûlée réunion".startsWith(utf8))
    }

    @Test
    fun `the calendars are the dashboard's own, else all of the face's`() {
        val layout = WatchFaceLayoutPayload(version = 1, header = WatchHeader(timeFormat = "HH:mm"),
            calendarEntityIds = listOf("calendar.a", "calendar.b", "", "calendar.c", "calendar.a"))
        assertEquals(listOf("calendar.work"), DashboardStateBuilder.calendarIds(ownerDash, layout))
        // A layout stored by app 2.1.106 has the events in place of the references. MAX_EVENTS is
        // how many events show, not how many calendars they are read from.
        val enriched = ownerDash.copy(events = listOf(WatchEvent(time = "09:00", title = "Standup", startEpoch = now)))
        assertEquals(listOf("calendar.a", "calendar.b", "calendar.c"), DashboardStateBuilder.calendarIds(enriched, layout))
    }

    // ── on the wire ──

    @Test
    fun `the message carries the keys the watch reads`() {
        val p = build(events = listOf(event(now + 600, "Design review")), alarm = { id, _, _ -> id == "sensor.f3" })
        val map = WireTestSupport.decodeMap(DashboardStateBuilder.encode(p))
        assertEquals(setOf("f", "t", "g", "s", "w", "e"), map.keys)
        assertEquals(GoldenVectors.value("status_fingerprint"), map["f"])
        assertEquals(now, map["t"])
        @Suppress("UNCHECKED_CAST")
        val groups = map["g"] as List<Map<String, Any?>>
        assertEquals(mapOf("t" to 1L, "b" to 2L), groups[0])
        @Suppress("UNCHECKED_CAST")
        val sensors = map["s"] as List<Map<String, Any?>>
        assertEquals(mapOf("t" to 1L, "v" to listOf(72L, 68L)), sensors[0])          // no alert bits: no "a"
        assertEquals(mapOf("t" to 2L, "v" to listOf(0L, null, 16L), "a" to 4L), sensors[1])
        assertEquals(mapOf("c" to 3L, "t" to 58L, "h" to 65L, "l" to 48L), map["w"])
        assertEquals(listOf(mapOf("s" to now + 600, "n" to "Design review")), map["e"])
    }

    @Test
    fun `the largest dashboard still fits one frame, the events shortened first`() {
        val dash = WatchDashboard(
            statusGroups = listOf("doors", "garage", "motion", "lights", "media").map { group(it, "ABCDEFGHIJ") },
            sensorGroups = listOf("thermostats", "freezers", "beacons").map { type ->
                WatchSensorGroup(type, (0 until 7).map { WatchSensor("sensor.${type}_$it") })
            },
            weather = WatchWeather(entityId = "weather.home"),
        )
        val states = buildMap {
            dash.statusGroups.flatMap { it.glyphs }.forEach { put(it.entityId!!, state(it.entityId!!, "on")) }
            dash.sensorGroups.flatMap { it.sensors }.forEach { put(it.entityId, state(it.entityId, "-1234")) }
            put("weather.home", state("weather.home", "lightning-rainy", mapOf("temperature" to -40)))
        }
        val events = listOf(event(now + 60, "A title well over twenty-four bytes"), event(now + 120, "Another title well over the limit"))
        val p = DashboardStateBuilder.build(dash, states, -100 to -120, events, now) { _, _, _ -> true }
        assertTrue(DashboardStateBuilder.encode(p).size <= DashboardStateBuilder.MAX_BYTES)
        assertEquals(5, p.groups.size)
        assertEquals(3, p.sensors.size)
        // The owner's dashboard keeps both events at full length.
        val owner = build(events = events)
        assertEquals(2, owner.events.size)
        assertEquals(24, owner.events[0].title.toByteArray().size)
        assertTrue(DashboardStateBuilder.encode(owner).size <= DashboardStateBuilder.MAX_BYTES)
    }

    // ── the freezer alarm ──

    @Test
    fun `a freezer alarms after 20 minutes over 15 F`() {
        val t = FreezerAlertTracker()
        val minute = 60_000L
        assertFalse(t.alert("f", 16, celsius = false, nowMs = 0))
        assertEquals(20 * minute, t.nextAlarmAtMs(0))
        assertFalse(t.alert("f", 17, celsius = false, nowMs = 19 * minute))
        assertTrue(t.alert("f", 17, celsius = false, nowMs = 20 * minute))
        // Back to 15 or below: the clock starts again.
        assertFalse(t.alert("f", 15, celsius = false, nowMs = 21 * minute))
        assertFalse(t.alert("f", 16, celsius = false, nowMs = 22 * minute))
        assertFalse(t.alert("g", null, celsius = false, nowMs = 0))
        // Celsius: -9 is the line.
        assertFalse(t.alert("c", -9, celsius = true, nowMs = 0))
        assertFalse(t.alert("c", -8, celsius = true, nowMs = 0))
        assertTrue(t.alert("c", -8, celsius = true, nowMs = 20 * minute))
    }

    // ── the orchestrator ──

    private inner class Bridge(
        scope: TestScope,
        features: Int = Protocol.Features.DASHBOARD_STATE,
        dash: WatchDashboard = ownerDash,
    ) {
        val watch = WireTestSupport.Recorder()
        val handler = ProtocolHandler(WireTestSupport.silentLogger, StateManager()) { _, _, _, _ -> }.also {
            // The watch's HELLO sets the features, as on a connection.
            val hello = ProtocolCbor.encode(HelloPayload.serializer(), HelloPayload(device = "watch", features = features))
            it.handleHello(FrameCodec.CompleteMessage(1, Protocol.Type.HELLO.wire, 0, 1, hello))
        }
        // The freezers cold: a warm one would schedule its alarm update 20 min on. A light no
        // dashboard shows is watched too, as a tile.
        val home = WireTestSupport.FakeHa(
            (ownerStates + ("sensor.f3" to state("sensor.f3", "-2.0"))).values.toList() + state("light.elsewhere", "off"),
        )
        val ha: HaWebSocketClient = home.client
        var connected by home::connected
        val haApi = mockk<HaApi>().also {
            coEvery { it.fetchWeatherForecast(any()) } returns AppResult.Ok(65 to 48)
            coEvery { it.fetchCalendarEvents(any(), any()) } returns AppResult.Ok(listOf(event(now + 3600, "Design review")))
        }
        val layout = WatchFaceLayoutPayload(version = 1, header = WatchHeader(timeFormat = "HH:mm"),
            screens = listOf(WatchScreen(screenType = 0, dashboard = dash)))
        val orchestrator = DashboardStateOrchestrator(
            WireTestSupport.silentLogger, haApi, handler, { layout }, { _, _, _, _ -> },
            clockMs = { now * 1000 + scope.testScheduler.currentTime },
        )

        init {
            // The bridge's state cache, subscribed as on a connection; its changes reach the orchestrator.
            home.connect(scope, handler.stateCache, { HaStateCache.watchedEntities(listOf("light.elsewhere"), layout) }) {
                orchestrator.onStateChange(it, ha, watch.transport, scope)
            }
        }

        fun sent(): List<Map<String, Any?>> = watch.sent.filter { it.first == Protocol.Type.DASHBOARD_STATE.wire }
            .map { WireTestSupport.decodeMap(it.second) }

        /** HA reports [entityId] at [newState]. */
        fun change(entityId: String, newState: String) = home.change(entityId, newState)
    }

    @Test
    fun `a refresh sends the state with its events`() = runTest {
        val b = Bridge(this)
        b.orchestrator.refreshNow("connect", b.ha, b.watch.transport, this)
        val m = b.sent().single()
        assertEquals(GoldenVectors.value("status_fingerprint"), m["f"])
        assertEquals(listOf(mapOf("s" to now + 3600, "n" to "Design review")), m["e"])
    }

    @Test
    fun `a burst of changes is one update, and a repeat none`() = runTest {
        val b = Bridge(this)
        b.orchestrator.refreshNow("connect", b.ha, b.watch.transport, this)
        assertEquals(1, b.sent().size)

        // Not a dashboard entity: nothing.
        b.change("light.elsewhere", "on")
        runCurrent()
        advanceUntilIdle()
        assertEquals(1, b.sent().size)

        // Two doors open within the debounce: one update, after it.
        b.change("binary_sensor.doors_0", "on")
        advanceTimeBy(500)
        b.change("binary_sensor.doors_4", "on")
        runCurrent()
        assertEquals(1, b.sent().size)
        advanceUntilIdle()
        assertEquals(2, b.sent().size)
        @Suppress("UNCHECKED_CAST")
        val doors = (b.sent().last()["g"] as List<Map<String, Any?>>)[0]
        assertEquals(0b10011L, doors["b"])

        // The same state again (an attribute changed): not sent.
        b.change("binary_sensor.doors_4", "on")
        runCurrent()
        advanceUntilIdle()
        assertEquals(2, b.sent().size)

        // A refresh is sent even when nothing changed.
        b.orchestrator.refreshNow("heartbeat", b.ha, b.watch.transport, this)
        assertEquals(3, b.sent().size)
    }

    @Test
    fun `nothing goes to a watch without the message, or while HA is away`() = runTest {
        val old = Bridge(this, features = Protocol.Features.CRASH_REPORT)
        old.orchestrator.refreshNow("connect", old.ha, old.watch.transport, this)
        old.change("binary_sensor.doors_0", "on")
        runCurrent()
        advanceUntilIdle()
        assertTrue(old.sent().isEmpty())

        val away = Bridge(this)
        away.connected = false
        away.orchestrator.refreshNow("connect", away.ha, away.watch.transport, this)
        advanceUntilIdle()
        assertTrue(away.sent().isEmpty())
    }

    @Test
    fun `the refresh comes sooner when the next event leaves the watch`() = runTest {
        val b = Bridge(this)
        b.orchestrator.refreshNow("connect", b.ha, b.watch.transport, this)
        // The event starts in an hour: the 15-minute heartbeat comes first.
        assertEquals(now * 1000 + DashboardStateOrchestrator.HEARTBEAT_MS, b.orchestrator.refreshDueAtMs())
        coEvery { b.haApi.fetchCalendarEvents(any(), any()) } returns AppResult.Ok(listOf(event(now - 300, "Standup")))
        b.orchestrator.refreshNow("connect", b.ha, b.watch.transport, this)
        // Began 5 min ago: it leaves the watch in 5 min.
        assertEquals((now + 300) * 1000 + DashboardStateOrchestrator.EVENT_GONE_SLACK_MS, b.orchestrator.refreshDueAtMs())
    }

    @Test
    fun `reads after HA reconnects coalesce to one a minute`() = runTest {
        val b = Bridge(this)
        b.orchestrator.onHaReconnected(b.ha, b.watch.transport, this)
        runCurrent()
        assertEquals(1, b.sent().size)
        coVerify(exactly = 1) { b.haApi.fetchWeatherForecast(any()) }

        // HA drops and returns three times within the minute: no read until the minute is up.
        repeat(3) {
            advanceTimeBy(10_000)
            b.orchestrator.onHaReconnected(b.ha, b.watch.transport, this)
            runCurrent()
        }
        coVerify(exactly = 1) { b.haApi.fetchWeatherForecast(any()) }
        advanceTimeBy(31_000)
        runCurrent()
        coVerify(exactly = 2) { b.haApi.fetchWeatherForecast(any()) }
    }

    @Test
    fun `an HA reconnect sends the state only when it differs from the watch's`() = runTest {
        val b = Bridge(this)
        b.orchestrator.refreshNow("connect", b.ha, b.watch.transport, this)
        assertEquals(1, b.sent().size)

        // The watch link was kept and nothing changed: the watch has this state already.
        b.orchestrator.onHaReconnected(b.ha, b.watch.transport, this)
        runCurrent()
        advanceUntilIdle()
        assertEquals(1, b.sent().size)

        // An event came in while HA was away: the next reconnect's read sends it.
        coEvery { b.haApi.fetchCalendarEvents(any(), any()) } returns AppResult.Ok(listOf(event(now + 7200, "Lunch")))
        advanceTimeBy(DashboardStateOrchestrator.MIN_RECONNECT_READ_MS)
        b.orchestrator.onHaReconnected(b.ha, b.watch.transport, this)
        runCurrent()
        assertEquals(2, b.sent().size)
        assertEquals(listOf(mapOf("s" to now + 7200, "n" to "Lunch")), b.sent().last()["e"])
    }

    @Test
    fun `the watch's messages time the refresh, and a failed one is tried again a minute on`() = runTest {
        val b = Bridge(this)
        b.orchestrator.refreshNow("connect", b.ha, b.watch.transport, this)
        assertEquals(1, b.sent().size)

        // Not due: a watch message sends nothing.
        b.orchestrator.onWatchActivity(b.ha, b.watch.transport, this)
        runCurrent()
        assertEquals(1, b.sent().size)

        // Due (15 min on, by the wall clock), but HA is away: nothing goes out ...
        advanceTimeBy(DashboardStateOrchestrator.HEARTBEAT_MS)
        b.home.drop()
        b.orchestrator.onWatchActivity(b.ha, b.watch.transport, this)
        runCurrent()
        assertEquals(1, b.sent().size)
        // ... nor on the next message within the minute, HA back or not ...
        b.connected = true
        advanceTimeBy(30_000)
        b.orchestrator.onWatchActivity(b.ha, b.watch.transport, this)
        runCurrent()
        assertEquals(1, b.sent().size)
        // ... and on the first one after it, the refresh goes out.
        advanceTimeBy(31_000)
        b.orchestrator.onWatchActivity(b.ha, b.watch.transport, this)
        runCurrent()
        assertEquals(2, b.sent().size)
    }

    @Test
    fun `a refresh that did not go out lets the next update through`() = runTest {
        val b = Bridge(this)
        b.orchestrator.refreshNow("connect", b.ha, b.watch.transport, this)
        assertEquals(1, b.sent().size)
        // A new connection whose refresh fails: the watch (restarted, say) may hold nothing.
        b.home.drop()
        b.orchestrator.refreshNow("connect", b.ha, b.watch.transport, this)
        assertEquals(1, b.sent().size)
        // HA is back. A change that leaves the state as it was sent before still goes out.
        b.connected = true
        b.handler.stateCache.subscribe(b.ha)
        b.change("binary_sensor.doors_1", "on")
        runCurrent()
        advanceUntilIdle()
        assertEquals(2, b.sent().size)
    }

    @Test
    fun `a change made while a refresh is under way is not undone by it`() = runTest {
        val b = Bridge(this)
        // The door opens while the refresh waits for the forecast: the state it sends has it open.
        coEvery { b.haApi.fetchWeatherForecast(any()) } coAnswers {
            b.change("binary_sensor.doors_0", "on")
            AppResult.Ok(65 to 48)
        }
        b.orchestrator.refreshNow("connect", b.ha, b.watch.transport, this)
        @Suppress("UNCHECKED_CAST")
        val doors = (b.sent().last()["g"] as List<Map<String, Any?>>)[0]
        assertEquals(0b11L, doors["b"])
    }

    @Test
    fun `a face's many calendars are read all at once`() = runTest {
        val calendars = (1..16).map { "calendar.c$it" }
        val b = Bridge(this, dash = ownerDash.copy(events = calendars.map { WatchEvent(time = "", title = "calendar:$it") }))
        coEvery { b.haApi.fetchCalendarEvents(any(), any()) } coAnswers {
            delay(1_000)
            AppResult.Ok(listOf(event(now + 3600, "Design review")))
        }
        val from = testScheduler.currentTime
        b.orchestrator.refreshNow("connect", b.ha, b.watch.transport, this)
        // One after another, the 16 took 16 s before the state went out.
        assertEquals(1_000L, testScheduler.currentTime - from)
        assertEquals(1, b.sent().size)
    }

    @Test
    fun `a refresh right after HA connects waits for HA's first answer`() = runTest {
        val b = Bridge(this)
        // HA reconnects; its answer to the new subscription is on its way.
        b.home.drop()
        b.connected = true
        b.home.answerLater = true
        b.handler.stateCache.subscribe(b.ha)
        b.orchestrator.refresh("ha-reconnect", b.ha, b.watch.transport, this)
        runCurrent()
        assertTrue(b.sent().isEmpty())
        b.home.answer()
        runCurrent()
        assertEquals(1, b.sent().size)
    }
}
