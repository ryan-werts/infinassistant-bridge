package com.w6tech.infinassistant.core.data.bridge

import com.w6tech.infinassistant.core.domain.HelloAckPayload
import com.w6tech.infinassistant.core.domain.Protocol
import com.w6tech.infinassistant.core.domain.ProtocolCbor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HELLO_ACK has to carry "pmaj" on the wire.
 *
 * The watch reads its peer's major version from that key alone, as an unsigned
 * integer, and answers VERSION_MISMATCH whenever it disagrees with the firmware's
 * own kProtocolMajor. It cannot parse protocolVersion, which is a string.
 *
 * This is a regression test because adding the property was not enough: the
 * serializer does not encode defaults, so pmaj held its declared default and was
 * silently skipped. HELLO_ACK stayed byte-identical at 61 bytes across the release
 * that was supposed to fix it, and the watch kept reporting a mismatch on every
 * handshake. Asserting the property exists would not have caught that — only
 * asserting it reaches the encoded bytes does.
 */
class HelloAckWireTest {

    private fun encode(payload: HelloAckPayload): ByteArray =
        ProtocolCbor.encode(HelloAckPayload.serializer(), payload)

    @Test
    fun `pmaj is encoded even though it holds its default`() {
        val bytes = encode(HelloAckPayload(protocolVersion = "1.0.0", supportedFeatures = 0))
        assertTrue(
            "pmaj missing from encoded HELLO_ACK — the watch will report VERSION_MISMATCH",
            bytes.containsAscii("pmaj"),
        )
    }

    @Test
    fun `encoded pmaj value matches the declared protocol major`() {
        val bytes = encode(HelloAckPayload(protocolVersion = "1.0.0", supportedFeatures = 0))
        val idx = bytes.indexOfAscii("pmaj")
        assertTrue("pmaj key not found", idx >= 0)
        // CBOR: 4-byte text key occupies 1 header + 4 bytes, then the value. Small
        // unsigned integers encode as a single byte equal to the value itself.
        val value = bytes[idx + "pmaj".length].toInt() and 0xFF
        assertEquals(Protocol.PROTOCOL_MAJOR, value)
    }

    @Test
    fun `protocol major agrees with the firmware constant`() {
        // firmware: ProtocolMessages.h — static constexpr uint8_t kProtocolMajor = 1
        assertEquals(1, Protocol.PROTOCOL_MAJOR)
        assertEquals(
            "PROTOCOL_MAJOR must be the major of PROTOCOL_VERSION",
            Protocol.PROTOCOL_VERSION.substringBefore('.').toInt(),
            Protocol.PROTOCOL_MAJOR,
        )
    }

    private fun ByteArray.indexOfAscii(needle: String): Int {
        val n = needle.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..(size - n.size)) {
            for (j in n.indices) if (this[i + j] != n[j]) continue@outer
            return i
        }
        return -1
    }

    private fun ByteArray.containsAscii(needle: String): Boolean = indexOfAscii(needle) >= 0
}
