package com.w6tech.infinassistant.core.data.bridge

import android.util.Log
import com.w6tech.infinassistant.core.ble.BleTransport
import com.w6tech.infinassistant.core.ble.FrameCodec
import com.w6tech.infinassistant.core.common.AppResult
import com.w6tech.infinassistant.core.common.Logger
import com.w6tech.infinassistant.core.data.BridgeEngine
import com.w6tech.infinassistant.core.data.CalendarEvent
import com.w6tech.infinassistant.core.data.HaApi
import com.w6tech.infinassistant.core.data.bridge.WatchFaceOrchestrator.WatchFaceLayoutResult
import com.w6tech.infinassistant.core.domain.*
import com.w6tech.infinassistant.core.ha.HaEntityRegistryEntry
import com.w6tech.infinassistant.core.ha.HaState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Exports the messages the app writes to the watch for the reference layout
 * (`core-domain/src/test/resources/user-layout-2026-09-24.json`), framed exactly as
 * [BleTransport.send] frames them, so the simulator can replay them through the watch's
 * GATT write path (`tools/sim_dashboard_shot.sh`).
 *
 * Every payload comes from the app's own code: the layout is parsed by [WatchFaceParser],
 * enriched, stripped and encoded by [WatchFaceOrchestrator.handleWatchFaceLayoutCommand]
 * (which also sends the HELLO before it and the tile STATE_UPDATE after it), today's calendar
 * entries come from [CalendarOrchestrator.sendToday], and every message goes through
 * [ProtocolHandler]. Home Assistant is a fake with plausible states: some status glyphs on and
 * some off, thermostats near 71 and 68, freezers near 0 and -2, a cloudy day with a high of 65
 * and a low of 48, and two events later the same day.
 *
 * The clock is fixed at [NOW] so the replayed face is the same on every run: TIME_SYNC carries it,
 * with the phone's UTC offset [TZ_OFFSET] (0 unless `$SIM_DASH_TZ_OFFSET` gives seconds), and the
 * event times are relative to it. The TIME_SYNC payload is built
 * the way [ProtocolHandler.sendTimeSync] builds it, with [NOW] in place of the system clock. The
 * dashboard's lastEnrichedAt is still the real time of the export; it is later than [NOW], and
 * the watch only treats data as stale when lastEnrichedAt is in the past by more than 15 minutes.
 *
 * Output, into `$SIM_DASH_OUT` or `build/sim-dashboard` under this module:
 * - `session.bin`: every GATT write in order, each as a little-endian u16 length and the frame;
 * - `session.txt`: one line per message (type, msgId, payload bytes, chunk count);
 * - `NN-TYPE.cbor`: each message's payload before framing.
 */
class SimDashboardPayloadExportTest {

    private companion object {
        /** The watch's clock in the replay: a fixed instant in the past. */
        val NOW: Instant = Instant.parse("2026-10-12T10:09:00Z")

        /** The phone's UTC offset in seconds that TIME_SYNC carries: `$SIM_DASH_TZ_OFFSET`, default 0. */
        val TZ_OFFSET: Int = System.getenv("SIM_DASH_TZ_OFFSET")?.trim()?.toIntOrNull() ?: 0

        /**
         * The house the export shows (`$SIM_DASH_VARIANT`): unset, some doors open and lights on;
         * `calm`, everything shut and off; `busy`, much open, on and playing, and a freezer warm
         * for 21 minutes (its alarm shows).
         */
        val VARIANT: String = System.getenv("SIM_DASH_VARIANT")?.trim()?.lowercase().orEmpty()

        /** ATT MTU the app requests (BleGattClient.connect) and the simulator reports. */
        const val ATT_MTU = 247

        /** Feature bits the firmware advertises in its HELLO (kSupportedFeatures, ProtocolMessages.h). */
        const val FIRMWARE_FEATURES = Protocol.Features.WATCHFACE_LAYOUT or Protocol.Features.LOGBOOK or
            Protocol.Features.HAPTIC_FEEDBACK or Protocol.Features.TIME_SYNC or Protocol.Features.WATCHFACE_ACK or
            Protocol.Features.CALENDAR or Protocol.Features.HOME_CONTROL or Protocol.Features.PROTOCOL_NEGOTIATION or
            Protocol.Features.FIND_DEVICE or Protocol.Features.NOTIFY_DISMISS_SNOOZE or Protocol.Features.SHORT_KEYS or
            Protocol.Features.CRASH_REPORT or Protocol.Features.DASHBOARD_STATE

        /** helloDiagRev of current firmware: an explicit WATCHFACE_ACK is expected. */
        const val FIRMWARE_HELLO_DIAG_REV = 4

        /** Free heap the watch reports while it is not on the HA face (about 11 KB, see WatchFaceOrchestrator). */
        const val WATCH_FREE_HEAP = 11_264L
    }

    private val fakeLogger = object : Logger {
        override fun d(tag: String, msg: String, tr: Throwable?) {}
        override fun i(tag: String, msg: String, tr: Throwable?) {}
        override fun w(tag: String, msg: String, tr: Throwable?) {}
        override fun e(tag: String, msg: String, tr: Throwable?) {}
    }

    @Before
    fun stubAndroidLog() {
        // WatchFaceParser logs through android.util.Log, which is a stub in this module's unit tests.
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun unstubAndroidLog() {
        unmockkStatic(Log::class)
    }

    // ---- the fake watch ----------------------------------------------------------

    private data class Sent(val type: Int, val flags: Int, val msgId: Int, val payload: ByteArray)

    /** Records every message the app sends and answers the two it waits for, as the firmware does. */
    private class FakeWatch {
        val sent = mutableListOf<Sent>()
        private val waiters = mutableListOf<Pair<(FrameCodec.CompleteMessage) -> Boolean, CompletableDeferred<FrameCodec.CompleteMessage>>>()
        val transport: BleTransport = mockk()

        init {
            every { transport.expect(any()) } answers {
                val predicate = firstArg<(FrameCodec.CompleteMessage) -> Boolean>()
                CompletableDeferred<FrameCodec.CompleteMessage>().also { waiters += predicate to it }
            }
            every { transport.cancelExpect(any()) } answers {
                val d = firstArg<CompletableDeferred<FrameCodec.CompleteMessage>>()
                waiters.removeAll { it.second === d }
                Unit
            }
            coEvery { transport.send(any(), any(), any(), any(), any()) } coAnswers {
                val type = firstArg<Int>()
                val msgId = thirdArg<Int>()
                sent += Sent(type, secondArg(), msgId, arg(3))
                when (type) {
                    Protocol.Type.HELLO.wire -> {
                        val hello = ProtocolCbor.encode(
                            HelloPayload.serializer(),
                            HelloPayload(
                                device = "watch",
                                features = FIRMWARE_FEATURES,
                                freeHeap = WATCH_FREE_HEAP,
                                helloDiagRev = FIRMWARE_HELLO_DIAG_REV,
                            ),
                        )
                        deliver(FrameCodec.CompleteMessage(1, type, 0, 0x4000, hello))
                    }
                    Protocol.Type.WATCHFACE_LAYOUT.wire -> {
                        val ack = ProtocolCbor.encode(WatchFaceAckPayload.serializer(), WatchFaceAckPayload(ok = true, appliedVersion = 2))
                        deliver(FrameCodec.CompleteMessage(1, Protocol.Type.WATCHFACE_ACK.wire, 0, 0x4001, ack))
                    }
                }
                AppResult.Ok(Unit)
            }
        }

        private fun deliver(m: FrameCodec.CompleteMessage) {
            val hit = waiters.firstOrNull { it.first(m) && !it.second.isCompleted } ?: return
            hit.second.complete(m)
        }
    }

    // ---- fixture -----------------------------------------------------------------

    /** Walk up from the working directory to a path that lives outside this module. */
    private fun findUp(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        assertNotNull("$relative not found above ${System.getProperty("user.dir")}", null)
        error("unreachable")
    }

    private fun parsedReference(): WatchFaceLayoutPayload {
        val json = findUp("core-domain/src/test/resources/user-layout-2026-09-24.json").readText()
        return when (val r = WatchFaceParser.parseWatchFaceJson(json)) {
            is ParseResult.Success -> r.payload
            is ParseResult.Error -> throw AssertionError("reference layout did not parse: ${r.message}")
        }
    }

    // ---- Home Assistant, as the fake sees it ---------------------------------------

    private fun state(entityId: String, state: String, attributes: Map<String, Any> = emptyMap()) = HaState(
        entityId = entityId,
        state = state,
        attributes = JsonObject(
            attributes.mapValues { (_, v) -> if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) },
        ),
    )

    /** Every entity the reference layout reads, with a plausible state. */
    private val plausibleStates: List<HaState> = listOf(
        // doors (F S M D D): the side door is open
        state("binary_sensor.lumi_lumi_sensor_magnet_aq2_opening", "off"),
        state("binary_sensor.lumi_lumi_sensor_magnet_aq2_opening_2", "on"),
        state("binary_sensor.master_back_door_opening_4", "off"),
        state("binary_sensor.lumi_lumi_sensor_magnet_aq2_opening_5", "off"),
        state("binary_sensor.lumi_lumi_sensor_magnet_aq2_opening_3", "off"),
        // garage doors: one open
        state("cover.msg100_2c21_garage_door", "open"),
        state("cover.msg100_45c0_garage_door", "closed"),
        // motion: main floor only
        state("binary_sensor.main_floor_motion", "on"),
        state("binary_sensor.downstairs_motion", "off"),
        state("binary_sensor.kid_s_room_motion", "off"),
        // lights: both kitchen lights on, WLED off
        state("light.kitchen_cabinet_lights", "on"),
        state("light.kitchen_main_lights", "on"),
        state("light.wled", "off"),
        // media: one TV playing
        state("media_player.lg_webos_tv_65uh5500_ua", "off"),
        state("media_player.huntertv", "playing"),
        state("media_player.lg_webos_tv_3a7c", "off"),
        // thermostats near 71 and 68 (rounded by the app)
        state("sensor.main_floor_current_temperature", "71.4", mapOf("unit_of_measurement" to "°F")),
        state("sensor.attic_sensorpush_temperature", "68.2", mapOf("unit_of_measurement" to "°F")),
        // freezers near 0 and -2
        state("sensor.ht1_a71b_temperature", "0.3", mapOf("unit_of_measurement" to "°F")),
        state("sensor.ht1_4434_temperature", "-2.4", mapOf("unit_of_measurement" to "°F")),
        state("sensor.sensorpush_chest_freezer_temperature", "-1.2", mapOf("unit_of_measurement" to "°F")),
        // beacons are device trackers: no number, so the app drops the group
        state("device_tracker.burby_beacon", "home"),
        state("device_tracker.jessicas_iphone_2", "home"),
        state("device_tracker.unifi_default_be_be_dd_34_0a_d3", "not_home"),
        // weather: cloudy, 58 now; the forecast below gives the high and low
        state("weather.forecast_home", "cloudy", mapOf("temperature" to 58)),
        // tile entities (screens 2-4), sent in the STATE_UPDATE after the layout
        state("light.dining_room_chandelier", "off"),
        state("light.front_foyer_chandelier", "on"),
        state("switch.sonoff_zbminil2", "off"),
        state("light.garage_lights", "off"),
        state("switch.shelly1minig3_cc8da2432cbc_shelly_sw", "off"),
        state("light.master_bathroom_vanity_lights", "off"),
        state("switch.master_bed_fan", "on"),
        state("light.garage_side_light", "off"),
        state("light.porch_light", "on"),
        state("light.master_bedroom_main", "off"),
    )

    /** The states of [VARIANT]: [plausibleStates] with the status entities and a freezer changed. */
    private val haStates: List<HaState> = when (VARIANT) {
        "calm" -> plausibleStates.map { s ->
            when (s.entityId.substringBefore('.')) {
                "binary_sensor", "light", "media_player" -> s.copy(state = "off")
                "cover" -> s.copy(state = "closed")
                else -> s
            }
        }
        "busy" -> plausibleStates.map { s ->
            when (s.entityId) {
                "binary_sensor.lumi_lumi_sensor_magnet_aq2_opening", "binary_sensor.lumi_lumi_sensor_magnet_aq2_opening_3",
                "binary_sensor.downstairs_motion", "light.wled" -> s.copy(state = "on")
                "cover.msg100_45c0_garage_door" -> s.copy(state = "open")
                "media_player.lg_webos_tv_3a7c" -> s.copy(state = "playing")
                "sensor.ht1_a71b_temperature" -> s.copy(state = "17.4")
                "weather.forecast_home" -> state("weather.forecast_home", "pouring", mapOf("temperature" to 44))
                else -> s
            }
        }
        else -> plausibleStates
    }

    /** The device-reference tiles (L1, L2, M MAIN) resolve to these entities. */
    private val deviceEntities = mapOf(
        "f42859bf90e3b114eb0ef6aee9e24b7c" to "light.garage_side_light",
        "60b33274d53e6eedb585121a1661a1e2" to "light.porch_light",
        "00b8082c70b4d75acf25930128c427b8" to "light.master_bedroom_main",
    )

    private val hhmm: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.ofTotalSeconds(TZ_OFFSET))

    private fun event(minutesFromNow: Long, title: String): CalendarEvent {
        val start = NOW.plusSeconds(minutesFromNow * 60)
        return CalendarEvent(time = hhmm.format(start), title = title, startInstant = start, startEpoch = start.epochSecond)
    }

    /** Two events later the same day, one in each of the dashboard's first two calendars. */
    private val designReview = event(81, "Design review")
    private val dentist = event(291, "Dentist appointment")

    // ---- export ------------------------------------------------------------------

    @Test
    fun `export the app's messages for the reference layout`() {
        val layout = parsedReference()
        val dashCalendars = layout.screens.first { it.screenType == 0 }.dashboard!!.events
            .map { it.title.removePrefix("calendar:") }
        assertEquals("the dashboard reads every configured calendar", 16, dashCalendars.size)

        // HA answers the state cache's subscription with these states, and get_states (the
        // push's enrichment of the layout record) with the same.
        val ha = WireTestSupport.FakeHa(haStates).client
        coEvery { ha.getStates() } returns AppResult.Ok(haStates)
        coEvery { ha.getEntityRegistry() } returns AppResult.Ok(
            deviceEntities.map { (device, entity) -> HaEntityRegistryEntry(entityId = entity, deviceId = device) },
        )
        val haApi = mockk<HaApi>()
        coEvery { haApi.fetchCalendarEvents(any(), any()) } answers {
            when (firstArg<String>()) {
                dashCalendars[0] -> AppResult.Ok(listOf(designReview))
                dashCalendars[1] -> AppResult.Ok(listOf(dentist))
                else -> AppResult.Ok(emptyList())
            }
        }
        coEvery { haApi.fetchCalendarDay(any(), any(), any()) } answers {
            if (firstArg<String>() == dashCalendars[0]) AppResult.Ok(listOf(designReview, dentist)) else AppResult.Ok(emptyList())
        }
        coEvery { haApi.fetchWeatherForecast(any()) } returns AppResult.Ok(Pair(65, 48))

        val watch = FakeWatch()
        val stateManager = StateManager()
        // A live session: BridgeEngine records the connect-time state snapshot this way, so the
        // STATE_UPDATE flags read fresh (data_stale and ha_offline false) as they do on a phone.
        stateManager.recordHaFetch()
        stateManager.setHaOffline(false)
        val protocolHandler = ProtocolHandler(logger = fakeLogger, stateManager = stateManager, logCallback = { _, _, _, _ -> })
        val eventBroker = EventBroker(
            logger = fakeLogger,
            stateManager = stateManager,
            protocolHandler = protocolHandler,
            logCallback = { _, _, _, _ -> },
        )
        val orchestrator = WatchFaceOrchestrator(
            logger = fakeLogger,
            haApi = haApi,
            settingsRepo = mockk(relaxed = true),
            protocolHandler = protocolHandler,
            stateManager = stateManager,
            eventBroker = eventBroker,
            logCallback = { _, _, _, _ -> },
        )
        // The entities the state cache watches, as BridgeEngine has them.
        protocolHandler.stateCache.wanted = {
            HaStateCache.watchedEntities(eventBroker.getTileEntityConfigs().map { it.entityId }, orchestrator.lastWatchFaceLayout)
        }
        val calendar = CalendarOrchestrator(
            logger = fakeLogger,
            haApi = haApi,
            protocolHandler = protocolHandler,
            layoutProvider = { orchestrator.lastWatchFaceLayout ?: layout },
            logCallback = { _, _, _, _ -> },
        )

        var result: WatchFaceLayoutResult? = null
        var recordDash: WatchDashboard? = null
        runTest {
            // HA connects: the state cache subscribes (nothing yet, with no layout stored).
            protocolHandler.stateCache.subscribe(ha)
            // On connect the app sets the watch clock.
            protocolHandler.sendToWatch(
                watch.transport, Protocol.Type.TIME_SYNC, protocolHandler.nextMsgId(),
                TimeSyncPayload(timestamp = NOW.epochSecond, timezoneOffset = TZ_OFFSET, isDst = false),
            )
            // And the CONFIG_PUSH the engine builds (BridgeEngine.configPushPayload) with the sensor
            // reports on, every second: the simulator's watch decodes the app's own bytes, key included,
            // and sends a SENSOR_REPORT when it takes them (the firmware ignores a key it does not know).
            val configSent = protocolHandler.sendConfigPush(
                watch.transport, protocolHandler.nextMsgId(),
                BridgeEngine.configPushPayload(
                    1,
                    BridgeSettings(infinassistant = InfinassistantSettings(enabled = true, sensorReportIntervalSeconds = 1)),
                ),
            )
            assertTrue("no HELLO has been seen, so the CONFIG_PUSH is not withheld", configSent)
            // The layout push: HELLO, WATCHFACE_LAYOUT, then the tile states.
            result = orchestrator.handleWatchFaceLayoutCommand(
                payload = layout,
                ha = ha,
                transport = watch.transport,
                onProgress = null,
                peerFeatures = FIRMWARE_FEATURES,
                peerHelloDiagRev = FIRMWARE_HELLO_DIAG_REV,
            )
            // After a push the app sends today's entries for the calendar app.
            calendar.sendToday(watch.transport, this)
            testScheduler.advanceUntilIdle()
            // And the dashboard's live state (DashboardStateOrchestrator.refresh, as after every push).
            var clockMs = NOW.toEpochMilli()
            val dashboardState = DashboardStateOrchestrator(
                logger = fakeLogger,
                haApi = haApi,
                protocolHandler = protocolHandler,
                layoutProvider = { orchestrator.lastWatchFaceLayout },
                logCallback = { _, _, _, _ -> },
                clockMs = { clockMs },
            )
            if (VARIANT == "busy") {
                // The warm freezer was first seen 21 minutes ago: it alarms now.
                clockMs -= 21 * 60_000L
                dashboardState.refreshNow("connect", ha, watch.transport, this)
                clockMs = NOW.toEpochMilli()
            }
            dashboardState.refreshNow("layout", ha, watch.transport, this)
            // The readings the layout record itself carries (the push's enrichment).
            recordDash = orchestrator.enrichDashboardWithEntityStates(layout, ha).screens.first { it.screenType == 0 }.dashboard
        }
        assertEquals(WatchFaceLayoutResult.Success, result)

        val types = watch.sent.map { it.type }
        assertEquals(
            "message order",
            listOf(
                Protocol.Type.TIME_SYNC.wire,
                Protocol.Type.CONFIG_PUSH.wire,
                Protocol.Type.HELLO.wire,
                Protocol.Type.WATCHFACE_LAYOUT.wire,
                Protocol.Type.STATE_UPDATE.wire,
                Protocol.Type.CALENDAR_UPDATE.wire,
            ) + List(if (VARIANT == "busy") 2 else 1) { Protocol.Type.DASHBOARD_STATE.wire },
            types,
        )

        // What the app keeps phone-side: the enriched layout, with the dashboard's calendar
        // references and every configured sensor (DashboardStateOrchestrator reads them).
        val dash = requireNotNull(orchestrator.lastWatchFaceLayout?.screens?.first { it.screenType == 0 }?.dashboard)
        assertEquals(dashCalendars.map { "calendar:$it" }, dash.events.map { it.title })
        assertEquals(listOf(2, 3, 3), dash.sensorGroups.map { it.sensors.size })
        val record = requireNotNull(recordDash)

        // The live state, as the watch decodes it (DashboardState.cpp).
        val live = WireTestSupport.decodeMap(watch.sent.last { it.type == Protocol.Type.DASHBOARD_STATE.wire }.payload)
        assertEquals("the watch's status fingerprint of this layout", GoldenVectors.value("status_fingerprint"), live["f"])
        assertEquals(NOW.epochSecond, live["t"])
        @Suppress("UNCHECKED_CAST")
        val groups = live["g"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val sensorGroups = live["s"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val liveEvents = (live["e"] as List<Map<String, Any?>>).map { it["n"] }
        assertEquals(listOf("Design review", "Dentist appointment"), liveEvents)
        if (VARIANT.isEmpty()) {
            assertEquals(
                listOf(listOf(false, true, false, false, false), listOf(true, false), listOf(true, false, false),
                    listOf(true, true, false), listOf(false, true, false, false)),
                dash.statusGroups.map { g -> g.glyphs.map { it.active } },
            )
            assertEquals(listOf(listOf(71, 68), listOf(0, -2, -1), emptyList()), record.sensorGroups.map { g -> g.sensors.map { it.value } })
            assertEquals("cloudy", dash.weather?.condition)
            assertEquals(65, dash.weather?.hi)
            assertEquals(48, dash.weather?.lo)
            // A bit per glyph, as the record's flags; the types doors, garage, motion, lights, media.
            assertEquals(listOf(1L to 0b10L, 2L to 0b1L, 3L to 0b1L, 4L to 0b11L, 5L to 0b10L), groups.map { it["t"] to it["b"] })
            // The beacons are device trackers: 1 at home and 0 away, where the record had no number.
            assertEquals(listOf(listOf(71L, 68L), listOf(0L, -2L, -1L), listOf(1L, 1L, 0L)), sensorGroups.map { it["v"] })
            assertEquals(mapOf("c" to 4L, "t" to 58L, "h" to 65L, "l" to 48L), live["w"])
        }
        if (VARIANT == "busy") {
            assertEquals("the warm freezer alarms", 1L, sensorGroups[1]["a"])
        }

        val layoutBytes = watch.sent.single { it.type == Protocol.Type.WATCHFACE_LAYOUT.wire }.payload
        assertTrue("layout fits the watch's reassembly buffer", layoutBytes.size <= FirmwareLimits.MAX_REASSEMBLY_SIZE)
        val stateUpdate = ProtocolCbor.decode(
            StateUpdatePayload.serializer(),
            watch.sent.single { it.type == Protocol.Type.STATE_UPDATE.wire }.payload,
        )
        assertTrue("a live session does not flag its data stale", !stateUpdate.data_stale && !stateUpdate.ha_offline)

        writeSession(watch.sent)
    }

    private fun typeName(wire: Int): String =
        Protocol.Type.entries.firstOrNull { it.wire == wire }?.name ?: "TYPE_0x%02X".format(wire)

    private fun writeSession(sent: List<Sent>) {
        val outDir = System.getenv("SIM_DASH_OUT")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.dir"), "build/sim-dashboard")
        outDir.mkdirs()
        outDir.listFiles { f -> f.name.endsWith(".cbor") }?.forEach { it.delete() }

        val session = ByteArrayOutputStream()
        val manifest = StringBuilder()
        manifest.append("# GATT writes the app makes for user-layout-2026-09-24.json; clock ")
            .append(NOW).append(", ATT MTU ").append(ATT_MTU).append('\n')
        sent.forEachIndexed { i, m ->
            val frames = FrameCodec.chunk(m.type, m.flags, m.msgId, m.payload, maxFrameBytes = ATT_MTU - 3)
            for (f in frames) {
                session.write(f.size and 0xFF)
                session.write((f.size shr 8) and 0xFF)
                session.write(f)
            }
            val name = typeName(m.type)
            File(outDir, "%02d-%s.cbor".format(i + 1, name)).writeBytes(m.payload)
            manifest.append("%02d %s msgId=%d bytes=%d chunks=%d\n".format(i + 1, name, m.msgId, m.payload.size, frames.size))
        }
        File(outDir, "session.bin").writeBytes(session.toByteArray())
        File(outDir, "session.txt").writeText(manifest.toString())
        println("SIMDASH export dir=${outDir.absolutePath} messages=${sent.size} bytes=${session.size()}")
        print(manifest)
    }
}
