package com.w6tech.infinassistant.core.data.bridge

import com.w6tech.infinassistant.core.ble.BleTransport
import com.w6tech.infinassistant.core.ble.FrameCodec
import com.w6tech.infinassistant.core.common.AppResult
import com.w6tech.infinassistant.core.common.Logger
import com.w6tech.infinassistant.core.data.bridge.WatchFaceOrchestrator.WatchFaceLayoutResult
import com.w6tech.infinassistant.core.domain.*
import com.w6tech.infinassistant.core.ha.HaWebSocketClient
import com.w6tech.infinassistant.core.data.HaApi
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * REQ009-FR-C1 app-to-host short-key parity (plan 5d.1; the 4a.1 branch is `generated`, so parity
 * of the tables is structural and this test pins the bytes).
 *
 * A fixed layout that populates every map level of the registry is sent through the single send
 * entry point, `WatchFaceOrchestrator.handleWatchFaceLayoutCommand`, against a fake watch, once with
 * feature bit 11 set and once with it clear. The frame bytes the transport receives must equal the
 * committed `tools/fuzz/seeds/layout_document/app_short_keys.cbor` and `app_long_keys.cbor`. The host
 * test `tools/fuzz/short_key_registry_test.cpp` decodes both files through `DecodeLayoutDocument` and
 * requires byte-identical full-struct targets, and both files seed the `layout_document` fuzz corpus.
 *
 * The default run only reads the two files, from the copies in the module's test resources
 * (`fuzz-seeds/layout_document/`; `VendoredFixturesTest` compares them with the monorepo seeds). To rewrite
 * both the seeds and the copies, set the environment variable
 * `REQ009_REGEN_SEEDS=1` for the Gradle run (the forked test JVM inherits it); Gradle does not treat an
 * environment variable as a task input, so also clean the task:
 *
 *     $env:REQ009_REGEN_SEEDS = '1'
 *     .\gradlew.bat :core-data:cleanTestDebugUnitTest :core-data:testDebugUnitTest --tests '*ShortKeyFixtureTest*'
 */
class ShortKeyFixtureTest {

    private val fakeLogger = object : Logger {
        override fun d(tag: String, msg: String, tr: Throwable?) {}
        override fun i(tag: String, msg: String, tr: Throwable?) {}
        override fun w(tag: String, msg: String, tr: Throwable?) {}
        override fun e(tag: String, msg: String, tr: Throwable?) {}
    }

    private class Sent(val type: Int, val msgId: Int, val payload: ByteArray)

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

    private val baseFeatures = Protocol.Features.WATCHFACE_LAYOUT or Protocol.Features.WATCHFACE_ACK or Protocol.Features.NOTIFY_DISMISS_SNOOZE
    private val shortFeatures = baseFeatures or Protocol.Features.SHORT_KEYS

    /**
     * The fixed layout. Every map level of the registry is populated, including the fields the send
     * path strips from the wire copy (`WatchFaceWire.stripNonRendered`), so the test can show they are
     * dropped. Each of the four non-zero defaults (`WatchFaceDefaultsGuardTest.WIRE_REACHABLE`) is given
     * a non-default value; three of them go on the wire and `screen.tileLayout` is stripped (see
     * [ABSENT_ON_WIRE]). Strings stay inside the firmware buffers, and nothing here needs HA to enrich it.
     */
    private fun fixtureLayout() = WatchFaceLayoutPayload(
        version = 2,
        theme = WatchTheme(background = "#1E1E1E", accent = "#18A1FF"),
        header = WatchHeader(
            timeFormat = "HH:mm",
            timeAlign = "center",
            presenceIcons = listOf(
                WatchPresenceIcon(type = "car", color = "#00FF00", entity = "device_tracker.car"),
                WatchPresenceIcon(type = "person_male", color = "#FF00FF", entity = "device_tracker.phone"),
            ),
        ),
        screens = listOf(
            WatchScreen(
                screenType = 0,
                dashboard = WatchDashboard(
                    statusGroups = listOf(
                        WatchStatusGroup(type = "doors", glyphs = listOf(
                            WatchGlyph(symbol = "F", entityId = "", active = true),
                            WatchGlyph(symbol = "B", entityId = "", active = false),
                        )),
                        WatchStatusGroup(type = "lights", glyphs = listOf(WatchGlyph(symbol = "K", entityId = "", active = true))),
                    ),
                    sensorGroups = listOf(
                        WatchSensorGroup(type = "thermostats", sensors = listOf(WatchSensor(value = 71), WatchSensor(value = 68))),
                        WatchSensorGroup(type = "freezers", sensors = listOf(WatchSensor(value = -18))),
                    ),
                    weather = WatchWeather(entityId = "weather.forecast_home", condition = "partlycloudy", temp = 72, hi = 81, lo = 63),
                    events = listOf(
                        WatchEvent(time = "09:30", title = "Standup", startEpoch = 1_790_000_000L),
                        WatchEvent(time = "13:00", title = "Dentist", startEpoch = 1_790_020_000L),
                    ),
                    notifications = listOf(
                        WatchNotification(source = "mail", text = "Build finished"),
                        WatchNotification(source = "chat", text = "Lunch?"),
                    ),
                    lastEnrichedAt = 1_790_000_100L,
                    configurableValueLabel = "Power",
                    configurableValueValue = "1.2 kW",
                    inlineMetric0Label = "Hu",
                    inlineMetric0Value = "45",
                    inlineMetric1Label = "Wi",
                    inlineMetric1Value = "12",
                ),
            ),
            WatchScreen(
                screenType = 1,
                tileLayout = 6,
                tiles = listOf(
                    WatchTile(label = "GARAGE", entityId = "cover.garage_door", color = "#FFAA00", colorEnabled = false, icon = "garage", displayMode = 1, confirm = true),
                    WatchTile(label = "LAMP", entityId = "light.lamp", color = "#FFFFFF"),
                ),
            ),
            WatchScreen(
                screenType = 1,
                tiles = listOf(WatchTile(label = "FAN", entityId = "switch.fan", color = "#00AAFF", icon = "fan")),
            ),
        ),
        tiles = listOf(WatchTile(label = "OLD", entityId = "light.old", color = "#888888")),
        statusBar = WatchStatusBar(battery = true, steps = false, heartRate = true, notifications = true, bluetooth = false),
        defaultActiveColor = "#112233",
        defaultInactiveColor = "#445566",
    )

    /**
     * Mapped keys the bytes do not carry: the fields the firmware never renders, which
     * `WatchFaceWire.stripNonRendered` leaves at their defaults so the encoder omits them (REQ009-FR-B4).
     * Whole levels go with their parent key: `theme`, `status_bar`, `presence_icon` and `notification`.
     * Their short-key decode stays covered by the hand-built seeds and the full-field fixture of
     * `tools/fuzz/layout_document_test.cpp`.
     */
    private val ABSENT_ON_WIRE = setOf(
        "layout.theme", "theme.background", "theme.accent",
        "layout.statusBar", "status_bar.battery", "status_bar.steps", "status_bar.heartRate",
        "status_bar.notifications", "status_bar.bluetooth",
        "header.timeAlign", "header.presenceIcons",
        "presence_icon.type", "presence_icon.color", "presence_icon.entity",
        "screen.tileLayout",
        "dashboard.notifications", "notification.source", "notification.text",
        "status_group.type", "sensor_group.type",
        "weather.entityId", "weather.temp",
        "glyph.entityId", "sensor.entityId", "sensor.displayType",
    )

    /** The non-zero defaults of `WatchFaceDefaultsGuardTest.WIRE_REACHABLE` that are still sent, as `level.key`. */
    private val NON_ZERO_DEFAULTS = listOf("layout.defaultActiveColor", "layout.defaultInactiveColor", "tile.colorEnabled")

    /** The layout the orchestrator kept for the phone (and persists) after the last push. */
    private var phoneCopy: WatchFaceLayoutPayload? = null

    private fun sendThroughOrchestrator(shortKeys: Boolean): ByteArray {
        val watch = FakeWatch(if (shortKeys) shortFeatures else baseFeatures)
        val ha = mockk<HaWebSocketClient>()
        // HA errors, so nothing is enriched and the bytes do not depend on the clock or on live state.
        every { ha.isConnected() } returns false
        coEvery { ha.getStates() } returns AppResult.Err(IllegalStateException("HA unreachable"))
        val haApi = mockk<HaApi>()
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
        )
        var result: WatchFaceLayoutResult? = null
        runTest {
            result = orchestrator.handleWatchFaceLayoutCommand(
                payload = fixtureLayout(),
                ha = ha,
                transport = watch.transport,
                onProgress = null,
                peerFeatures = if (shortKeys) shortFeatures else baseFeatures,
                peerHelloDiagRev = 2,
            )
        }
        assertEquals(WatchFaceLayoutResult.Success, result)
        phoneCopy = orchestrator.lastWatchFaceLayout
        assertEquals("exactly one layout frame", 1, watch.layoutFrames.size)
        return watch.layoutFrames.single().payload
    }

    // ---- files -------------------------------------------------------------------

    /** The monorepo's fuzz seed directory, or null in the bridge repo, which has no firmware tree. */
    private fun monorepoSeedDir(): File? =
        MonorepoSources.find("tools/fuzz/seeds/layout_document/app_long_keys.cbor")?.parentFile

    /** The seed the app's tests read: the copy on the test classpath (see VendoredFixturesTest). */
    private fun vendoredSeed(name: String): ByteArray? =
        ShortKeyFixtureTest::class.java.getResourceAsStream("/fuzz-seeds/layout_document/$name")?.use { it.readBytes() }

    private val regenerate get() = System.getenv("REQ009_REGEN_SEEDS") == "1"

    private fun checkAgainstFile(name: String, actual: ByteArray) {
        if (regenerate) {
            // Rewrites the fuzz corpus seed and the vendored copy (the test JVM runs in the module directory).
            val seedDir = requireNotNull(monorepoSeedDir()) { "REQ009_REGEN_SEEDS needs the monorepo tree" }
            val vendored = File(System.getProperty("user.dir"), "src/test/resources/fuzz-seeds/layout_document/$name")
            for (file in listOf(File(seedDir, name), vendored)) {
                file.parentFile?.mkdirs()
                file.writeBytes(actual)
                println("REGENERATED ${file.path} (${actual.size} bytes)")
            }
            return
        }
        val expected = vendoredSeed(name)
        assertNotNull(
            "$name is missing from the test resources; set REQ009_REGEN_SEEDS=1 for one run to create it",
            expected,
        )
        assertArrayEquals("$name must equal the bytes the send path produces; regenerate with REQ009_REGEN_SEEDS=1", expected, actual)
    }

    // ---- a minimal CBOR reader: which keys sit at which registry level ---------------

    /** Every `level.key` the bytes carry, with keys read as the [short] or the long spelling. */
    private class Walker(private val b: ByteArray, private val short: Boolean) {
        var pos = 0
        val seen = linkedSetOf<String>()

        private fun byte() = b[pos++].toInt() and 0xFF

        private fun arg(info: Int): Long = when {
            info < 24 -> info.toLong()
            info == 24 -> byte().toLong()
            info == 25 -> ((byte() shl 8) or byte()).toLong()
            info == 26 -> (byte().toLong() shl 24) or (byte().toLong() shl 16) or (byte().toLong() shl 8) or byte().toLong()
            info == 27 -> { var v = 0L; repeat(8) { v = (v shl 8) or byte().toLong() }; v }
            else -> error("unsupported CBOR additional info $info at ${pos - 1}")
        }

        private fun text(info: Int): String {
            val n = arg(info).toInt()
            val s = String(b, pos, n, Charsets.UTF_8)
            pos += n
            return s
        }

        /** Child level of [key] under [level], or null for a scalar key. */
        private fun child(level: String, key: String): String? = CHILD[level]?.get(key)

        fun item(level: String?) {
            val head = byte()
            val major = head shr 5
            val info = head and 0x1F
            when (major) {
                0, 1 -> arg(info)
                3 -> text(info)
                7 -> require(info in 20..22) { "unsupported simple value $info" }
                4 -> {
                    if (info == 31) { while (b[pos].toInt() and 0xFF != 0xFF) item(level); pos++ }
                    else repeat(arg(info).toInt()) { item(level) }
                }
                5 -> {
                    var remaining = if (info == 31) -1L else arg(info)
                    while (if (info == 31) (b[pos].toInt() and 0xFF) != 0xFF else remaining > 0) {
                        val kh = byte()
                        require(kh shr 5 == 3) { "non-text key at ${pos - 1}" }
                        val wire = text(kh and 0x1F)
                        val longKey: String = if (level == null) wire else if (short) {
                            WireShortKeys.BY_LEVEL.getValue(level).entries.firstOrNull { it.value == wire }?.key
                                ?: error("'$wire' is not a short key of level $level")
                        } else {
                            require(wire in WireShortKeys.BY_LEVEL.getValue(level)) { "'$wire' is not a long key of level $level" }
                            wire
                        }
                        if (level != null) seen += "$level.$longKey"
                        item(if (level == null) null else child(level, longKey))
                        if (info != 31) remaining--
                    }
                    if (info == 31) pos++
                }
                else -> error("unsupported CBOR major type $major")
            }
        }

        companion object {
            /** Registry level of the value under `level.key`. Arrays carry the level of their elements. */
            val CHILD: Map<String, Map<String, String>> = mapOf(
                "layout" to mapOf("theme" to "theme", "header" to "header", "screens" to "screen", "tiles" to "tile", "statusBar" to "status_bar"),
                "header" to mapOf("presenceIcons" to "presence_icon"),
                "screen" to mapOf("tiles" to "tile", "dashboard" to "dashboard"),
                "dashboard" to mapOf(
                    "statusGroups" to "status_group", "sensorGroups" to "sensor_group", "weather" to "weather",
                    "events" to "event", "notifications" to "notification",
                ),
                "status_group" to mapOf("glyphs" to "glyph"),
                "sensor_group" to mapOf("sensors" to "sensor"),
            )
        }
    }

    private fun keysOf(bytes: ByteArray, short: Boolean): Set<String> =
        Walker(bytes, short).also { it.item("layout"); assertEquals("whole payload consumed", bytes.size, it.pos) }.seen

    // ---- tests -------------------------------------------------------------------

    @Test
    fun `the sent bytes equal the committed app_short_keys and app_long_keys seeds`() {
        val long = sendThroughOrchestrator(shortKeys = false)
        val short = sendThroughOrchestrator(shortKeys = true)
        assertTrue("short spelling is smaller", short.size < long.size)
        checkAgainstFile("app_long_keys.cbor", long)
        checkAgainstFile("app_short_keys.cbor", short)
    }

    @Test
    fun `the fixture writes every mapped key of every registry level in both spellings`() {
        val expected = WireShortKeys.BY_LEVEL.flatMap { (level, keys) -> keys.keys.map { "$level.$it" } }.toSet() - ABSENT_ON_WIRE
        val longSeen = keysOf(sendThroughOrchestrator(shortKeys = false), short = false)
        val shortSeen = keysOf(sendThroughOrchestrator(shortKeys = true), short = true)
        assertEquals("keys carried by the long bytes", expected, longSeen)
        // The short reader accepts only short keys, and maps them back to the same long keys.
        assertEquals("keys carried by the short bytes", expected, shortSeen)
        for (k in NON_ZERO_DEFAULTS) assertTrue("$k must be on the wire at a non-default value", k in longSeen)
        assertEquals(15, WireShortKeys.BY_LEVEL.size)
    }

    @Test
    fun `every stripped field is absent and every rendered field is present in both spellings`() {
        // The stripped set is exactly the mapped keys the fixture populates and the bytes lack.
        val mapped = WireShortKeys.BY_LEVEL.flatMap { (level, keys) -> keys.keys.map { "$level.$it" } }.toSet()
        for (k in ABSENT_ON_WIRE) assertTrue("$k is a mapped registry key", k in mapped)
        for (short in listOf(false, true)) {
            val seen = keysOf(sendThroughOrchestrator(shortKeys = short), short = short)
            for (k in ABSENT_ON_WIRE) assertTrue("$k is absent (short=$short)", k !in seen)
            for (k in mapped - ABSENT_ON_WIRE) assertTrue("$k is present (short=$short)", k in seen)
        }
    }

    @Test
    fun `the long bytes decode to the fixture with only the stripped fields removed`() {
        val sent = ProtocolCbor.decode(WatchFaceLayoutPayload.serializer(), sendThroughOrchestrator(shortKeys = false))
        val fixture = fixtureLayout()
        assertEquals(WatchFaceWire.stripNonRendered(fixture), sent)
        // Firmware-read fields, checked one by one against the original layout.
        assertEquals(fixture.version, sent.version)
        assertEquals(fixture.header.timeFormat, sent.header.timeFormat)
        assertEquals(fixture.defaultActiveColor, sent.defaultActiveColor)
        assertEquals(fixture.defaultInactiveColor, sent.defaultInactiveColor)
        assertEquals(fixture.tiles, sent.tiles)
        assertEquals(fixture.screens.map { it.screenType }, sent.screens.map { it.screenType })
        assertEquals(fixture.screens.map { it.tiles }, sent.screens.map { it.tiles })
        val d = fixture.screens[0].dashboard!!
        val sd = sent.screens[0].dashboard!!
        assertEquals(d.statusGroups.map { g -> g.glyphs.map { it.symbol to it.active } }, sd.statusGroups.map { g -> g.glyphs.map { it.symbol to it.active } })
        assertEquals(d.sensorGroups.map { g -> g.sensors.map { it.value } }, sd.sensorGroups.map { g -> g.sensors.map { it.value } })
        assertEquals(d.weather!!.condition, sd.weather!!.condition)
        assertEquals(d.weather!!.hi, sd.weather!!.hi)
        assertEquals(d.weather!!.lo, sd.weather!!.lo)
        assertEquals(d.events, sd.events)
        assertEquals(d.lastEnrichedAt, sd.lastEnrichedAt)
        assertEquals(d.configurableValueLabel, sd.configurableValueLabel)
        assertEquals(d.configurableValueValue, sd.configurableValueValue)
        assertEquals(d.inlineMetric0Label, sd.inlineMetric0Label)
        assertEquals(d.inlineMetric0Value, sd.inlineMetric0Value)
        assertEquals(d.inlineMetric1Label, sd.inlineMetric1Label)
        assertEquals(d.inlineMetric1Value, sd.inlineMetric1Value)
        // What was stripped is gone from the wire copy.
        assertEquals(WatchTheme(background = "", accent = ""), sent.theme)
        assertEquals(null, sent.header.presenceIcons)
        assertEquals("", sent.header.timeAlign)
        assertTrue(sd.notifications.isEmpty())
    }

    @Test
    fun `the phone copy keeps the full layout`() {
        sendThroughOrchestrator(shortKeys = true)
        val kept = phoneCopy!!
        val fixture = fixtureLayout()
        assertEquals(fixture.theme, kept.theme)
        assertEquals(fixture.statusBar, kept.statusBar)
        assertEquals(fixture.header, kept.header)
        assertEquals(fixture.screens[0].dashboard!!.notifications, kept.screens[0].dashboard!!.notifications)
        assertEquals(fixture.screens[0].dashboard!!.statusGroups.map { it.type }, kept.screens[0].dashboard!!.statusGroups.map { it.type })
        assertEquals(fixture.screens[0].dashboard!!.weather, kept.screens[0].dashboard!!.weather)
    }
}
