package com.w6tech.infinassistant.core.data.bridge

import com.w6tech.infinassistant.core.ble.BleTransport
import com.w6tech.infinassistant.core.ble.FrameCodec
import com.w6tech.infinassistant.core.common.AppResult
import com.w6tech.infinassistant.core.domain.ConfigPushPayload
import com.w6tech.infinassistant.core.domain.HelloPayload
import com.w6tech.infinassistant.core.domain.InfinassistantConfig
import com.w6tech.infinassistant.core.domain.Protocol
import com.w6tech.infinassistant.core.domain.ProtocolCbor
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The renamed CONFIG key (firmware 2.4.103, `infinassistant`). A watch that does not say it reads it
 * (no [Protocol.Features.CONFIG_KEY_V2] in its HELLO) is sent no CONFIG_PUSH, and the app shows
 * "Update the watch" instead; a watch that does say so gets the CONFIG_PUSH with the new key. The
 * bytes and the feature word are the golden vectors the firmware's host tests pin too.
 */
class ConfigKeyGateTest {

    private val handler = ProtocolHandler(WireTestSupport.silentLogger, StateManager()) { _, _, _, _ -> }

    /** What was written to the watch: the message type and its payload. */
    private val written = mutableListOf<Pair<Int, ByteArray>>()
    private val transport: BleTransport = mockk<BleTransport>().also { t ->
        coEvery { t.send(any(), any(), any(), any(), any()) } coAnswers {
            written += firstArg<Int>() to arg<ByteArray>(3)
            AppResult.Ok(Unit)
        }
    }

    private fun helloFrame(payload: ByteArray) =
        FrameCodec.CompleteMessage(FrameCodec.VERSION, Protocol.Type.HELLO.wire, 0, 1, payload)

    private fun helloWithFeatures(features: Int?) = helloFrame(
        ProtocolCbor.encode(
            HelloPayload.serializer(),
            if (features == null) HelloPayload(device = "watch", fwVersion = "2.4.64") else HelloPayload(device = "watch", features = features, fwVersion = "2.4.102"),
        ),
    )

    /** The CONFIG_PUSH the golden vector holds: the reports on at 120 s, voice assist on. */
    private val config = ConfigPushPayload(
        configVersion = 1,
        infinassistant = InfinassistantConfig(enabled = true, sensorReportIntervalSeconds = 120),
        voiceAssist = true,
    )

    private fun assertNoConfigSent() {
        assertTrue("no CONFIG_PUSH is written", written.none { it.first == Protocol.Type.CONFIG_PUSH.wire })
        assertTrue("nothing at all is written", written.isEmpty())
    }

    @Test
    fun `a HELLO without bit 16 shows the update prompt and sends no CONFIG`() = runTest {
        handler.resetSession()
        // The word of firmware 2.4.65 to 2.4.102.
        val word = GoldenVectors.value("hello_features_word_2_4_102").toInt()
        assertEquals(0, word and Protocol.Features.CONFIG_KEY_V2)
        handler.handleHello(helloWithFeatures(word))

        assertTrue(handler.watchPredatesConfigKey)
        assertFalse(handler.sendConfigPush(transport, 7, config))
        assertNoConfigSent()
    }

    @Test
    fun `a HELLO with no feature word shows the prompt too`() = runTest {
        handler.resetSession()
        handler.handleHello(helloWithFeatures(null))

        assertEquals(0, handler.peerFeatures)
        assertTrue(handler.watchPredatesConfigKey)
        assertFalse(handler.sendConfigPush(transport, 7, config))
        assertNoConfigSent()
    }

    @Test
    fun `an empty HELLO from very old firmware shows the prompt too`() = runTest {
        handler.resetSession()
        handler.handleHello(helloFrame(ByteArray(0)))

        assertTrue(handler.watchPredatesConfigKey)
        assertFalse(handler.sendConfigPush(transport, 7, config))
        assertNoConfigSent()
    }

    @Test
    fun `the watch's HELLO of firmware 2_4_103 sends CONFIG with the new key`() = runTest {
        handler.resetSession()
        // The HELLO as the firmware writes it (golden vector), not one the app encoded itself.
        val hello = handler.handleHello(helloFrame(GoldenVectors.bytes("hello_watch_2_4_103")))

        assertEquals(GoldenVectors.value("hello_features_word").toInt(), hello?.features)
        assertTrue(handler.peerSupports(Protocol.Features.CONFIG_KEY_V2))
        assertFalse(handler.watchPredatesConfigKey)
        assertTrue(handler.sendConfigPush(transport, 7, config))

        assertEquals(1, written.size)
        assertEquals(Protocol.Type.CONFIG_PUSH.wire, written.single().first)
        val payload = written.single().second
        assertArrayEquals(GoldenVectors.bytes("config_push_v2"), payload)
        assertTrue(String(payload, Charsets.ISO_8859_1).contains("infinassistant"))
    }

    @Test
    fun `a HELLO with bit 16 alone is enough`() = runTest {
        handler.resetSession()
        handler.handleHello(helloWithFeatures(Protocol.Features.CONFIG_KEY_V2))

        assertFalse(handler.watchPredatesConfigKey)
        assertTrue(handler.sendConfigPush(transport, 7, config))
        assertEquals(1, written.size)
    }

    @Test
    fun `before any HELLO the watch is not held back, and a new connection forgets the last HELLO`() = runTest {
        handler.resetSession()
        assertFalse(handler.watchPredatesConfigKey)

        handler.handleHello(helloWithFeatures(0xFBFF))
        assertTrue(handler.watchPredatesConfigKey)

        handler.resetSession()
        assertFalse(handler.watchPredatesConfigKey)
    }

    @Test
    fun `an updated watch clears the prompt on its next HELLO`() = runTest {
        handler.resetSession()
        handler.handleHello(helloWithFeatures(0xFBFF))
        assertTrue(handler.watchPredatesConfigKey)

        handler.resetSession()
        handler.handleHello(helloFrame(GoldenVectors.bytes("hello_watch_2_4_103")))
        assertFalse(handler.watchPredatesConfigKey)
    }

    @Test
    fun `a later good HELLO in the same connection reports the withheld push once`() = runTest {
        var gained = 0
        handler.onConfigKeySupportGained = { gained++ }

        // A new connection: the first HELLO is old, so the push is withheld. Not a flip.
        handler.resetSession()
        handler.handleHello(helloWithFeatures(0xFBFF))
        assertTrue(handler.watchPredatesConfigKey)
        assertEquals(0, gained)

        // Still old: no flip.
        handler.handleHello(helloWithFeatures(0xFBFF))
        assertEquals(0, gained)

        // A good HELLO later in the same connection: true to false, reported once.
        handler.handleHello(helloFrame(GoldenVectors.bytes("hello_watch_2_4_103")))
        assertFalse(handler.watchPredatesConfigKey)
        assertEquals(1, gained)

        // Good again: still false, not reported again.
        handler.handleHello(helloFrame(GoldenVectors.bytes("hello_watch_2_4_103")))
        assertEquals(1, gained)
    }

    @Test
    fun `a good first HELLO of a connection is no flip, even after an old one on the last`() = runTest {
        var gained = 0
        handler.onConfigKeySupportGained = { gained++ }

        handler.resetSession()
        handler.handleHello(helloWithFeatures(0xFBFF))
        assertTrue(handler.watchPredatesConfigKey)

        // The next connection starts clean: its good HELLO is the one the initial sync already pushes for.
        handler.resetSession()
        handler.handleHello(helloFrame(GoldenVectors.bytes("hello_watch_2_4_103")))
        assertFalse(handler.watchPredatesConfigKey)
        assertEquals(0, gained)
    }

    @Test
    fun `every HELLO seen reports the update flag, decoded or not`() = runTest {
        val seen = mutableListOf<Boolean>()
        handler.onHelloSeen = { seen += handler.watchPredatesConfigKey }

        // Empty, undecodable (a CBOR integer, not a map), and decoded without bit 16: each is a
        // HELLO seen, so the status flag is refreshed each time. None of them has bit 16.
        handler.resetSession()
        handler.handleHello(helloFrame(ByteArray(0)))
        handler.resetSession()
        handler.handleHello(helloFrame(byteArrayOf(0x01)))
        handler.resetSession()
        handler.handleHello(helloWithFeatures(0xFBFF))
        assertEquals(listOf(true, true, true), seen)

        // An updated watch's HELLO clears it.
        seen.clear()
        handler.resetSession()
        handler.handleHello(helloFrame(GoldenVectors.bytes("hello_watch_2_4_103")))
        assertEquals(listOf(false), seen)
    }

    @Test
    fun `the feature constants make the golden word, and the HELLO fits the frame`() {
        val declared = listOf(
            Protocol.Features.WATCHFACE_LAYOUT, Protocol.Features.LOGBOOK, Protocol.Features.HAPTIC_FEEDBACK,
            Protocol.Features.TIME_SYNC, Protocol.Features.VOICE_ASSIST, Protocol.Features.WATCHFACE_ACK,
            Protocol.Features.CALENDAR, Protocol.Features.HOME_CONTROL, Protocol.Features.PROTOCOL_NEGOTIATION,
            Protocol.Features.FIND_DEVICE, Protocol.Features.SHORT_KEYS, Protocol.Features.CRASH_REPORT,
            Protocol.Features.DASHBOARD_STATE, Protocol.Features.SIX_SCREENS, Protocol.Features.PHONE_NOTIFY_SYNC,
            Protocol.Features.CONFIG_KEY_V2,
        )
        // Every bit but the retired bit 10 (NOTIFY_DISMISS_SNOOZE), as the firmware's kSupportedFeatures.
        assertEquals(GoldenVectors.value("hello_features_word"), declared.fold(0) { acc, bit -> acc or bit }.toLong())
        assertEquals(GoldenVectors.value("config_key_v2_bit"), Protocol.Features.CONFIG_KEY_V2.toLong())
        assertEquals(0x10000, Protocol.Features.CONFIG_KEY_V2)
        // 244 bytes of payload at the 256-byte MTU; the watch has no chunked transmit.
        assertTrue(GoldenVectors.bytes("hello_watch_2_4_103").size <= 244)
    }

    @Test
    fun `the CONFIG the app writes is the golden vector, under the new key only`() {
        val bytes = ProtocolCbor.encode(ConfigPushPayload.serializer(), config)
        assertEquals(GoldenVectors.hex("config_push_v2"), bytes.joinToString(" ") { "%02X".format(it) })
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.contains("infinassistant"))
        assertEquals(config, ProtocolCbor.decode(ConfigPushPayload.serializer(), bytes))
    }
}
