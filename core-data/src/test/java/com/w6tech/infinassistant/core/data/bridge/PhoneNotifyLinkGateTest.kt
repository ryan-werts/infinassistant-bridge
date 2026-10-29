package com.w6tech.infinassistant.core.data.bridge

import com.w6tech.infinassistant.core.ble.BleTransport
import com.w6tech.infinassistant.core.ble.FrameCodec
import com.w6tech.infinassistant.core.common.AppResult
import com.w6tech.infinassistant.core.domain.HelloPayload
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine's pieces for a phone forward, driven as the engine drives them: the watch's HELLO and
 * PONG through [ProtocolHandler], the link through [WatchLinkTracker], the queue through
 * [PhoneNotifySync], and the bytes through a transport that records what is written. BridgeEngine
 * itself needs a Context, Bluetooth and a GATT link, so it is not driven here.
 */
class PhoneNotifyLinkGateTest {

    private val handler = ProtocolHandler(WireTestSupport.silentLogger, StateManager()) { _, _, _, _ -> }
    private val state = StateManager()
    private val tracker = WatchLinkTracker()
    private var msgId = 100
    private val sync = PhoneNotifySync(PhoneNotificationRegistry(), state, { ++msgId })
    private val written = mutableListOf<Int>()
    private val transport: BleTransport = mockk<BleTransport>().also { t ->
        coEvery { t.send(any(), any(), any(), any(), any()) } coAnswers {
            written += firstArg<Int>()
            AppResult.Ok(Unit)
        }
    }

    private fun link(bonded: Boolean = true) = tracker.update(bonded, handler.helloSeen, handler.peerEnc)

    private fun hello(enc: Boolean?, features: Int = Protocol.Features.PHONE_NOTIFY_SYNC) {
        val payload = ProtocolCbor.encode(HelloPayload.serializer(), HelloPayload(device = "watch", features = features, enc = enc))
        handler.handleHello(FrameCodec.CompleteMessage(FrameCodec.VERSION, Protocol.Type.HELLO.wire, 0, 1, payload))
    }

    private suspend fun pong(enc: Boolean) {
        val payload = ProtocolCbor.encode(PongPayload.serializer(), PongPayload(requestMsgId = 3, enc = enc))
        handler.handleWatchMessage(
            m = FrameCodec.CompleteMessage(FrameCodec.VERSION, Protocol.Type.PONG.wire, 0, 2, payload),
            ha = mockk<HaWebSocketClient>(relaxed = true),
            transport = mockk<BleTransport>(relaxed = true),
            sessionScope = CoroutineScope(Dispatchers.Unconfined),
            onVoiceAssistInvoke = {},
            onSensorReport = {},
        )
    }

    private fun snap(key: String) = PhoneNotificationSnapshot(
        key = key, packageName = "com.chat", appLabel = "Chat", flags = 0, importance = 4,
        matchesInterruptionFilter = true, title = "Alice", text = "hi",
    )

    private suspend fun flush() = sync.flushForwards(link().allowsPhoneNotifications) { mid, payload ->
        handler.sendToWatch(transport, Protocol.Type.NOTIFY, mid, payload)
    }

    private fun post(key: String) = sync.onPosted(snap(key), true, link())

    @Test
    fun `a HELLO with enc false leads to no NOTIFY, and the PONG that says true releases it`() = runTest {
        handler.resetSession()
        hello(enc = false)

        assertTrue(post("k1") is PhoneNotifySync.Posted.Forwarded)
        flush()
        assertTrue("nothing is written while the link is unencrypted: $written", written.isEmpty())

        pong(enc = true)
        flush()
        assertEquals(listOf(Protocol.Type.NOTIFY.wire), written)
    }

    @Test
    fun `before this session's HELLO nothing is sent, and the HELLO decides`() = runTest {
        handler.resetSession()
        assertTrue(post("k1") is PhoneNotifySync.Posted.Forwarded)
        flush()
        assertTrue(written.isEmpty())

        hello(enc = null) // firmware 2.4.100: the bond alone
        flush()
        assertEquals(listOf(Protocol.Type.NOTIFY.wire), written)
    }

    @Test
    fun `a new session holds the forwards again until its own HELLO`() = runTest {
        handler.resetSession()
        hello(enc = true)
        post("k1")
        flush()
        assertEquals(1, written.size)

        handler.resetSession() // reconnect: the last HELLO's enc no longer counts
        post("k2")
        flush()
        assertEquals(1, written.size)
        hello(enc = true)
        flush()
        assertEquals(2, written.size)
    }

    @Test
    fun `an unpaired watch gets nothing and nothing is queued`() = runTest {
        handler.resetSession()
        hello(enc = true)
        assertEquals(
            PhoneNotifySync.Posted.Skipped("link-not-encrypted"),
            sync.onPosted(snap("k1"), true, link(bonded = false)),
        )
        assertTrue(state.getAllPendingNotifications().isEmpty())
    }
}
