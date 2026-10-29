package com.w6tech.infinassistant.core.data.bridge

/**
 * One BLE frame of the bridge protocol. The watch handles a message that fits one frame straight
 * from the write (FrameCodec.cpp's single-chunk path). A longer message goes through a reassembly
 * buffer that the watch allocates from the heap the face draws from, and refuses while that heap is
 * low (kReassemblyHeadroomBytes), dropping the message unseen. The app lists (logbook, calendar,
 * home control) are therefore cut to what fits one frame.
 */
internal object WireFrame {
    /** MTU 256, less the ATT header (3) and the frame header (FrameCodec.HEADER_LEN, 9). */
    const val SINGLE_FRAME_PAYLOAD = 244

    /**
     * The payload [build] makes from the longest prefix of [items] whose encoding fits one frame;
     * at least one item, so a single oversized item still goes out (in several frames).
     */
    fun <T, P> fit(items: List<T>, encode: (P) -> ByteArray, build: (List<T>) -> P): P {
        var n = items.size
        var payload = build(items)
        while (n > 1 && encode(payload).size > SINGLE_FRAME_PAYLOAD) {
            n--
            payload = build(items.take(n))
        }
        return payload
    }
}
