package com.w6tech.infinassistant.ui

import com.w6tech.infinassistant.core.ble.FrameCodec
import com.w6tech.infinassistant.core.common.Logger
import com.w6tech.infinassistant.core.data.bridge.ProtocolHandler
import com.w6tech.infinassistant.core.data.bridge.StateManager
import com.w6tech.infinassistant.core.domain.HelloPayload
import com.w6tech.infinassistant.core.domain.Protocol
import com.w6tech.infinassistant.core.domain.ProtocolCbor
import com.w6tech.infinassistant.core.domain.WatchDashboard
import com.w6tech.infinassistant.core.domain.WatchEvent
import com.w6tech.infinassistant.core.domain.WatchFaceLayoutPayload
import com.w6tech.infinassistant.core.domain.WatchFaceWire
import com.w6tech.infinassistant.core.domain.WatchGlyph
import com.w6tech.infinassistant.core.domain.WatchHeader
import com.w6tech.infinassistant.core.domain.WatchScreen
import com.w6tech.infinassistant.core.domain.WatchSensor
import com.w6tech.infinassistant.core.domain.WatchSensorGroup
import com.w6tech.infinassistant.core.domain.WatchStatusBar
import com.w6tech.infinassistant.core.domain.WatchStatusGroup
import com.w6tech.infinassistant.core.domain.WatchTheme
import com.w6tech.infinassistant.core.domain.WatchTile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REQ009-FR-C4: the editor's `cborBytes` is the size of the bytes a push of the same layout
 * hands the transport (watch-face encoder, the connected watch's negotiated key spelling, long
 * when unknown), with the fields the watch never renders stripped as the push strips them.
 */
class WatchFaceEditorMetricTest {

    private val silentLogger = object : Logger {
        override fun d(tag: String, msg: String, tr: Throwable?) {}
        override fun i(tag: String, msg: String, tr: Throwable?) {}
        override fun w(tag: String, msg: String, tr: Throwable?) {}
        override fun e(tag: String, msg: String, tr: Throwable?) {}
    }

    /** A layout whose defaults (tile colour flag, colours, tile layout) are left at their values. */
    private fun layout() = WatchFaceLayoutPayload(
        version = 1,
        theme = WatchTheme(background = "#000000", accent = "#FF8800"),
        header = WatchHeader(timeFormat = "HH:mm", timeAlign = "center", presenceIcons = emptyList()),
        screens = listOf(
            WatchScreen(
                screenType = 0,
                dashboard = WatchDashboard(
                    statusGroups = listOf(WatchStatusGroup(type = "doors", glyphs = listOf(WatchGlyph(symbol = "D", entityId = "", active = true)))),
                    sensorGroups = listOf(WatchSensorGroup(type = "thermostats", sensors = listOf(WatchSensor(value = 71)))),
                    events = listOf(WatchEvent(time = "09:30", title = "Standup", startEpoch = 1_790_000_000L)),
                ),
            ),
            WatchScreen(screenType = 1, tiles = listOf(WatchTile(label = "Lamp", color = "#FFFFFF"))),
        ),
        statusBar = WatchStatusBar(battery = true, steps = false, heartRate = true, notifications = false, bluetooth = true),
    )

    private fun handlerWithFeatures(features: Int?): ProtocolHandler {
        val handler = ProtocolHandler(silentLogger, StateManager()) { _, _, _, _ -> }
        if (features != null) {
            val hello = ProtocolCbor.encode(
                HelloPayload.serializer(),
                HelloPayload(device = "watch", features = features, freeHeap = 20_000L, helloDiagRev = 2),
            )
            handler.handleHello(FrameCodec.CompleteMessage(1, Protocol.Type.HELLO.wire, 0, 1, hello))
        }
        return handler
    }

    /** What a push hands the encoder: the layout with the non-rendered fields stripped (REQ009-FR-B4). */
    private fun wire() = WatchFaceWire.stripNonRendered(layout())

    private val withoutBit = Protocol.Features.WATCHFACE_LAYOUT or Protocol.Features.WATCHFACE_ACK
    private val withBit = withoutBit or Protocol.Features.SHORT_KEYS

    @Test
    fun `long spelling measures the bytes a push hands the transport`() {
        val expected = ProtocolCbor.encodeWatchFaceLayout(wire(), shortKeys = false).bytes.size
        assertEquals(expected, buildConfigSummary(layout(), shortKeys = false).cborBytes)
    }

    @Test
    fun `short spelling measures the short bytes and is smaller than long`() {
        val short = buildConfigSummary(layout(), shortKeys = true).cborBytes
        val long = buildConfigSummary(layout(), shortKeys = false).cborBytes
        assertEquals(ProtocolCbor.encodeWatchFaceLayout(wire(), shortKeys = true).bytes.size, short)
        assertTrue("short $short must be under long $long", short < long)
    }

    @Test
    fun `the spelling follows the connected watch and is long when unknown`() {
        val cases = listOf(
            "bit set" to withBit,
            "bit clear" to withoutBit,
            "features 0" to 0,
            "no HELLO" to null,
        )
        for ((name, features) in cases) {
            val handler = handlerWithFeatures(features)
            val pushed = handler.encodeWatchFaceLayout(wire())
            val measured = buildConfigSummary(layout(), shortKeys = handler.peerSupports(Protocol.Features.SHORT_KEYS)).cborBytes
            assertEquals("$name: editor figure equals the pushed byte count", pushed.bytes.size, measured)
            assertEquals("$name: spelling", features == withBit, pushed.shortKeys)
        }
    }

    @Test
    fun `the other metrics are unchanged by the spelling`() {
        val long = buildConfigSummary(layout(), shortKeys = false)
        val short = buildConfigSummary(layout(), shortKeys = true)
        assertEquals(long.copy(cborBytes = 0), short.copy(cborBytes = 0))
        assertEquals(2, long.screenCount)
        assertEquals(2, long.objectCount)
        assertEquals(2, long.styleCount)
    }
}
