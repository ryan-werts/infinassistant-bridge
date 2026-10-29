package com.w6tech.infinassistant.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CBOR round-trip tests for all protocol payload types.
 * Verifies encode → bytes → decode → assertEquals for each payload.
 * Tests use ProtocolCbor (kotlinx.serialization.cbor with ignoreUnknownKeys).
 */
class ProtocolCborTest {

    // ── HELLO ──────────────────────────────────────────────

    @Test
    fun `HelloPayload phone round-trip`() {
        val original = HelloPayload(device = "phone")
        val decoded = roundTrip(HelloPayload.serializer(), original)
        assertEquals(original, decoded)
    }

    @Test
    fun `HelloPayload watch with diagnostics round-trip`() {
        val original = HelloPayload(
            device = "watch",
            fwVersion = "1.90.0",
            resetReason = "Watchdog",
            freeHeap = 12345,
            minFreeHeap = 8000,
            mallocFails = 2,
            stackOverflows = 0,
            mbufFailures = 1,
            uptimeTicks = 500000,
            crashContext = CrashContextPayload(
                breadcrumb = 3,
                lastMsgType = 0x0E,
                lastMsgId = 42,
                freeHeap = 4000,
                uptimeTicks = 499000,
            ),
        )
        val decoded = roundTrip(HelloPayload.serializer(), original)
        assertEquals(original, decoded)
    }

    @Test
    fun `HelloPayload watch without crash context round-trip`() {
        val original = HelloPayload(
            device = "watch",
            fwVersion = "1.91.0",
            resetReason = "SoftReset",
            freeHeap = 20000,
            minFreeHeap = 15000,
            mallocFails = 0,
            stackOverflows = 0,
            mbufFailures = 0,
            uptimeTicks = 100,
        )
        val decoded = roundTrip(HelloPayload.serializer(), original)
        assertEquals(original, decoded)
        assertNull(decoded.crashContext)
    }

    // ── CONFIG_PUSH ────────────────────────────────────────

    @Test
    fun `ConfigPushPayload round-trip`() {
        val original = ConfigPushPayload(
            configVersion = 5,
            infinassistant = InfinassistantConfig(enabled = true, sensorReportIntervalSeconds = 30),
        )
        val decoded = roundTrip(ConfigPushPayload.serializer(), original)
        assertEquals(original, decoded)
    }

    @Test
    fun `ConfigPushPayload carries voiceAssist only when it is on`() {
        val on = roundTrip(ConfigPushPayload.serializer(), ConfigPushPayload(configVersion = 1, voiceAssist = true))
        assertEquals(true, on.voiceAssist)
        val off = roundTrip(ConfigPushPayload.serializer(), ConfigPushPayload(configVersion = 1))
        assertEquals(false, off.voiceAssist)
        val onBytes = ProtocolCbor.encode(ConfigPushPayload.serializer(), ConfigPushPayload(configVersion = 1, voiceAssist = true))
        val offBytes = ProtocolCbor.encode(ConfigPushPayload.serializer(), ConfigPushPayload(configVersion = 1))
        assertEquals(true, onBytes.size > offBytes.size)
    }

    // ── CONFIG_ACK ─────────────────────────────────────────

    @Test
    fun `ConfigAckPayload round-trip`() {
        val original = ConfigAckPayload(ok = true, appliedVersion = 5)
        val decoded = roundTrip(ConfigAckPayload.serializer(), original)
        assertEquals(original, decoded)
    }

    @Test
    fun `ConfigAckPayload failure round-trip`() {
        val original = ConfigAckPayload(ok = false, appliedVersion = 0)
        val decoded = roundTrip(ConfigAckPayload.serializer(), original)
        assertEquals(original, decoded)
    }

    // ── STATE_UPDATE ───────────────────────────────────────

    @Test
    fun `StateUpdatePayload round-trip`() {
        val original = StateUpdatePayload(
            items = listOf(
                StateItem(entityId = "sensor.temp", state = "22.5", unit = "°C", ts = 1700000000000),
                StateItem(entityId = "binary_sensor.door", state = "on", ts = 1700000001000),
            ),
        )
        val decoded = roundTrip(StateUpdatePayload.serializer(), original)
        assertEquals(original, decoded)
    }

    @Test
    fun `StateUpdatePayload empty items round-trip`() {
        val original = StateUpdatePayload(items = emptyList())
        val decoded = roundTrip(StateUpdatePayload.serializer(), original)
        assertEquals(original, decoded)
    }

    // ── STATE_REFRESH_REQUEST ──────────────────────────────

    @Test
    fun `StateRefreshRequestPayload all round-trip`() {
        val original = StateRefreshRequestPayload(all = true)
        val decoded = roundTrip(StateRefreshRequestPayload.serializer(), original)
        assertEquals(original, decoded)
    }

    @Test
    fun `StateRefreshRequestPayload specific entities round-trip`() {
        val original = StateRefreshRequestPayload(entityIds = listOf("sensor.temp", "light.hall"))
        val decoded = roundTrip(StateRefreshRequestPayload.serializer(), original)
        assertEquals(original, decoded)
    }

    // ── ACTION_INVOKE ──────────────────────────────────────

    @Test
    fun `ActionInvokePayload tap round-trip`() {
        val original = ActionInvokePayload(actionId = 1, gesture = "tap")
        val decoded = roundTrip(ActionInvokePayload.serializer(), original)
        assertEquals(original, decoded)
    }

    @Test
    fun `ActionInvokePayload tile with entityId round-trip`() {
        val original = ActionInvokePayload(
            actionId = 0xFFFD,
            gesture = "tap",
            entityId = "cover.garage",
        )
        val decoded = roundTrip(ActionInvokePayload.serializer(), original)
        assertEquals(original, decoded)
    }

    @Test
    fun `ActionInvokePayload voice assist round-trip`() {
        val original = ActionInvokePayload(actionId = 0xFFFE, gesture = "tap")
        val decoded = roundTrip(ActionInvokePayload.serializer(), original)
        assertEquals(Protocol.VOICE_ASSIST_ACTION_ID, decoded.actionId)
    }

    // ── ACTION_RESULT ──────────────────────────────────────

    @Test
    fun `ActionResultPayload success round-trip`() {
        val original = ActionResultPayload(actionId = 1, ok = true, message = "queued")
        val decoded = roundTrip(ActionResultPayload.serializer(), original)
        assertEquals(original, decoded)
    }

    @Test
    fun `ActionResultPayload failure round-trip`() {
        val original = ActionResultPayload(actionId = 1, ok = false, message = "timeout")
        val decoded = roundTrip(ActionResultPayload.serializer(), original)
        assertEquals(original, decoded)
    }

    // ── NOTIFY ─────────────────────────────────────────────

    @Test
    fun `NotifyPayload round-trip`() {
        val original = NotifyPayload(
            title = "Garage Open",
            body = "The garage door has been open for 10 minutes",
            severity = Severity.WARN,
            tag = "garage_alert",
            ts = 1700000000000,
        )
        val decoded = roundTrip(NotifyPayload.serializer(), original)
        assertEquals(original, decoded)
    }

    @Test
    fun `NotifyPayload minimal round-trip`() {
        val original = NotifyPayload(
            title = "Test",
            body = "Hello",
            ts = 0,
        )
        val decoded = roundTrip(NotifyPayload.serializer(), original)
        assertEquals(original, decoded)
    }

    @Test
    fun `NotifyPayload with a phone nid round-trips`() {
        val original = NotifyPayload(title = "Alice", body = "Dinner at 7?", tag = "12", ts = 1700000000000, nid = 12)
        assertEquals(original, roundTrip(NotifyPayload.serializer(), original))
    }

    @Test
    fun `NotifyRemovePayload and NotifyDismissPayload round-trip`() {
        assertEquals(NotifyRemovePayload(65535), roundTrip(NotifyRemovePayload.serializer(), NotifyRemovePayload(65535)))
        assertEquals(NotifyDismissPayload(1), roundTrip(NotifyDismissPayload.serializer(), NotifyDismissPayload(1)))
    }

    // ── NOTIFY_ACK ─────────────────────────────────────────

    @Test
    fun `NotifyAckPayload round-trip`() {
        val original = NotifyAckPayload(tag = "garage_alert", msgId = 42, displayed = true)
        val decoded = roundTrip(NotifyAckPayload.serializer(), original)
        assertEquals(original, decoded)
    }

    @Test
    fun `NotifyAckPayload without tag round-trip`() {
        val original = NotifyAckPayload(msgId = 100, displayed = true)
        val decoded = roundTrip(NotifyAckPayload.serializer(), original)
        assertEquals(original, decoded)
    }

    // ── SENSOR_REPORT ──────────────────────────────────────

    @Test
    fun `SensorReportPayload round-trip`() {
        val original = SensorReportPayload(
            steps = 8432,
            heartRate = 72,
            heartRateState = 3,
            batteryPercent = 85,
            batteryCharging = false,
            motionX = -12,
            motionY = 1023,
            motionZ = -500,
            timestamp = 1700000000000,
        )
        val decoded = roundTrip(SensorReportPayload.serializer(), original)
        assertEquals(original, decoded)
    }

    @Test
    fun `SensorReportPayload with diagnostics round-trip`() {
        val original = SensorReportPayload(
            steps = 100,
            heartRate = 60,
            heartRateState = 0,
            batteryPercent = 50,
            batteryCharging = true,
            motionX = 0,
            motionY = 0,
            motionZ = 0,
            timestamp = 1700000000000,
            freeHeap = 12000,
            minFreeHeap = 8000,
            stackHighWater = 256,
        )
        val decoded = roundTrip(SensorReportPayload.serializer(), original)
        assertEquals(original, decoded)
    }

    @Test
    fun `SensorReportPayload without diagnostics round-trip`() {
        val original = SensorReportPayload(
            steps = 0,
            heartRate = 0,
            heartRateState = 0,
            batteryPercent = 100,
            batteryCharging = true,
            motionX = 0,
            motionY = 0,
            motionZ = 0,
            timestamp = 0,
        )
        val decoded = roundTrip(SensorReportPayload.serializer(), original)
        assertNull(decoded.freeHeap)
        assertNull(decoded.minFreeHeap)
        assertNull(decoded.stackHighWater)
    }

    @Test
    fun `SensorReportPayload with dashboard counters round-trip`() {
        val original = SensorReportPayload(
            steps = 100, heartRate = 60, heartRateState = 0, batteryPercent = 50, batteryCharging = true,
            motionX = 0, motionY = 0, motionZ = 0, timestamp = 1700000000,
            freeHeap = 4012, minFreeHeap = 3100, stackHighWater = 120,
            dashStateOk = 65535, dashStateRefused = 2, fingerprintMisses = 1, dashStateAgeSec = 4294967295,
        )
        assertEquals(original, roundTrip(SensorReportPayload.serializer(), original))
    }

    @Test
    fun `SensorReportPayload from firmware without dashboard counters leaves them null`() {
        val original = SensorReportPayload(
            steps = 100, heartRate = 60, heartRateState = 0, batteryPercent = 50, batteryCharging = true,
            motionX = 0, motionY = 0, motionZ = 0, timestamp = 1700000000,
            freeHeap = 4012, minFreeHeap = 3100, stackHighWater = 120,
        )
        val decoded = roundTrip(SensorReportPayload.serializer(), original)
        assertEquals(4012L, decoded.freeHeap)
        assertNull(decoded.dashStateOk)
        assertNull(decoded.dashStateRefused)
        assertNull(decoded.fingerprintMisses)
        assertNull(decoded.dashStateAgeSec)
    }

    @Test
    fun `SensorReportPayload carries the battery millivolts under bmv`() {
        val original = SensorReportPayload(
            steps = 100, heartRate = 60, heartRateState = 0, batteryPercent = 50, batteryCharging = false,
            motionX = 0, motionY = 0, motionZ = 0, timestamp = 1700000000,
            batteryMillivolts = 3950,
        )
        val bytes = ProtocolCbor.encode(SensorReportPayload.serializer(), original)
        assertEquals(3950, ProtocolCbor.decode(SensorReportPayload.serializer(), bytes).batteryMillivolts)
        // The key is the text "bmv" (0x63 'b' 'm' 'v') followed by the uint16 3950 (0x19 0x0F 0x6E).
        val key = byteArrayOf(0x63, 'b'.code.toByte(), 'm'.code.toByte(), 'v'.code.toByte(), 0x19, 0x0F, 0x6E)
        assertTrue(bytes.toList().windowed(key.size).any { it == key.toList() })
        // Firmware before 2.4.105 sends none: it decodes as null.
        val older = ProtocolCbor.encode(SensorReportPayload.serializer(), original.copy(batteryMillivolts = null))
        assertNull(ProtocolCbor.decode(SensorReportPayload.serializer(), older).batteryMillivolts)
    }

    @Test
    fun `SensorReportPayload carries power present under pp, and reads absent as null`() {
        val original = SensorReportPayload(
            steps = 100, heartRate = 60, heartRateState = 0, batteryPercent = 100, batteryCharging = false,
            motionX = 0, motionY = 0, motionZ = 0, timestamp = 1700000000,
            batteryMillivolts = 4150, powerPresent = true,
        )
        val bytes = ProtocolCbor.encode(SensorReportPayload.serializer(), original)
        assertEquals(true, ProtocolCbor.decode(SensorReportPayload.serializer(), bytes).powerPresent)
        // The key is the text "pp" (0x62 'p' 'p') followed by true (0xF5).
        val key = byteArrayOf(0x62, 'p'.code.toByte(), 'p'.code.toByte(), 0xF5.toByte())
        assertTrue(bytes.toList().windowed(key.size).any { it == key.toList() })
        // Firmware sends the key only while power is present: absent decodes as null, which is off power.
        val off = ProtocolCbor.encode(SensorReportPayload.serializer(), original.copy(powerPresent = null))
        assertNull(ProtocolCbor.decode(SensorReportPayload.serializer(), off).powerPresent)
    }

    @Test
    fun `SensorReportPayload decodes the report the watch encodes`() {
        // The firmware encoder's bytes (ha_bridge/SensorReport.h), which tools/fuzz/sensor_report_test
        // checks against this seed: 20 keys, the four LVGL ones unknown here.
        val decoded = ProtocolCbor.decode(SensorReportPayload.serializer(), fuzzSeed("sensor_report/watch_report.cbor"))
        assertEquals(8432L, decoded.steps)
        assertEquals(72, decoded.heartRate)
        assertEquals(85, decoded.batteryPercent)
        assertEquals(false, decoded.batteryCharging)
        assertEquals(-500, decoded.motionZ)
        assertEquals(1791910249L, decoded.timestamp)
        assertEquals(4012L, decoded.freeHeap)
        assertEquals(3100L, decoded.minFreeHeap)
        assertEquals(120, decoded.stackHighWater)
        assertEquals(41, decoded.dashStateOk)
        assertEquals(2, decoded.dashStateRefused)
        assertEquals(1, decoded.fingerprintMisses)
        assertEquals(33L, decoded.dashStateAgeSec)
    }

    /** A committed fuzz seed, read from its copy in the test resources (VendoredFixturesTest compares it with the original). */
    private fun fuzzSeed(path: String): ByteArray =
        requireNotNull(ProtocolCborTest::class.java.getResourceAsStream("/fuzz-seeds/$path")) {
            "test resource fuzz-seeds/$path missing"
        }.use { it.readBytes() }

    // ── HAPTIC_FEEDBACK ────────────────────────────────────

    @Test
    fun `HapticFeedbackPayload round-trip`() {
        for (pattern in listOf("short", "long", "double", "recording_start", "recording_stop")) {
            val original = HapticFeedbackPayload(pattern = pattern)
            val decoded = roundTrip(HapticFeedbackPayload.serializer(), original)
            assertEquals(pattern, decoded.pattern)
        }
    }

    // ── TIME_SYNC ──────────────────────────────────────────

    @Test
    fun `TimeSyncPayload round-trip`() {
        val original = TimeSyncPayload(
            timestamp = 1700000000,
            timezoneOffset = -18000,
            isDst = true,
        )
        val decoded = roundTrip(TimeSyncPayload.serializer(), original)
        assertEquals(original, decoded)
    }

    // ── WATCHFACE_LAYOUT ───────────────────────────────────

    @Test
    fun `WatchFaceLayoutPayload simple tiles round-trip`() {
        val original = WatchFaceLayoutPayload(
            version = 1,
            theme = WatchTheme(background = "charcoal", accent = "#00BFFF"),
            header = WatchHeader(
                timeFormat = "HH:mm",
                timeAlign = "left",
                presenceIcons = listOf(
                    WatchPresenceIcon(type = "person_male", color = "#2196F3", entity = "person.dale"),
                ),
            ),
            screens = listOf(
                WatchScreen(
                    screenType = 1,
                    tileLayout = 4,
                    tiles = listOf(
                        WatchTile(label = "GARAGE", entityId = "cover.garage", color = "#FF6B35"),
                        WatchTile(label = "FRONT", entityId = "lock.front", color = "#2196F3", colorEnabled = false),
                    ),
                ),
            ),
            statusBar = WatchStatusBar(battery = true, steps = true, heartRate = false, notifications = true, bluetooth = true),
        )
        val decoded = roundTrip(WatchFaceLayoutPayload.serializer(), original)
        assertEquals(original, decoded)
    }

    @Test
    fun `WatchFaceLayoutPayload dashboard screen round-trip`() {
        val original = WatchFaceLayoutPayload(
            version = 2,
            theme = WatchTheme(background = "black", accent = "#FFD700"),
            header = WatchHeader(timeFormat = "hh:mm", timeAlign = "center", presenceIcons = emptyList()),
            screens = listOf(
                WatchScreen(
                    screenType = 0,
                    dashboard = WatchDashboard(
                        statusGroups = listOf(
                            WatchStatusGroup(
                                type = "doors",
                                glyphs = listOf(
                                    WatchGlyph(symbol = "D", entityId = "binary_sensor.door", active = true),
                                    WatchGlyph(symbol = "G", entityId = "cover.garage", active = false),
                                ),
                            ),
                        ),
                        sensorGroups = listOf(
                            WatchSensorGroup(
                                type = "thermostats",
                                sensors = listOf(
                                    WatchSensor(entityId = "climate.living", displayType = "temperature", value = 72),
                                ),
                            ),
                        ),
                        weather = WatchWeather(entityId = "weather.home", condition = "sunny", temp = 75, hi = 80, lo = 65),
                        events = listOf(
                            WatchEvent(time = "10:00", title = "Team standup"),
                        ),
                        notifications = listOf(
                            WatchNotification(source = "Slack", text = "New message"),
                        ),
                    ),
                ),
            ),
            statusBar = WatchStatusBar(battery = true, steps = false, heartRate = true, notifications = false, bluetooth = true),
            defaultActiveColor = "#00FF00",
            defaultInactiveColor = "#FF0000",
        )
        val decoded = roundTrip(WatchFaceLayoutPayload.serializer(), original)
        assertEquals(original, decoded)
    }

    // ── LOGBOOK_UPDATE ─────────────────────────────────────

    @Test
    fun `LogbookUpdatePayload round-trip`() {
        val original = LogbookUpdatePayload(
            screenIndex = 1,
            entries = listOf(
                LogbookEntry(time = "14:32", state = "ON", subject = "Living Room"),
                LogbookEntry(time = "14:10", state = "OPN", subject = "Front Door"),
                LogbookEntry(time = "13:55", state = "CLSD", subject = "Garage"),
            ),
        )
        val decoded = roundTrip(LogbookUpdatePayload.serializer(), original)
        assertEquals(original, decoded)
    }

    @Test
    fun `LogbookUpdatePayload empty entries round-trip`() {
        val original = LogbookUpdatePayload(screenIndex = 0, entries = emptyList())
        val decoded = roundTrip(LogbookUpdatePayload.serializer(), original)
        assertEquals(original, decoded)
    }

    @Test
    fun `LogbookEntry compact field names round-trip`() {
        // The firmware reads "t", "s" and "n" (BridgeController::HandleLogbookUpdate); the wire
        // keys themselves are checked in core-data's LogbookWireTest.
        val entry = LogbookEntry(time = "09:15", state = "72.0", subject = "Thermostat")
        val bytes = ProtocolCbor.encode(LogbookEntry.serializer(), entry)
        val decoded = ProtocolCbor.decode(LogbookEntry.serializer(), bytes)
        assertEquals("09:15", decoded.time)
        assertEquals("72.0", decoded.state)
        assertEquals("Thermostat", decoded.subject)
    }

    // ── WATCHFACE_LAYOUT with logbook screen ─────────────

    @Test
    fun `WatchFaceLayoutPayload logbook screen round-trip`() {
        val original = WatchFaceLayoutPayload(
            version = 2,
            theme = WatchTheme(background = "dark", accent = "#FF5722"),
            header = WatchHeader(timeFormat = "HH:mm", timeAlign = "left", presenceIcons = emptyList()),
            screens = listOf(
                WatchScreen(
                    screenType = 2,
                    logbook = WatchLogbook(
                        entityIds = listOf("light.living_room", "binary_sensor.front_door"),
                        maxEntries = 10,
                        hoursBack = 6,
                    ),
                ),
            ),
            statusBar = WatchStatusBar(battery = true, steps = false, heartRate = false, notifications = false, bluetooth = true),
        )
        val decoded = roundTrip(WatchFaceLayoutPayload.serializer(), original)
        assertEquals(original, decoded)
        assertEquals(2, decoded.screens[0].screenType)
        assertNotNull(decoded.screens[0].logbook)
        assertEquals(2, decoded.screens[0].logbook!!.entityIds.size)
    }

    // ── EMPTY PAYLOAD ──────────────────────────────────────

    @Test
    fun `EmptyPayload round-trip`() {
        val original = EmptyPayload()
        val bytes = ProtocolCbor.encode(EmptyPayload.serializer(), original)
        val decoded = ProtocolCbor.decode(EmptyPayload.serializer(), bytes)
        assertNotNull(decoded)
    }

    // ── EDGE CASES ─────────────────────────────────────────

    @Test
    fun `HelloPayload backward compatible - decode without new fields`() {
        // Simulate old firmware sending minimal HELLO (just device field)
        val minimal = HelloPayload(device = "watch")
        val bytes = ProtocolCbor.encode(HelloPayload.serializer(), minimal)
        val decoded = ProtocolCbor.decode(HelloPayload.serializer(), bytes)
        assertEquals("watch", decoded.device)
        assertNull(decoded.fwVersion)
        assertNull(decoded.crashContext)
    }

    @Test
    fun `SensorReportPayload backward compatible - decode without diagnostics`() {
        // Simulate old firmware sending sensor report without diagnostic fields
        val minimal = SensorReportPayload(
            steps = 1000,
            heartRate = 70,
            heartRateState = 3,
            batteryPercent = 90,
            batteryCharging = false,
            motionX = 0,
            motionY = 0,
            motionZ = 0,
            timestamp = 1700000000000,
        )
        val bytes = ProtocolCbor.encode(SensorReportPayload.serializer(), minimal)
        val decoded = ProtocolCbor.decode(SensorReportPayload.serializer(), bytes)
        assertEquals(1000L, decoded.steps)
        assertNull(decoded.freeHeap)
    }

    // ── Helper ─────────────────────────────────────────────

    private fun <T> roundTrip(serializer: kotlinx.serialization.KSerializer<T>, original: T): T {
        val bytes = ProtocolCbor.encode(serializer, original)
        return ProtocolCbor.decode(serializer, bytes)
    }
}
