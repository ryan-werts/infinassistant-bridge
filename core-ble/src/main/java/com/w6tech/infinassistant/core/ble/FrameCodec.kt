package com.w6tech.infinassistant.core.ble

import com.w6tech.infinassistant.core.common.Clock
import com.w6tech.infinassistant.core.common.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * BLE framing: [version:1][type:1][flags:1][msgId:2][totalLen:2][chunkIndex:1][chunkCount:1] (9 bytes)
 */
object FrameCodec {
    const val HEADER_LEN: Int = 9
    const val VERSION: Int = 1

    data class CompleteMessage(
        val version: Int,
        val type: Int,
        val flags: Int,
        val msgId: Int,
        val payload: ByteArray,
    )

    fun chunk(
        type: Int,
        flags: Int,
        msgId: Int,
        payload: ByteArray,
        maxFrameBytes: Int,
    ): List<ByteArray> {
        require(maxFrameBytes > HEADER_LEN) { "maxFrameBytes must be > $HEADER_LEN" }
        val chunkPayloadSize = maxFrameBytes - HEADER_LEN
        val totalLen = payload.size
        val chunkCount = ((totalLen + chunkPayloadSize - 1) / chunkPayloadSize).coerceAtLeast(1)
        val out = ArrayList<ByteArray>(chunkCount)
        for (i in 0 until chunkCount) {
            val start = i * chunkPayloadSize
            val end = minOf(totalLen, start + chunkPayloadSize)
            val slice = payload.copyOfRange(start, end)
            val frame = ByteArray(HEADER_LEN + slice.size)
            val bb = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
            bb.put(VERSION.toByte())
            bb.put(type.toByte())
            bb.put(flags.toByte())
            // Use bitwise AND to safely convert to unsigned 16-bit value stored as Short
            bb.putShort((msgId and 0xFFFF).toShort())
            bb.putShort((totalLen and 0xFFFF).toShort())
            bb.put(i.toByte())
            bb.put(chunkCount.toByte())
            bb.put(slice)
            out.add(frame)
        }
        return out
    }

    class Reassembler(
        private val clock: Clock = SystemClock,
        private val timeoutMs: Long = 5_000,
    ) {
        private data class InFlight(
            val type: Int,
            val flags: Int,
            val totalLen: Int,
            val chunkCount: Int,
            val startedMs: Long,
            val chunks: Array<ByteArray?>,
            var received: Int = 0,
        )

        private val inflight = LinkedHashMap<Int, InFlight>()

        @Synchronized
        fun ingest(frame: ByteArray): CompleteMessage? {
            purgeExpiredLocked()
            if (frame.size < HEADER_LEN) return null
            val bb = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
            val version = bb.get().toInt() and 0xFF
            val type = bb.get().toInt() and 0xFF
            val flags = bb.get().toInt() and 0xFF
            val msgId = bb.short.toInt() and 0xFFFF
            val totalLen = bb.short.toInt() and 0xFFFF
            val chunkIndex = bb.get().toInt() and 0xFF
            val chunkCount = bb.get().toInt() and 0xFF

            if (version != VERSION) return null
            if (chunkCount <= 0 || chunkIndex >= chunkCount) return null
            val payloadChunk = ByteArray(frame.size - HEADER_LEN)
            bb.get(payloadChunk)

            val now = clock.nowEpochMillis()
            val entry = inflight.getOrPut(msgId) {
                InFlight(
                    type = type,
                    flags = flags,
                    totalLen = totalLen,
                    chunkCount = chunkCount,
                    startedMs = now,
                    chunks = arrayOfNulls(chunkCount),
                )
            }

            // If header mismatches for same msgId, drop and restart.
            if (entry.totalLen != totalLen || entry.chunkCount != chunkCount || entry.type != type) {
                inflight.remove(msgId)
                return null
            }

            if (entry.chunks[chunkIndex] == null) {
                entry.chunks[chunkIndex] = payloadChunk
                entry.received++
            }

            if (entry.received == entry.chunkCount) {
                val full = ByteArray(entry.totalLen)
                var offset = 0
                for (i in 0 until entry.chunkCount) {
                    val c = entry.chunks[i] ?: return null
                    // Bounds check to prevent buffer overflow from malformed messages
                    if (offset + c.size > entry.totalLen) {
                        inflight.remove(msgId)
                        return null  // Malformed message
                    }
                    c.copyInto(full, destinationOffset = offset)
                    offset += c.size
                }
                // Some last chunk may be shorter; trim.
                val payload = if (offset == full.size) full else full.copyOf(offset)
                inflight.remove(msgId)
                return CompleteMessage(version, type, flags, msgId, payload)
            }

            return null
        }

        private fun purgeExpiredLocked() {
            val now = clock.nowEpochMillis()
            val it = inflight.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (now - e.value.startedMs > timeoutMs) it.remove()
            }
        }
    }
}
