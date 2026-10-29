package com.w6tech.infinassistant.core.data.bridge

import com.w6tech.infinassistant.core.ble.BleTransport
import com.w6tech.infinassistant.core.ble.FrameCodec
import com.w6tech.infinassistant.core.common.AppResult
import com.w6tech.infinassistant.core.domain.HelloPayload
import com.w6tech.infinassistant.core.domain.NotifyDismissPayload
import com.w6tech.infinassistant.core.domain.NotifyPayload
import com.w6tech.infinassistant.core.domain.NotifyRemovePayload
import com.w6tech.infinassistant.core.domain.PongPayload
import com.w6tech.infinassistant.core.domain.Protocol
import com.w6tech.infinassistant.core.domain.ProtocolCbor
import com.w6tech.infinassistant.core.ha.HaWebSocketClient
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Section 2 of the phone notification plan, as the app puts it on the wire and reads it off. */
class PhoneNotifyWireTest {

    private val handler = ProtocolHandler(WireTestSupport.silentLogger, StateManager()) { _, _, _, _ -> }

    @Test
    fun `the wire values are the contract's`() {
        assertEquals(0x8000, Protocol.Features.PHONE_NOTIFY_SYNC)
        assertEquals(0x1E, Protocol.Type.NOTIFY_DISMISS.wire)
        assertEquals(0x21, Protocol.Type.NOTIFY_REMOVE.wire)
        // Bit 10 is the retired REQ-071 meaning; the new feature must not share it.
        assertEquals(0, Protocol.Features.PHONE_NOTIFY_SYNC and Protocol.Features.NOTIFY_DISMISS_SNOOZE)
    }

    @Test
    fun `a NOTIFY with a nid carries it, and one without carries no such key`() {
        val phone = ProtocolCbor.encode(
            NotifyPayload.serializer(),
            NotifyPayload(title = "Alice", body = "Hi", tag = "7", ts = 1, nid = 7),
        )
        val ha = ProtocolCbor.encode(NotifyPayload.serializer(), NotifyPayload(title = "Door", body = "Open", tag = "door", ts = 1))
        assertEquals(7, ProtocolCbor.decode(NotifyPayload.serializer(), phone).nid)
        assertTrue(String(phone, Charsets.ISO_8859_1).contains("nid"))
        assertFalse(String(ha, Charsets.ISO_8859_1).contains("nid"))
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(it) }

    private fun bytesOf(hex: String) = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    // The exact bytes. The firmware's host tests feed the same ones to its decoders. The app's CBOR
    // encoder writes maps with an indefinite length (BF ... FF), so the decoders must take that form.

    @Test
    fun `NOTIFY_REMOVE for nid 7 is these bytes`() {
        assertEquals(
            GoldenVectors.hex("notify_remove_nid7"),
            hex(ProtocolCbor.encode(NotifyRemovePayload.serializer(), NotifyRemovePayload(nid = 7))),
        )
        assertEquals(
            GoldenVectors.hex("notify_remove_nid300"),
            hex(ProtocolCbor.encode(NotifyRemovePayload.serializer(), NotifyRemovePayload(nid = 300))),
        )
    }

    @Test
    fun `a NOTIFY with a nid is these bytes`() {
        // {"title": "Al", "body": "Hi", "tag": "7", "ts": 1, "nid": 7}; severity INFO is the default and left out.
        val notify = NotifyPayload(title = "Al", body = "Hi", tag = "7", ts = 1, nid = 7)
        assertEquals(GoldenVectors.hex("notify_phone_nid7"), hex(ProtocolCbor.encode(NotifyPayload.serializer(), notify)))
    }

    @Test
    fun `a NOTIFY without a nid is the same bytes minus the key`() {
        val notify = NotifyPayload(title = "Al", body = "Hi", tag = "7", ts = 1)
        assertEquals(
            "BF 65 74 69 74 6C 65 62 41 6C 64 62 6F 64 79 62 48 69 63 74 61 67 61 37 62 74 73 01 FF",
            hex(ProtocolCbor.encode(NotifyPayload.serializer(), notify)),
        )
    }

    @Test
    fun `the golden NOTIFY_DISMISS vectors decode to their nids`() {
        for ((name, nid) in listOf("notify_dismiss_nid7" to 7, "notify_dismiss_nid42" to 42, "notify_dismiss_nid65535" to 65535)) {
            assertEquals(name, NotifyDismissPayload(nid = nid), ProtocolCbor.decode(NotifyDismissPayload.serializer(), GoldenVectors.bytes(name)))
        }
        val decoded = ProtocolCbor.decode(NotifyDismissPayload.serializer(), GoldenVectors.bytes("notify_dismiss_nid42"))
        assertEquals(NotifyDismissPayload(nid = 42), decoded)
        // The same map with an indefinite length.
        val indefinite = ProtocolCbor.decode(NotifyDismissPayload.serializer(), bytesOf("BF 63 6E 69 64 18 2A FF"))
        assertEquals(NotifyDismissPayload(nid = 42), indefinite)
    }

    // ── enc (FR-18) ──

    @Test
    fun `HELLO and PONG report enc, and absent means unknown`() {
        val hello = ProtocolCbor.encode(HelloPayload.serializer(), HelloPayload(device = "watch", enc = true))
        assertEquals(true, ProtocolCbor.decode(HelloPayload.serializer(), hello).enc)
        val bare = ProtocolCbor.encode(HelloPayload.serializer(), HelloPayload(device = "watch"))
        assertNull(ProtocolCbor.decode(HelloPayload.serializer(), bare).enc)

        // {"rid": 5, "enc": false} and {"rid": 5, "enc": true}
        assertEquals(PongPayload(requestMsgId = 5, enc = false), ProtocolCbor.decode(PongPayload.serializer(), GoldenVectors.bytes("pong_rid5_enc_false")))
        assertEquals(PongPayload(requestMsgId = 5, enc = true), ProtocolCbor.decode(PongPayload.serializer(), GoldenVectors.bytes("pong_rid5_enc_true")))
    }

    // ── The watch's HELLO as the firmware writes it (BridgeController, 15 entries, definite length) ──

    private fun cborText(s: String): ByteArray = byteArrayOf((0x60 + s.length).toByte()) + s.toByteArray(Charsets.US_ASCII)

    /** The shortest unsigned integer, as TinyCBOR writes it. */
    private fun cborUint(v: Long): ByteArray = when {
        v < 24 -> byteArrayOf(v.toByte())
        v < 0x100 -> byteArrayOf(0x18, v.toByte())
        v < 0x10000 -> byteArrayOf(0x19, (v shr 8).toByte(), v.toByte())
        else -> byteArrayOf(0x1A, (v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    }

    private fun firmwareHello(enc: Boolean): ByteArray {
        var out = byteArrayOf(0xAF.toByte())
        fun entry(key: String, value: ByteArray) { out += cborText(key) + value }
        entry("device", cborText("watch"))
        entry("features", cborUint(0xBFFF))
        entry("fwVersion", cborText("2.4.101"))
        entry("bridgeProtocolRev", cborUint(2))
        entry("helloDiagRev", cborUint(4))
        entry("freeHeap", cborUint(31234))
        entry("minFreeHeap", cborUint(20100))
        entry("mallocFails", cborUint(0))
        entry("stackOverflows", cborUint(0))
        entry("uptimeTicks", cborUint(123456))
        entry("lvCreateFailures", cborUint(0))
        entry("safeFaceTrips", cborUint(1))
        entry("layoutRefused", cborUint(2))
        entry("layoutWriteFailed", cborUint(3))
        entry("enc", byteArrayOf(if (enc) 0xF5.toByte() else 0xF4.toByte()))
        return out
    }

    @Test
    fun `the firmware's definite length HELLO decodes with enc true and false`() {
        for (enc in listOf(true, false)) {
            val bytes = firmwareHello(enc)
            assertEquals("AF 66 64 65 76 69 63 65 65 77 61 74 63 68", hex(bytes.copyOfRange(0, 14)))
            assertEquals(if (enc) "63 65 6E 63 F5" else "63 65 6E 63 F4", hex(bytes.copyOfRange(bytes.size - 5, bytes.size)))

            val hello = ProtocolCbor.decode(HelloPayload.serializer(), bytes)

            assertEquals(enc, hello.enc)
            assertEquals("watch", hello.device)
            assertEquals(0xBFFF, hello.features)
            assertEquals("2.4.101", hello.fwVersion)
            assertEquals(4, hello.helloDiagRev)
            assertEquals(31234L, hello.freeHeap)
            assertEquals(123456L, hello.uptimeTicks)
            assertEquals(1, hello.safeFaceTrips)
            assertEquals(2L, hello.layoutRefused)
            assertEquals(3L, hello.layoutWriteFailed)
        }
    }

    @Test
    fun `the handler takes enc from the firmware's HELLO bytes`() {
        handler.handleHello(helloFrame(firmwareHello(enc = false)))
        assertEquals(false, handler.peerEnc)
        assertTrue(handler.peerSupports(Protocol.Features.PHONE_NOTIFY_SYNC))
        handler.handleHello(helloFrame(firmwareHello(enc = true)))
        assertEquals(true, handler.peerEnc)
    }

    private fun helloFrame(payload: ByteArray) =
        FrameCodec.CompleteMessage(FrameCodec.VERSION, Protocol.Type.HELLO.wire, 0, 1, payload)

    @Test
    fun `the handler keeps the watch's enc from its HELLO and its PONGs`() = runTest {
        assertNull(handler.peerEnc)
        handler.handleHello(helloFrame(ProtocolCbor.encode(HelloPayload.serializer(), HelloPayload(device = "watch", enc = false))))
        assertEquals(false, handler.peerEnc)

        val pong = FrameCodec.CompleteMessage(
            FrameCodec.VERSION, Protocol.Type.PONG.wire, 0, 2,
            ProtocolCbor.encode(PongPayload.serializer(), PongPayload(requestMsgId = 3, enc = true)),
        )
        handler.handleWatchMessage(
            m = pong,
            ha = mockk<HaWebSocketClient>(relaxed = true),
            transport = mockk<BleTransport>(relaxed = true),
            sessionScope = CoroutineScope(Dispatchers.Unconfined),
            onVoiceAssistInvoke = {},
            onSensorReport = {},
        )
        assertEquals(true, handler.peerEnc)

        // A new HELLO replaces what the last one said.
        handler.handleHello(helloFrame(ProtocolCbor.encode(HelloPayload.serializer(), HelloPayload(device = "watch"))))
        assertNull(handler.peerEnc)
    }

    @Test
    fun `a new session forgets the last HELLO`() {
        handler.handleHello(
            helloFrame(
                ProtocolCbor.encode(
                    HelloPayload.serializer(),
                    HelloPayload(device = "watch", features = Protocol.Features.PHONE_NOTIFY_SYNC, enc = true),
                ),
            ),
        )
        assertTrue(handler.helloSeen)
        assertTrue(handler.peerSupports(Protocol.Features.PHONE_NOTIFY_SYNC))

        handler.resetSession()

        assertFalse(handler.helloSeen)
        assertFalse(handler.peerSupports(Protocol.Features.PHONE_NOTIFY_SYNC))
        assertNull(handler.peerEnc)
    }

    @Test
    fun `an empty HELLO from old firmware still counts as a HELLO`() {
        handler.handleHello(helloFrame(ByteArray(0)))
        assertTrue(handler.helloSeen)
    }

    @Test
    fun `NOTIFY_REMOVE goes out with the nid as its payload`() = runTest {
        val sent = mutableListOf<Pair<Int, ByteArray>>()
        val transport: BleTransport = mockk()
        coEvery { transport.send(any(), any(), any(), any(), any()) } coAnswers {
            sent += firstArg<Int>() to arg<ByteArray>(3)
            AppResult.Ok(Unit)
        }

        handler.sendToWatch(transport, Protocol.Type.NOTIFY_REMOVE, 55, NotifyRemovePayload(nid = 300))

        assertEquals(1, sent.size)
        assertEquals(0x21, sent[0].first)
        assertEquals(300, ProtocolCbor.decode(NotifyRemovePayload.serializer(), sent[0].second).nid)
    }

    private fun dismissFrame(payload: ByteArray) =
        FrameCodec.CompleteMessage(FrameCodec.VERSION, Protocol.Type.NOTIFY_DISMISS.wire, 0, 9, payload)

    private suspend fun dispatch(m: FrameCodec.CompleteMessage, onDismiss: (Int) -> Unit) {
        handler.handleWatchMessage(
            m = m,
            ha = mockk<HaWebSocketClient>(relaxed = true),
            transport = mockk<BleTransport>(relaxed = true),
            sessionScope = CoroutineScope(Dispatchers.Unconfined),
            onVoiceAssistInvoke = {},
            onSensorReport = {},
            onNotifyDismiss = onDismiss,
        )
    }

    private fun advertiseSync() {
        handler.handleHello(
            helloFrame(
                ProtocolCbor.encode(
                    HelloPayload.serializer(),
                    HelloPayload(device = "watch", features = Protocol.Features.PHONE_NOTIFY_SYNC),
                ),
            ),
        )
    }

    @Test
    fun `NOTIFY_DISMISS is dropped from a watch that did not advertise the sync feature`() = runTest {
        val got = mutableListOf<Int>()
        dispatch(dismissFrame(GoldenVectors.bytes("notify_dismiss_nid42"))) { got += it }
        assertTrue(got.isEmpty())

        // The retired REQ-071 feature bit is not the new one.
        handler.handleHello(
            helloFrame(
                ProtocolCbor.encode(
                    HelloPayload.serializer(),
                    HelloPayload(device = "watch", features = Protocol.Features.NOTIFY_DISMISS_SNOOZE),
                ),
            ),
        )
        dispatch(dismissFrame(GoldenVectors.bytes("notify_dismiss_nid42"))) { got += it }
        assertTrue(got.isEmpty())
    }

    @Test
    fun `NOTIFY_DISMISS from the watch reaches the dismiss callback with its nid`() = runTest {
        advertiseSync()
        val got = mutableListOf<Int>()
        dispatch(dismissFrame(ProtocolCbor.encode(NotifyDismissPayload.serializer(), NotifyDismissPayload(nid = 42)))) { got += it }
        assertEquals(listOf(42), got)
    }

    @Test
    fun `a NOTIFY_DISMISS that does not decode is dropped`() = runTest {
        advertiseSync()
        val got = mutableListOf<Int>()
        dispatch(dismissFrame(byteArrayOf(0x01, 0x02))) { got += it }
        assertTrue(got.isEmpty())
    }
}
