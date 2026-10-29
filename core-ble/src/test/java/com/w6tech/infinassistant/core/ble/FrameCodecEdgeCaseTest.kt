package com.w6tech.infinassistant.core.ble

import com.w6tech.infinassistant.core.common.Clock
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FrameCodec edge-case tests covering:
 * - Out-of-order chunk delivery
 * - Duplicate chunk handling
 * - Timeout / expiry
 * - Single-chunk messages
 * - Boundary sizes (0 payload, max MTU)
 * - Malformed headers (short frames, bad version, chunkIndex >= chunkCount)
 * - Header mismatch on same msgId (restarts assembly)
 * - Interleaved messages with different msgIds
 */
class FrameCodecEdgeCaseTest {

    // ── Controllable clock for timeout tests ──

    private class FakeClock(var now: Long = 0) : Clock {
        override fun nowEpochMillis(): Long = now
    }

    // ── Helpers ──

    /** Build a raw frame ByteArray with the given header fields and payload chunk. */
    private fun buildFrame(
        version: Int = FrameCodec.VERSION,
        type: Int = 0x04,
        flags: Int = 0,
        msgId: Int = 1,
        totalLen: Int,
        chunkIndex: Int,
        chunkCount: Int,
        chunkPayload: ByteArray,
    ): ByteArray {
        val frame = ByteArray(FrameCodec.HEADER_LEN + chunkPayload.size)
        val bb = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(version.toByte())
        bb.put(type.toByte())
        bb.put(flags.toByte())
        bb.putShort((msgId and 0xFFFF).toShort())
        bb.putShort((totalLen and 0xFFFF).toShort())
        bb.put(chunkIndex.toByte())
        bb.put(chunkCount.toByte())
        bb.put(chunkPayload)
        return frame
    }

    // ── Single-chunk messages ──

    @Test
    fun `single chunk message reassembles immediately`() {
        val payload = byteArrayOf(0x01, 0x02, 0x03)
        val frames = FrameCodec.chunk(type = 0x04, flags = 0, msgId = 1, payload = payload, maxFrameBytes = 64)
        assertEquals(1, frames.size)

        val re = FrameCodec.Reassembler(timeoutMs = 5000)
        val result = re.ingest(frames[0])
        assertNotNull(result)
        assertEquals(0x04, result!!.type)
        assertEquals(1, result.msgId)
        assertArrayEquals(payload, result.payload)
    }

    @Test
    fun `empty payload single chunk`() {
        val payload = ByteArray(0)
        val frames = FrameCodec.chunk(type = 0x01, flags = 0, msgId = 42, payload = payload, maxFrameBytes = 64)
        assertEquals(1, frames.size)

        val re = FrameCodec.Reassembler(timeoutMs = 5000)
        val result = re.ingest(frames[0])
        assertNotNull(result)
        assertEquals(0, result!!.payload.size)
    }

    // ── Out-of-order chunks ──

    @Test
    fun `out-of-order chunks reassemble correctly`() {
        val payload = ByteArray(100) { (it % 251).toByte() }
        // Max 20 bytes per frame → 20 - 9 = 11 bytes per chunk → 10 chunks
        val frames = FrameCodec.chunk(type = 0x04, flags = 0, msgId = 5, payload = payload, maxFrameBytes = 20)
        assertTrue(frames.size > 1)

        // Reverse order
        val reversed = frames.reversed()
        val re = FrameCodec.Reassembler(timeoutMs = 60_000)
        var result: FrameCodec.CompleteMessage? = null
        for (f in reversed) {
            result = re.ingest(f) ?: result
        }
        assertNotNull(result)
        assertArrayEquals(payload, result!!.payload)
    }

    @Test
    fun `random order chunks reassemble correctly`() {
        val payload = ByteArray(200) { (it xor 0xAB).toByte() }
        val frames = FrameCodec.chunk(type = 0x08, flags = 0, msgId = 7, payload = payload, maxFrameBytes = 30)
        assertTrue(frames.size > 3)

        // Shuffle with deterministic seed
        val shuffled = frames.toMutableList()
        shuffled.shuffle(java.util.Random(12345))

        val re = FrameCodec.Reassembler(timeoutMs = 60_000)
        var result: FrameCodec.CompleteMessage? = null
        for (f in shuffled) {
            result = re.ingest(f) ?: result
        }
        assertNotNull(result)
        assertArrayEquals(payload, result!!.payload)
    }

    // ── Duplicate chunks ──

    @Test
    fun `duplicate chunk is ignored and message still completes`() {
        val payload = ByteArray(50) { it.toByte() }
        val frames = FrameCodec.chunk(type = 0x04, flags = 0, msgId = 3, payload = payload, maxFrameBytes = 20)
        assertTrue(frames.size > 1)

        val re = FrameCodec.Reassembler(timeoutMs = 60_000)
        var result: FrameCodec.CompleteMessage? = null
        // Send first chunk twice, then the rest
        re.ingest(frames[0])
        re.ingest(frames[0]) // duplicate
        for (f in frames.drop(1)) {
            result = re.ingest(f) ?: result
        }
        assertNotNull(result)
        assertArrayEquals(payload, result!!.payload)
    }

    // ── Timeout / expiry ──

    @Test
    fun `incomplete message expires after timeout`() {
        val clock = FakeClock(now = 1000)
        val re = FrameCodec.Reassembler(clock = clock, timeoutMs = 5000)

        val payload = ByteArray(100) { it.toByte() }
        val frames = FrameCodec.chunk(type = 0x04, flags = 0, msgId = 10, payload = payload, maxFrameBytes = 20)
        assertTrue(frames.size > 2)

        // Ingest first chunk only
        re.ingest(frames[0])

        // Advance clock past timeout
        clock.now = 7000

        // Ingest a new message to trigger purge
        val newPayload = byteArrayOf(0xAA.toByte())
        val newFrames = FrameCodec.chunk(type = 0x01, flags = 0, msgId = 11, payload = newPayload, maxFrameBytes = 64)
        val result = re.ingest(newFrames[0])

        // New message should complete
        assertNotNull(result)
        assertEquals(11, result!!.msgId)

        // Now send remaining chunks of old message - should NOT complete (was purged)
        var oldResult: FrameCodec.CompleteMessage? = null
        for (f in frames.drop(1)) {
            oldResult = re.ingest(f) ?: oldResult
        }
        // Old message was purged, so sending remaining chunks starts a new assembly
        // which won't complete because it's missing chunk 0
        assertNull(oldResult)
    }

    // ── Malformed frames ──

    @Test
    fun `frame shorter than header is rejected`() {
        val re = FrameCodec.Reassembler(timeoutMs = 5000)
        val result = re.ingest(ByteArray(8)) // 8 < HEADER_LEN (9)
        assertNull(result)
    }

    @Test
    fun `frame with bad version is rejected`() {
        val frame = buildFrame(
            version = 99,
            totalLen = 3,
            chunkIndex = 0,
            chunkCount = 1,
            chunkPayload = byteArrayOf(1, 2, 3),
        )
        val re = FrameCodec.Reassembler(timeoutMs = 5000)
        assertNull(re.ingest(frame))
    }

    @Test
    fun `chunkIndex greater than or equal to chunkCount is rejected`() {
        val re = FrameCodec.Reassembler(timeoutMs = 5000)

        // chunkIndex == chunkCount
        val f1 = buildFrame(totalLen = 3, chunkIndex = 2, chunkCount = 2, chunkPayload = byteArrayOf(1, 2, 3))
        assertNull(re.ingest(f1))

        // chunkIndex > chunkCount
        val f2 = buildFrame(totalLen = 3, chunkIndex = 5, chunkCount = 2, chunkPayload = byteArrayOf(1, 2, 3))
        assertNull(re.ingest(f2))
    }

    @Test
    fun `chunkCount of zero is rejected`() {
        val frame = buildFrame(totalLen = 3, chunkIndex = 0, chunkCount = 0, chunkPayload = byteArrayOf(1, 2, 3))
        val re = FrameCodec.Reassembler(timeoutMs = 5000)
        assertNull(re.ingest(frame))
    }

    // ── Header mismatch on same msgId ──

    @Test
    fun `header mismatch on same msgId drops and restarts`() {
        val re = FrameCodec.Reassembler(timeoutMs = 60_000)

        // Send chunk 0 of a 2-chunk message with totalLen=20
        val frame1 = buildFrame(
            msgId = 50,
            totalLen = 20,
            chunkIndex = 0,
            chunkCount = 2,
            chunkPayload = ByteArray(10) { 0xAA.toByte() },
        )
        assertNull(re.ingest(frame1))

        // Send chunk 1 with DIFFERENT totalLen → mismatch → drop
        val frame2 = buildFrame(
            msgId = 50,
            totalLen = 30, // mismatch!
            chunkIndex = 1,
            chunkCount = 2,
            chunkPayload = ByteArray(10) { 0xBB.toByte() },
        )
        assertNull(re.ingest(frame2))
    }

    // ── Interleaved messages ──

    @Test
    fun `interleaved messages with different msgIds both complete`() {
        val payloadA = ByteArray(30) { 0xAA.toByte() }
        val payloadB = ByteArray(30) { 0xBB.toByte() }

        val framesA = FrameCodec.chunk(type = 0x04, flags = 0, msgId = 100, payload = payloadA, maxFrameBytes = 20)
        val framesB = FrameCodec.chunk(type = 0x08, flags = 0, msgId = 200, payload = payloadB, maxFrameBytes = 20)
        assertTrue(framesA.size > 1)
        assertTrue(framesB.size > 1)

        val re = FrameCodec.Reassembler(timeoutMs = 60_000)
        val results = mutableListOf<FrameCodec.CompleteMessage>()

        // Interleave: A[0], B[0], A[1], B[1], A[2], B[2], ...
        val maxLen = maxOf(framesA.size, framesB.size)
        for (i in 0 until maxLen) {
            if (i < framesA.size) re.ingest(framesA[i])?.let { results.add(it) }
            if (i < framesB.size) re.ingest(framesB[i])?.let { results.add(it) }
        }

        assertEquals(2, results.size)
        val msgA = results.first { it.msgId == 100 }
        val msgB = results.first { it.msgId == 200 }
        assertArrayEquals(payloadA, msgA.payload)
        assertArrayEquals(payloadB, msgB.payload)
    }

    // ── Boundary: payload exactly fills one chunk ──

    @Test
    fun `payload exactly fills one chunk`() {
        // maxFrameBytes = 20, header = 9, so chunk data = 11 bytes
        val payload = ByteArray(11) { it.toByte() }
        val frames = FrameCodec.chunk(type = 0x06, flags = 0, msgId = 99, payload = payload, maxFrameBytes = 20)
        assertEquals(1, frames.size)

        val re = FrameCodec.Reassembler(timeoutMs = 5000)
        val result = re.ingest(frames[0])
        assertNotNull(result)
        assertArrayEquals(payload, result!!.payload)
    }

    @Test
    fun `payload one byte over chunk boundary creates two frames`() {
        // 12 bytes > 11 chunk capacity → 2 frames
        val payload = ByteArray(12) { it.toByte() }
        val frames = FrameCodec.chunk(type = 0x06, flags = 0, msgId = 99, payload = payload, maxFrameBytes = 20)
        assertEquals(2, frames.size)

        val re = FrameCodec.Reassembler(timeoutMs = 5000)
        var result: FrameCodec.CompleteMessage? = null
        for (f in frames) {
            result = re.ingest(f) ?: result
        }
        assertNotNull(result)
        assertArrayEquals(payload, result!!.payload)
    }

    // ── Chunk overflow protection ──

    @Test
    fun `total chunk data exceeding totalLen is rejected`() {
        val re = FrameCodec.Reassembler(timeoutMs = 60_000)

        // Claim totalLen=5 but provide 10 bytes of chunk data across 2 chunks
        val f1 = buildFrame(
            msgId = 77,
            totalLen = 5,
            chunkIndex = 0,
            chunkCount = 2,
            chunkPayload = ByteArray(5) { 0x11 },
        )
        val f2 = buildFrame(
            msgId = 77,
            totalLen = 5,
            chunkIndex = 1,
            chunkCount = 2,
            chunkPayload = ByteArray(5) { 0x22 }, // Total = 10 > totalLen of 5
        )
        assertNull(re.ingest(f1))
        val result = re.ingest(f2)
        // Should be rejected due to overflow protection
        assertNull(result)
    }

    // ── msgId unsigned range ──

    @Test
    fun `msgId 0xFFFF round-trips correctly`() {
        val payload = byteArrayOf(0x42)
        val frames = FrameCodec.chunk(type = 0x01, flags = 0, msgId = 0xFFFF, payload = payload, maxFrameBytes = 64)
        val re = FrameCodec.Reassembler(timeoutMs = 5000)
        val result = re.ingest(frames[0])
        assertNotNull(result)
        assertEquals(0xFFFF, result!!.msgId)
    }

    @Test
    fun `msgId 0 round-trips correctly`() {
        val payload = byteArrayOf(0x42)
        val frames = FrameCodec.chunk(type = 0x01, flags = 0, msgId = 0, payload = payload, maxFrameBytes = 64)
        val re = FrameCodec.Reassembler(timeoutMs = 5000)
        val result = re.ingest(frames[0])
        assertNotNull(result)
        assertEquals(0, result!!.msgId)
    }

    // ── Large payload ──

    @Test
    fun `large payload 4096 bytes round-trips`() {
        val payload = ByteArray(4096) { (it % 256).toByte() }
        val frames = FrameCodec.chunk(type = 0x0E, flags = 0, msgId = 500, payload = payload, maxFrameBytes = 185)
        assertTrue(frames.size > 20) // 4096 / (185-9) = ~24 chunks

        val re = FrameCodec.Reassembler(timeoutMs = 60_000)
        var result: FrameCodec.CompleteMessage? = null
        for (f in frames) {
            result = re.ingest(f) ?: result
        }
        assertNotNull(result)
        assertArrayEquals(payload, result!!.payload)
    }

    // ── Missing middle chunk ──

    @Test
    fun `missing middle chunk prevents completion`() {
        val payload = ByteArray(100) { it.toByte() }
        val frames = FrameCodec.chunk(type = 0x04, flags = 0, msgId = 20, payload = payload, maxFrameBytes = 20)
        assertTrue(frames.size >= 4)

        val re = FrameCodec.Reassembler(timeoutMs = 60_000)
        var result: FrameCodec.CompleteMessage? = null

        // Send all except chunk at index 2
        for ((idx, f) in frames.withIndex()) {
            if (idx == 2) continue
            result = re.ingest(f) ?: result
        }
        assertNull(result) // Can't complete without chunk 2
    }

    // ── Flags preserved ──

    @Test
    fun `flags field is preserved through round-trip`() {
        val payload = byteArrayOf(0x01)
        val frames = FrameCodec.chunk(type = 0x04, flags = 0x55, msgId = 1, payload = payload, maxFrameBytes = 64)
        val re = FrameCodec.Reassembler(timeoutMs = 5000)
        val result = re.ingest(frames[0])
        assertNotNull(result)
        assertEquals(0x55, result!!.flags)
    }
}
