@file:OptIn(ExperimentalSerializationApi::class)

package com.w6tech.infinassistant.core.domain

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import java.io.ByteArrayOutputStream

/**
 * A WATCHFACE_LAYOUT payload as it goes on the wire.
 *
 * [shortKeys] is true only when the bytes carry the short key spelling, so a
 * caller that asked for short keys can tell that the encoder fell back to long.
 */
class EncodedWatchFaceLayout(val bytes: ByteArray, val shortKeys: Boolean)

/**
 * Post-encode key rewrite (REQ009-FR-C3): turns the long-key CBOR that
 * `ProtocolCbor.encodeWatchFace` writes into the short-key spelling of
 * [WireShortKeys].
 *
 * kotlinx-serialization-cbor 1.7.3 offers no per-session key naming, and
 * `@SerialName` fixes one spelling at compile time, so the payload is encoded
 * once with the long names and the text keys of each map are rewritten here.
 * The level of a map is read from the serial descriptor of the type that
 * produced it, so the rewrite tracks the registry without a second type set.
 *
 * Everything but the key text and the container headers is copied byte for byte.
 * The encoder writes maps and arrays with indefinite length (0xBF / 0x9F ... 0xFF),
 * which costs two bytes of framing per container; the rewrite counts the entries
 * and writes a definite-length header instead (one byte for up to 23 entries), so
 * the short spelling is smaller than a key rename alone (REQ009-FR-B4). A container
 * that already has a definite length keeps its header. Integer, boolean and null
 * items are copied unchanged. Anything else (byte strings, tags, floats, non-text
 * keys, indefinite text) makes the rewrite give up, and the caller sends long keys.
 * The long spelling never passes through here and stays the encoder's output.
 */
object WatchFaceShortKeys {

    /** Serial name of each encoded type to its registry level. */
    private val LEVEL_BY_SERIAL_NAME: Map<String, String> = mapOf(
        WatchFaceLayoutPayload.serializer().descriptor.serialName to "layout",
        WatchTheme.serializer().descriptor.serialName to "theme",
        WatchHeader.serializer().descriptor.serialName to "header",
        WatchPresenceIcon.serializer().descriptor.serialName to "presence_icon",
        WatchStatusBar.serializer().descriptor.serialName to "status_bar",
        WatchScreen.serializer().descriptor.serialName to "screen",
        WatchTile.serializer().descriptor.serialName to "tile",
        WatchDashboard.serializer().descriptor.serialName to "dashboard",
        WatchStatusGroup.serializer().descriptor.serialName to "status_group",
        WatchGlyph.serializer().descriptor.serialName to "glyph",
        WatchSensorGroup.serializer().descriptor.serialName to "sensor_group",
        WatchSensor.serializer().descriptor.serialName to "sensor",
        WatchWeather.serializer().descriptor.serialName to "weather",
        WatchEvent.serializer().descriptor.serialName to "event",
        WatchNotification.serializer().descriptor.serialName to "notification",
    )

    private const val MAX_DEPTH = 16

    /**
     * Rewrite the map keys of [longKeyBytes] (an encoded [WatchFaceLayoutPayload])
     * to their short spelling, or return null when the bytes hold a CBOR item the
     * rewrite does not handle.
     */
    fun toShortKeys(longKeyBytes: ByteArray): ByteArray? =
        try {
            Rewriter(longKeyBytes).run()
        } catch (e: UnsupportedCborException) {
            null
        }

    private class UnsupportedCborException : Exception()

    private fun unsupported(): Nothing = throw UnsupportedCborException()

    private class Rewriter(private val src: ByteArray) {
        private var pos = 0
        private var out = ByteArrayOutputStream(src.size)

        fun run(): ByteArray {
            item(WatchFaceLayoutPayload.serializer().descriptor, 0)
            if (pos != src.size) unsupported()
            return out.toByteArray()
        }

        private fun next(): Int {
            if (pos >= src.size) unsupported()
            return src[pos++].toInt() and 0xFF
        }

        private fun peek(): Int {
            if (pos >= src.size) unsupported()
            return src[pos].toInt() and 0xFF
        }

        /** Consume the argument that follows an initial byte with additional info [info]. */
        private fun readArgument(info: Int): Long {
            val extra = when {
                info < 24 -> return info.toLong()
                info == 24 -> 1
                info == 25 -> 2
                info == 26 -> 4
                info == 27 -> 8
                else -> unsupported()
            }
            if (pos + extra > src.size) unsupported()
            var v = 0L
            for (i in 0 until extra) v = (v shl 8) or (src[pos + i].toLong() and 0xFF)
            pos += extra
            return v
        }

        /** A length or element count: must fit the remaining input. */
        private fun readLength(info: Int): Int {
            val v = readArgument(info)
            if (v < 0 || v > src.size - pos) unsupported()
            return v.toInt()
        }

        /** Copy `src[from, pos)` to the output. */
        private fun copyFrom(from: Int) {
            out.write(src, from, pos - from)
        }

        private fun item(d: SerialDescriptor?, depth: Int) {
            if (depth > MAX_DEPTH) unsupported()
            val start = pos
            val ib = next()
            val info = ib and 0x1F
            when (ib ushr 5) {
                0, 1 -> {
                    readArgument(info)
                    copyFrom(start)
                }
                3 -> {
                    if (info == 31) unsupported()
                    val len = readLength(info)
                    pos += len
                    copyFrom(start)
                }
                4 -> container(ib, info) {
                    item(if (d != null && d.kind == StructureKind.LIST) d.getElementDescriptor(0) else null, depth + 1)
                }
                5 -> container(ib, info) { mapEntry(d, depth) }
                7 -> if (ib == 0xF4 || ib == 0xF5 || ib == 0xF6) out.write(ib) else unsupported()
                else -> unsupported()
            }
        }

        /**
         * Copy a definite length header, or turn an indefinite one into a definite
         * header, then run [entry] once per element. An indefinite container's
         * elements are written to a side buffer first, because the header needs
         * their count.
         */
        private fun container(ib: Int, info: Int, entry: () -> Unit) {
            if (info == 31) {
                val outer = out
                val body = ByteArrayOutputStream()
                var count = 0
                out = body
                try {
                    while (peek() != 0xFF) {
                        entry()
                        count++
                    }
                } finally {
                    out = outer
                }
                pos++ // the break byte, which peek() has just seen
                writeHeader(ib and 0xE0, count)
                body.writeTo(out)
                return
            }
            out.write(ib)
            val headerArgStart = pos
            val count = readLength(info)
            copyFrom(headerArgStart)
            for (i in 0 until count) entry()
        }

        private fun mapEntry(d: SerialDescriptor?, depth: Int) {
            val keyStart = pos
            val kb = next()
            if ((kb ushr 5) != 3 || (kb and 0x1F) == 31) unsupported()
            val len = readLength(kb and 0x1F)
            val key = String(src, pos, len, Charsets.UTF_8)
            pos += len

            val level = d?.let { LEVEL_BY_SERIAL_NAME[it.serialName.removeSuffix("?")] }
            val index = if (d != null && d.kind == StructureKind.CLASS) d.getElementIndex(key) else -1
            val child = if (d != null && index >= 0) d.getElementDescriptor(index) else null
            val shortKey = level?.let { WireShortKeys.BY_LEVEL[it]?.get(key) }

            if (shortKey == null) copyFrom(keyStart) else writeText(shortKey)
            item(child, depth + 1)
        }

        /** Write a definite-length header for [count] elements of the container with [majorBits] (0x80 array, 0xA0 map). */
        private fun writeHeader(majorBits: Int, count: Int) {
            when {
                count < 24 -> out.write(majorBits or count)
                count < 256 -> { out.write(majorBits or 24); out.write(count) }
                count < 65536 -> { out.write(majorBits or 25); out.write(count ushr 8); out.write(count and 0xFF) }
                else -> unsupported()
            }
        }

        private fun writeText(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            when {
                b.size < 24 -> out.write(0x60 or b.size)
                b.size < 256 -> { out.write(0x78); out.write(b.size) }
                b.size < 65536 -> { out.write(0x79); out.write(b.size ushr 8); out.write(b.size and 0xFF) }
                else -> unsupported()
            }
            out.write(b)
        }
    }
}
