package com.w6tech.infinassistant.core.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameCodecTest {
    @Test
    fun chunkAndReassemble_roundTrip() {
        val payload = ByteArray(512) { (it % 251).toByte() }
        val frames = FrameCodec.chunk(type = 0x08, flags = 0, msgId = 123, payload = payload, maxFrameBytes = 64)
        assertEquals(true, frames.size > 1)

        val re = FrameCodec.Reassembler(timeoutMs = 60_000)
        var out: FrameCodec.CompleteMessage? = null
        for (f in frames) {
            out = re.ingest(f) ?: out
        }
        requireNotNull(out)
        assertEquals(0x08, out!!.type)
        assertEquals(123, out!!.msgId)
        assertArrayEquals(payload, out!!.payload)
    }
}
