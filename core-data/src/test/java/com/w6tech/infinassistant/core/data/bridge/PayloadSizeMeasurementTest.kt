package com.w6tech.infinassistant.core.data.bridge

import android.util.Log
import com.w6tech.infinassistant.core.ble.BleTransport
import com.w6tech.infinassistant.core.ble.FrameCodec
import com.w6tech.infinassistant.core.common.AppResult
import com.w6tech.infinassistant.core.common.Logger
import com.w6tech.infinassistant.core.data.CalendarEvent
import com.w6tech.infinassistant.core.data.HaApi
import com.w6tech.infinassistant.core.data.bridge.WatchFaceOrchestrator.WatchFaceLayoutResult
import com.w6tech.infinassistant.core.domain.*
import com.w6tech.infinassistant.core.ha.HaEntityRegistryEntry
import com.w6tech.infinassistant.core.ha.HaState
import com.w6tech.infinassistant.core.ha.HaWebSocketClient
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * REQ009-FR-C6 / FR-C4 / FR-C7: payload size measurement on the real send path.
 *
 * Every size here is the length of the bytes `WatchFaceOrchestrator.handleWatchFaceLayoutCommand`
 * hands the transport: the orchestrator's post-enrichment `blePayload`, encoded by
 * `ProtocolCbor.encodeWatchFaceLayout` once the watch's refreshed HELLO has been read. The
 * editor's `cborBytes` figure measures the parsed payload instead, so the parsed stage is
 * printed alongside as its own row and never stands in for the wire size.
 *
 * The layout is the reference layout, `core-domain/src/test/resources/user-layout-2026-09-24.json`
 * (the FR-E7 test layout, recorded in the evidence log under 3a.1, `e7-layout:`). It is read
 * from that file by walking up from the working directory, and checked field-for-field against
 * the record before any size is taken.
 *
 * Two HA scenarios are measured. `populated`: HA answers every lookup, every glyph is active, and
 * the two calendars return two events each. That is the larger payload and the one the gate
 * reads. `unreachable`: HA returns nothing, so nothing is enriched. The test prints one table to
 * stdout (`MEASURE ...` lines) which the evidence log quotes; its assertions are invariants, so a
 * change to the layout types or the encoders surfaces as a new table rather than a red test.
 *
 * The paper margin inputs are from evidence `3b.3 revision 2`; this test only does the arithmetic.
 */
@OptIn(ExperimentalSerializationApi::class)
class PayloadSizeMeasurementTest {

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

    private data class Sent(val type: Int, val msgId: Int, val payload: ByteArray)

    /** A transport that plays the watch: answers the refresh HELLO and acks the layout. */
    private class FakeWatch(private val helloFeatures: Int) {
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
                sent += Sent(type, msgId, arg(3))
                when (type) {
                    Protocol.Type.HELLO.wire -> {
                        val hello = ProtocolCbor.encode(
                            HelloPayload.serializer(),
                            HelloPayload(device = "watch", features = helloFeatures, freeHeap = 20_000L, helloDiagRev = 2),
                        )
                        deliver(FrameCodec.CompleteMessage(1, type, 0, 9000, hello))
                    }
                    Protocol.Type.WATCHFACE_LAYOUT.wire -> {
                        val ack = ProtocolCbor.encode(WatchFaceAckPayload.serializer(), WatchFaceAckPayload(ok = true, appliedVersion = 1))
                        deliver(FrameCodec.CompleteMessage(1, Protocol.Type.WATCHFACE_ACK.wire, 0, msgId, ack))
                    }
                }
                AppResult.Ok(Unit)
            }
        }

        private fun deliver(m: FrameCodec.CompleteMessage) {
            val hit = waiters.firstOrNull { it.first(m) && !it.second.isCompleted } ?: return
            hit.second.complete(m)
        }

        val layoutFrames get() = sent.filter { it.type == Protocol.Type.WATCHFACE_LAYOUT.wire }
    }

    // CALENDAR, as every firmware advertises (kSupportedFeatures): without it the orchestrator
    // skips the calendar enrichment, and the dashboard's "calendar:" references, one per calendar,
    // went on the wire in place of the two events the populated scenario is about.
    private val baseFeatures = Protocol.Features.WATCHFACE_LAYOUT or Protocol.Features.WATCHFACE_ACK or
        Protocol.Features.NOTIFY_DISMISS_SNOOZE or Protocol.Features.CALENDAR
    private val shortFeatures = baseFeatures or Protocol.Features.SHORT_KEYS

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

    // ---- send-path harness -------------------------------------------------------

    private fun stateOf(entityId: String, state: String, attributes: Map<String, Double> = emptyMap()) = HaState(
        entityId = entityId,
        state = state,
        attributes = JsonObject(attributes.mapValues { JsonPrimitive(it.value) }),
    )

    /** HA answers every lookup the reference layout makes. Every glyph is active (an upper bound). */
    private fun populatedStates(p: WatchFaceLayoutPayload): List<HaState> {
        val states = mutableListOf<HaState>()
        for (screen in p.screens) {
            val dash = screen.dashboard ?: continue
            for (g in dash.statusGroups) for (glyph in g.glyphs) states += stateOf(glyph.entityId.orEmpty(), "on")
            for (g in dash.sensorGroups) for (s in g.sensors) {
                states += when (g.type) {
                    "thermostats" -> stateOf(s.entityId, "71.6")
                    "freezers" -> stateOf(s.entityId, "-17.8")
                    else -> stateOf(s.entityId, "home") // a tracker: no number, so the sensor is dropped
                }
            }
            dash.weather?.let { states += stateOf(it.entityId, "partlycloudy", mapOf("temperature" to 72.0)) }
        }
        return states
    }

    private fun deviceRefs(p: WatchFaceLayoutPayload): List<String> =
        p.screens.flatMap { it.tiles }.map { it.entityId }.filter { it.startsWith("device:") }.map { it.removePrefix("device:") }

    /**
     * Drives the real `handleWatchFaceLayoutCommand` and returns the bytes of the one WATCHFACE_LAYOUT
     * frame the transport received, or fails if none was sent (a preflight refusal).
     */
    private fun sendThroughOrchestrator(
        payload: WatchFaceLayoutPayload,
        shortKeys: Boolean,
        states: List<HaState>?,
        calendarEvents: List<CalendarEvent>?,
        extraFeatures: Int = 0,
    ): ByteArray {
        val features = (if (shortKeys) shortFeatures else baseFeatures) or extraFeatures
        val watch = FakeWatch(features)
        val ha = mockk<HaWebSocketClient>()
        every { ha.isConnected() } returns (states != null)
        coEvery { ha.getStates() } returns
            (if (states != null) AppResult.Ok(states) else AppResult.Err(IllegalStateException("HA unreachable")))
        coEvery { ha.getEntityRegistry() } returns AppResult.Ok(
            deviceRefs(payload).mapIndexed { i, id -> HaEntityRegistryEntry(entityId = "light.resolved_device_$i", deviceId = id) },
        )
        val haApi = mockk<HaApi>()
        coEvery { haApi.fetchCalendarEvents(any(), any()) } returns
            (if (calendarEvents != null) AppResult.Ok(calendarEvents) else AppResult.Err(IllegalStateException("HA unreachable")))
        coEvery { haApi.fetchWeatherForecast(any()) } returns AppResult.Ok(Pair(81, 63))

        val stateManager = StateManager()
        val protocolHandler = ProtocolHandler(logger = fakeLogger, stateManager = stateManager, logCallback = { _, _, _, _ -> })
        val orchestrator = WatchFaceOrchestrator(
            logger = fakeLogger,
            haApi = haApi,
            settingsRepo = mockk(relaxed = true),
            protocolHandler = protocolHandler,
            stateManager = stateManager,
            eventBroker = EventBroker(
                logger = fakeLogger,
                stateManager = stateManager,
                protocolHandler = protocolHandler,
                logCallback = { _, _, _, _ -> },
            ),
            logCallback = { _, _, _, _ -> },
            // One stamp for both sends: measure() compares the long and short bytes field by field,
            // and a second boundary between the two sends changed lastEnrichedAt.
            clockMs = { 1_791_640_000_000L },
        )
        var result: WatchFaceLayoutResult? = null
        runTest {
            result = orchestrator.handleWatchFaceLayoutCommand(
                payload = payload,
                ha = ha,
                transport = watch.transport,
                onProgress = null,
                peerFeatures = features,
                peerHelloDiagRev = 2,
            )
        }
        assertEquals("push must succeed for its bytes to be the sent bytes", WatchFaceLayoutResult.Success, result)
        assertEquals("exactly one layout frame", 1, watch.layoutFrames.size)
        return watch.layoutFrames.single().payload
    }

    private val calendarEvents = listOf(
        CalendarEvent(time = "09:30", title = "Team standup with product", startEpoch = 1_790_000_000L),
        CalendarEvent(time = "13:00", title = "Dentist appointment", startEpoch = 1_790_020_000L),
    )

    // ---- measurements ------------------------------------------------------------

    private class Sizes(val longSent: ByteArray, val shortSent: ByteArray, val blePayload: WatchFaceLayoutPayload)

    private fun measure(payload: WatchFaceLayoutPayload, states: List<HaState>?, events: List<CalendarEvent>?): Sizes {
        val longSent = sendThroughOrchestrator(payload, shortKeys = false, states = states, calendarEvents = events)
        val shortSent = sendThroughOrchestrator(payload, shortKeys = true, states = states, calendarEvents = events)
        // The wire payload, recovered from the long bytes (an absent key decodes to its default).
        val ble = ProtocolCbor.decode(WatchFaceLayoutPayload.serializer(), longSent)
        // Self-check: the recovered payload re-encodes to exactly what was sent, in both spellings.
        assertArrayEquals(longSent, ProtocolCbor.encodeWatchFaceLayout(ble, shortKeys = false).bytes)
        assertArrayEquals(shortSent, ProtocolCbor.encodeWatchFaceLayout(ble, shortKeys = true).bytes)
        return Sizes(longSent, shortSent, ble)
    }

    /** `Cbor` with defaults written: the mode the old comment in ProtocolCbor claimed the shared encoder used. */
    private val cborWithDefaults = Cbor {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun shortOf(longBytes: ByteArray): ByteArray? = WatchFaceShortKeys.toShortKeys(longBytes)

    /** The four cells, plus the shared encoder as it actually behaves. */
    private fun grid(stage: String, p: WatchFaceLayoutPayload): Map<String, Int> {
        val serializer = WatchFaceLayoutPayload.serializer()
        val withDefaultsLong = cborWithDefaults.encodeToByteArray(serializer, p)
        val watchLong = ProtocolCbor.encodeWatchFaceLayout(p, shortKeys = false).bytes
        val watchShort = ProtocolCbor.encodeWatchFaceLayout(p, shortKeys = true).bytes
        val sharedLong = ProtocolCbor.encode(serializer, p)
        val cells = linkedMapOf(
            "shared+defaults/long" to withDefaultsLong.size,
            "shared+defaults/short" to (shortOf(withDefaultsLong)?.size ?: -1),
            "watch-face(no defaults)/long" to watchLong.size,
            "watch-face(no defaults)/short" to watchShort.size,
            "shared-actual/long" to sharedLong.size,
            "shared-actual/short" to (shortOf(sharedLong)?.size ?: -1),
        )
        for ((cell, n) in cells) println("MEASURE grid stage=$stage cell=$cell bytes=$n")
        return cells
    }

    /** Layout populated at the firmware caps (REQ009-FR-C6, `P_worst`): every string at its buffer length. */
    private fun worstLayout(screens: Int = 4): WatchFaceLayoutPayload {
        fun s(n: Int) = "w".repeat(n)
        val glyph = WatchGlyph(symbol = "W", entityId = "", active = true)
        val sensor = WatchSensor(entityId = "", displayType = "", value = 100)
        val dashboard = WatchDashboard(
            statusGroups = List(5) { WatchStatusGroup(type = s(15), glyphs = List(10) { glyph }) },
            sensorGroups = List(3) { WatchSensorGroup(type = s(15), sensors = List(7) { sensor }) },
            weather = WatchWeather(entityId = "weather.${s(56)}", condition = s(15), temp = 100, hi = 100, lo = -100),
            events = List(2) { WatchEvent(time = s(7), title = s(31), startEpoch = 1_790_000_000L) },
            notifications = List(2) { WatchNotification(source = s(15), text = s(31)) },
            lastEnrichedAt = 1_790_000_000L,
            configurableValueLabel = s(11),
            configurableValueValue = s(19),
            inlineMetric0Label = s(2),
            inlineMetric0Value = s(3),
            inlineMetric1Label = s(2),
            inlineMetric1Value = s(3),
        )
        val tile = WatchTile(
            label = s(19), entityId = "light.${s(57)}", color = "#FFFFFF", colorEnabled = false,
            icon = s(15), displayMode = 1, confirm = true,
        )
        return WatchFaceLayoutPayload(
            version = 2,
            theme = WatchTheme(background = "#FFFFFF", accent = "#FFFFFF"),
            header = WatchHeader(
                timeFormat = s(15), timeAlign = s(15),
                presenceIcons = List(2) { WatchPresenceIcon(type = s(15), color = "#FFFFFF", entity = "device_tracker.${s(48)}") },
            ),
            screens = listOf(WatchScreen(screenType = 0, tileLayout = 6, dashboard = dashboard)) +
                List(screens - 1) { WatchScreen(screenType = 1, tileLayout = 6, tiles = List(4) { tile }) },
            statusBar = WatchStatusBar(battery = true, steps = true, heartRate = true, notifications = true, bluetooth = true),
            defaultActiveColor = "#112233",
            defaultInactiveColor = "#445566",
        )
    }

    // ---- paper margin ------------------------------------------------------------

    /** The sizes 5c.1 measured on the send path before the R1 rebalance (evidence 5c.1, 2026-09-29T18:44:46Z). */
    private companion object {
        const val PRE_R1_P_SHORT = 1538
        const val PRE_R1_P_LONG = 2415
        const val PRE_R1_P_WORST = 3213
    }

    private object Inputs { // evidence 3b.3 revision 2, "Changed inputs" and the carried-over list
        const val F0 = 3728
        const val L = 2584
        const val V = 496
        const val N = 20
        const val S = 760
        const val T = 152
        const val H = 3584
        const val O_BUF_BOUND = 15
    }

    private fun margin(p: Int): Int = (Inputs.F0 + Inputs.L - Inputs.V - Inputs.S - Inputs.T - Inputs.N) - (p + Inputs.H)

    private fun marginLine(name: String, pName: String, p: Int): String =
        "MEASURE margin $name = (F0 ${Inputs.F0} + L ${Inputs.L} - V ${Inputs.V} - S ${Inputs.S} - T ${Inputs.T} - N ${Inputs.N})" +
            " - ($pName $p + H ${Inputs.H}) = ${margin(p)}"

    // ---- tests -------------------------------------------------------------------

    @Test
    fun `the fixture matches the recorded E7 layout field for field`() {
        val p = parsedReference()
        assertEquals(4, p.screens.size)
        assertEquals(listOf(0, 1, 1, 1), p.screens.map { it.screenType })
        assertEquals("screens 1-3 use tileLayout 4", listOf(4, 4, 4), p.screens.drop(1).map { it.tileLayout })
        assertEquals("screens 1-3 hold 4 tiles each", listOf(4, 4, 4), p.screens.drop(1).map { it.tiles.size })
        val dash = requireNotNull(p.screens[0].dashboard)
        assertEquals("status group glyph counts", listOf(5, 2, 3, 3, 4), dash.statusGroups.map { it.glyphs.size })
        assertEquals("sensor group sizes", listOf(2, 3, 3), dash.sensorGroups.map { it.sensors.size })
        assertEquals("weather.forecast_home", dash.weather?.entityId)
        assertEquals("#FFD700", p.defaultActiveColor)
        assertEquals("#800080", p.defaultInactiveColor)
    }

    @Test
    fun `the shared encoder omits defaults, so the old comment claim is wrong`() {
        val tile = WatchTile(label = "Lamp", color = "#FFFFFF") // colorEnabled holds its default (true)
        val serializer = WatchTile.serializer()
        val shared = ProtocolCbor.encode(serializer, tile)
        assertArrayEquals(ProtocolCbor.encodeWatchFace(serializer, tile), shared)
        val withDefaults = cborWithDefaults.encodeToByteArray(serializer, tile)
        assertTrue("defaults-included is larger", withDefaults.size > shared.size)
        assertTrue(String(withDefaults, Charsets.ISO_8859_1).contains("colorEnabled"))
        assertTrue(!String(shared, Charsets.ISO_8859_1).contains("colorEnabled"))
        // Cbor {} starts from Cbor.Default, whose CborConfiguration has encodeDefaults = false
        // (kotlinx-serialization-cbor 1.7.3, `javap -c 'Cbor$Default'`: first constructor argument iconst_0).
    }

    /** Number of indefinite-length maps and arrays in [bytes]. */
    private fun countIndefiniteContainers(bytes: ByteArray): Int {
        var p = 0
        var count = 0
        fun byte() = bytes[p++].toInt() and 0xFF
        fun argument(info: Int): Long {
            if (info < 24) return info.toLong()
            val n = when (info) { 24 -> 1; 25 -> 2; 26 -> 4; 27 -> 8; else -> error("bad additional info $info") }
            var v = 0L
            repeat(n) { v = (v shl 8) or byte().toLong() }
            return v
        }
        fun read() {
            val ib = byte()
            val info = ib and 0x1F
            val major = ib ushr 5
            when (major) {
                0, 1 -> argument(info)
                3 -> { val n = argument(info).toInt(); p += n }
                4, 5 -> {
                    val per = if (major == 5) 2 else 1
                    if (info == 31) {
                        count++
                        while (bytes[p].toInt() and 0xFF != 0xFF) repeat(per) { read() }
                        p++
                    } else repeat(argument(info).toInt() * per) { read() }
                }
                7 -> Unit
                else -> error("unsupported major type $major")
            }
        }
        read()
        assertEquals("all bytes consumed", bytes.size, p)
        return count
    }

    @Test
    fun `payload sizes on the send path, four-cell grid and paper margins`() {
        println("MEASURE start utc=${Instant.now()}")
        val parsed = parsedReference()

        // Editor stage: what the parsed payload measures, before the orchestrator's enrichment and strip.
        val editorGrid = grid("parsed(editor)", parsed)

        // Wire stage, HA populated.
        val populated = measure(parsed, populatedStates(parsed), calendarEvents)
        val wireGrid = grid("wire-populated", populated.blePayload)
        val pShort = populated.shortSent.size
        val pLong = populated.longSent.size
        assertEquals(wireGrid.getValue("watch-face(no defaults)/short"), pShort)
        assertEquals(wireGrid.getValue("watch-face(no defaults)/long"), pLong)
        assertTrue(pShort < pLong)
        // Both encoders omit defaults, so the shared encoder's long cell equals the watch-face long cell.
        assertEquals(pLong, wireGrid.getValue("shared-actual/long"))
        assertTrue(wireGrid.getValue("shared+defaults/long") >= pLong)

        // Wire stage, HA unreachable (nothing enriched).
        val bare = measure(parsed, null, null)
        println("MEASURE wire-unreachable short=${bare.shortSent.size} long=${bare.longSent.size}")
        grid("wire-unreachable", bare.blePayload)

        // P_worst: 4 screens x 4 tiles, the caps of a watch without the six-screen bit (the six-screen worst case is its own test below), through the same path (HA unreachable: nothing to enrich).
        val worst = worstLayout()
        val worstSent = measure(worst, null, null)
        val pWorst = worstSent.shortSent.size
        val pWorstLong = worstSent.longSent.size
        grid("worst", worstSent.blePayload)
        assertEquals(4, worstSent.blePayload.screens.size)
        assertEquals(listOf(0, 4, 4, 4), worstSent.blePayload.screens.map { it.tiles.size })
        assertTrue(pWorst > pShort)
        assertTrue("worst must fit the reassembly cap", pWorstLong <= FirmwareLimits.MAX_REASSEMBLY_SIZE)

        // The reference layout is the E7 layout, so P_E7 = P_short and P_E7_long = P_long (BRD Terms).
        val pE7 = pShort
        val pE7Long = pLong
        println("MEASURE sizes P_short=$pShort P_E7=$pE7 P_long=$pLong P_E7_long=$pE7Long P_worst=$pWorst P_worst_long=$pWorstLong")
        println("MEASURE sizes-unreachable P_short=${bare.shortSent.size} P_long=${bare.longSent.size}")
        println("MEASURE editor-stage cborBytes(long, no defaults)=${editorGrid.getValue("watch-face(no defaults)/long")}")

        // Lever accounting against the sizes 5c.1 measured before the R1 rebalance (evidence 6.1). Each
        // indefinite container of the long bytes costs the short path one byte less as a definite header
        // (all counts are under 24), so the short size without R1b is the short size plus that count.
        val containers = countIndefiniteContainers(populated.longSent)
        val pShortWithoutR1b = pShort + containers
        println("MEASURE lever pre-R1 P_short=$PRE_R1_P_SHORT P_long=$PRE_R1_P_LONG P_worst=$PRE_R1_P_WORST")
        println("MEASURE lever R1a-only P_short=$pShortWithoutR1b saved=${PRE_R1_P_SHORT - pShortWithoutR1b}")
        println("MEASURE lever R1b saved=$containers containers=$containers (short path only)")
        println("MEASURE lever R1a+R1b P_short=$pShort saved=${PRE_R1_P_SHORT - pShort}")
        println("MEASURE lever long P_long=$pLong saved=${PRE_R1_P_LONG - pLong} P_worst=$pWorst saved=${PRE_R1_P_WORST - pWorst}")
        assertTrue("R1 must not make the short payload larger than 5c.1 measured", pShort < PRE_R1_P_SHORT)
        assertTrue("R1 must not make the long payload larger than 5c.1 measured", pLong <= PRE_R1_P_LONG)

        // Paper margin inputs, transcribed: the arithmetic must reproduce the log's own figures.
        val placeholder = 1571
        assertEquals("M at P_ph, evidence 3b.3 rev 2", -271, margin(placeholder))
        assertEquals("P0, evidence 3b.3 rev 2", 1300, Inputs.F0 + Inputs.L - Inputs.V - Inputs.S - Inputs.T - Inputs.N - Inputs.H)

        val pGate = maxOf(pShort, pE7)
        val pGateLong = maxOf(pLong, pE7Long)
        println(marginLine("M", "P", pGate))
        println(marginLine("M_long", "P_long", pGateLong))
        println(marginLine("M_worst", "P_worst", pWorst))
        println("MEASURE margin inputs O_buf<=${Inputs.O_BUF_BOUND} (charged in G, not in M)")
        println("MEASURE end utc=${Instant.now()}")
    }

    @Test
    fun `the worst layout at six screens, sent to a six-screen watch, fits the reassembly cap`() {
        // FR-7: the dashboard plus 5 full tile screens, the largest record firmware 2.4.100 holds.
        val sent = sendThroughOrchestrator(
            worstLayout(screens = 6), shortKeys = true, states = null, calendarEvents = null,
            extraFeatures = Protocol.Features.SIX_SCREENS,
        )
        val sentLong = sendThroughOrchestrator(
            worstLayout(screens = 6), shortKeys = false, states = null, calendarEvents = null,
            extraFeatures = Protocol.Features.SIX_SCREENS,
        )
        val ble = ProtocolCbor.decode(WatchFaceLayoutPayload.serializer(), sentLong)
        println("MEASURE worst-6 screens=${ble.screens.size} short=${sent.size} long=${sentLong.size}")
        assertEquals(6, ble.screens.size)
        assertEquals(listOf(0, 4, 4, 4, 4, 4), ble.screens.map { it.tiles.size })
        assertTrue("worst at six screens must fit the reassembly cap", sentLong.size <= FirmwareLimits.MAX_REASSEMBLY_SIZE)
    }

    @Test
    fun `the app render caps equal the firmware caps (FR-C7)`() {
        // FirmwareLimits does not carry the two caps, so the firmware header is read directly. The
        // header is GPL firmware source and is not vendored: in the bridge repo this test is skipped.
        val headerFile = MonorepoSources.find("firmware/infinitime_overlay/src/components/ble/HomeAssistantService.h")
        assumeTrue("no firmware tree above the working directory", headerFile != null)
        val header = headerFile!!.readText()
        fun cap(name: String): Int {
            val m = Regex("""static\s+constexpr\s+uint8_t\s+$name\s*=\s*(\d+)\s*;""").find(header)
            assertNotNull("$name not found in HomeAssistantService.h", m)
            return m!!.groupValues[1].toInt()
        }
        val screens = cap("kMaxScreens")
        val tiles = cap("kMaxTilesPerScreen")
        println("MEASURE fr-c7 source=HomeAssistantService.h kMaxScreens=$screens kMaxTilesPerScreen=$tiles " +
            "app MAX_FW_SCREENS=${WatchFaceOrchestrator.MAX_FW_SCREENS} (LEGACY_FW_SCREENS=${WatchFaceOrchestrator.LEGACY_FW_SCREENS} without the six-screen bit) MAX_FW_TILES_PER_SCREEN=${WatchFaceOrchestrator.MAX_FW_TILES_PER_SCREEN}")
        assertEquals(screens, WatchFaceOrchestrator.MAX_FW_SCREENS)
        assertEquals(tiles, WatchFaceOrchestrator.MAX_FW_TILES_PER_SCREEN)
    }
}
