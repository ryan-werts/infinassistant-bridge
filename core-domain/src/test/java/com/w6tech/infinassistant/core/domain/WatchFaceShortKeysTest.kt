package com.w6tech.infinassistant.core.domain

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REQ009-FR-C3: the post-encode key rewrite and the long/short choice of
 * `ProtocolCbor.encodeWatchFaceLayout`.
 *
 * The rewrite is checked against an independent oracle: a small CBOR reader in
 * this file turns both spellings into plain maps and lists, and the short tree
 * must equal the long tree with every key renamed through [WireShortKeys], the
 * level of each map read from the serial descriptor.
 */
@OptIn(ExperimentalSerializationApi::class)
class WatchFaceShortKeysTest {

    // ---- long path ---------------------------------------------------------------

    @Test
    fun `long spelling is byte-identical to the pinned pre-change bytes`() {
        val full = ProtocolCbor.encodeWatchFaceLayout(WatchFaceFixtures.full, shortKeys = false)
        val minimal = ProtocolCbor.encodeWatchFaceLayout(WatchFaceFixtures.minimal, shortKeys = false)
        assertFalse(full.shortKeys)
        assertFalse(minimal.shortKeys)
        assertEquals(WatchFaceEncoderBytesPinTest.FULL_HEX, WatchFaceFixtures.hex(full.bytes))
        assertEquals(WatchFaceEncoderBytesPinTest.MINIMAL_HEX, WatchFaceFixtures.hex(minimal.bytes))
    }

    @Test
    fun `the pre-change fixture uses indefinite-length maps and arrays`() {
        val bytes = ProtocolCbor.encodeWatchFace(WatchFaceLayoutPayload.serializer(), WatchFaceFixtures.full)
        assertEquals("root map opens indefinite (0xBF)", 0xBF, bytes.first().toInt() and 0xFF)
        assertEquals("root map closes with the break byte", 0xFF, bytes.last().toInt() and 0xFF)
        assertTrue("an array opens indefinite (0x9F)", bytes.any { (it.toInt() and 0xFF) == 0x9F })
    }

    // ---- short path --------------------------------------------------------------

    @Test
    fun `short spelling renames every key through the registry, level by level`() {
        for (payload in listOf(WatchFaceFixtures.full, WatchFaceFixtures.minimal)) {
            val long = ProtocolCbor.encodeWatchFaceLayout(payload, shortKeys = false)
            val short = ProtocolCbor.encodeWatchFaceLayout(payload, shortKeys = true)
            assertTrue("short spelling was produced", short.shortKeys)
            assertTrue("short is smaller", short.bytes.size < long.bytes.size)

            val expected = rename(parse(long.bytes), LAYOUT_DESCRIPTOR, toShort = true, visited = null)
            assertEquals(expected, parse(short.bytes))
        }
    }

    @Test
    fun `every registry level is exercised by the full fixture`() {
        val visited = mutableSetOf<String>()
        val long = ProtocolCbor.encodeWatchFaceLayout(WatchFaceFixtures.full, shortKeys = false)
        rename(parse(long.bytes), LAYOUT_DESCRIPTOR, toShort = true, visited = visited)
        assertEquals(WireShortKeys.BY_LEVEL.keys, visited.intersect(WireShortKeys.BY_LEVEL.keys))
    }

    @Test
    fun `every nested type round-trips in both spellings`() {
        val payload = WatchFaceFixtures.full
        val long = ProtocolCbor.encodeWatchFaceLayout(payload, shortKeys = false)
        val short = ProtocolCbor.encodeWatchFaceLayout(payload, shortKeys = true)

        // Long: decodes back to the payload the app started from.
        val decoded = ProtocolCbor.decode(WatchFaceLayoutPayload.serializer(), long.bytes)
        assertEquals(payload, decoded)

        // Short: renaming its keys back gives exactly the long tree, so nothing was
        // lost or moved in the rewrite.
        val restored = rename(parse(short.bytes), LAYOUT_DESCRIPTOR, toShort = false, visited = null)
        assertEquals(parse(long.bytes), restored)
    }

    @Test
    fun `long-only levels and keys keep their long spelling`() {
        val short = ProtocolCbor.encodeWatchFaceLayout(WatchFaceFixtures.full, shortKeys = true)
        @Suppress("UNCHECKED_CAST")
        val root = parse(short.bytes) as Map<String, Any?>
        for (key in WireShortKeys.LAYOUT_LONG_ONLY) assertTrue("layout keeps '$key'", key in root)

        @Suppress("UNCHECKED_CAST")
        val screens = root.getValue("s") as List<Map<String, Any?>>
        val logbookScreen = screens.single { "logbook" in it }
        @Suppress("UNCHECKED_CAST")
        val logbook = logbookScreen.getValue("logbook") as Map<String, Any?>
        assertEquals(WireShortKeys.LOGBOOK_LONG_ONLY.toSet(), logbook.keys)

        // The map under a long-only key is not renamed either (its own keys are not
        // in the registry at that level).
        @Suppress("UNCHECKED_CAST")
        val app = root.getValue("logbookApp") as Map<String, Any?>
        assertEquals(setOf("entityIds", "maxEntries", "hoursBack"), app.keys)
    }

    @Test
    fun `no long key of a shortened level survives in the short spelling`() {
        val short = ProtocolCbor.encodeWatchFaceLayout(WatchFaceFixtures.full, shortKeys = true)
        val text = String(short.bytes, Charsets.ISO_8859_1)
        for (long in listOf("version", "theme", "statusBar", "screenType", "statusGroups", "colorEnabled", "displayType", "timeFormat", "im0l", "cvl")) {
            assertFalse("'$long' should not appear", text.contains(long))
        }
    }

    // ---- definite-length containers on the short path (REQ009-FR-B4) --------------

    @Test
    fun `an indefinite input is written back with definite-length headers`() {
        // {"version": 1, "theme": {"background": "a", "accent": "b"}}, indefinite lengths in.
        val input = hexBytes("bf" + text("version") + "01" + text("theme") + "bf" + text("background") + text("a") + text("accent") + text("b") + "ff" + "ff")
        val expected = hexBytes("a2" + text("v") + "01" + text("m") + "a2" + text("b") + text("a") + text("a") + text("b"))
        assertArrayEquals(expected, WatchFaceShortKeys.toShortKeys(input))
    }

    @Test
    fun `empty indefinite containers become empty definite ones`() {
        // {"version": 1, "screens": [], "theme": {}}: 9f ff and bf ff in, 80 and a0 out.
        val input = hexBytes("bf" + text("version") + "01" + text("screens") + "9fff" + text("theme") + "bfff" + "ff")
        val expected = hexBytes("a3" + text("v") + "01" + text("s") + "80" + text("m") + "a0")
        assertArrayEquals(expected, WatchFaceShortKeys.toShortKeys(input))
    }

    @Test
    fun `the definite header grows with the entry count`() {
        // An unknown key holding an indefinite array of N small integers: the header is the
        // 1-byte form up to 23 entries, then 0x98 nn, then 0x99 nnnn.
        fun rewrite(n: Int): ByteArray {
            val input = hexBytes("bf" + text("zzz") + "9f" + "00".repeat(n) + "ff" + "ff")
            return WatchFaceShortKeys.toShortKeys(input)!!
        }
        val prefix = hexBytes("a1" + text("zzz"))
        for ((n, header) in listOf(0 to "80", 23 to "97", 24 to "9818", 255 to "98ff", 256 to "990100", 300 to "99012c")) {
            assertArrayEquals("$n entries", prefix + hexBytes(header + "00".repeat(n)), rewrite(n))
        }
    }

    @Test
    fun `a map counts its entries, not its keys and values`() {
        // Three entries, each a text key and an integer value: header a3, not a6.
        val input = hexBytes("bf" + text("aa") + "01" + text("bb") + "02" + text("cc") + "03" + "ff")
        assertArrayEquals(hexBytes("a3" + text("aa") + "01" + text("bb") + "02" + text("cc") + "03"), WatchFaceShortKeys.toShortKeys(input))
    }

    @Test
    fun `an unterminated indefinite container makes the rewrite give up`() {
        assertNull(WatchFaceShortKeys.toShortKeys(hexBytes("bf" + text("version") + "01")))
        assertNull(WatchFaceShortKeys.toShortKeys(hexBytes("bf" + text("zzz") + "9f00")))
    }

    @Test
    fun `the short spelling of a real payload has no indefinite container`() {
        for (payload in listOf(WatchFaceFixtures.full, WatchFaceFixtures.minimal)) {
            val short = ProtocolCbor.encodeWatchFaceLayout(payload, shortKeys = true)
            val long = ProtocolCbor.encodeWatchFaceLayout(payload, shortKeys = false)
            assertEquals("indefinite containers in the short bytes", 0, countIndefinite(short.bytes))
            assertTrue("indefinite containers in the long bytes", countIndefinite(long.bytes) > 0)
            assertEquals("same content as the long tree", rename(parse(long.bytes), LAYOUT_DESCRIPTOR, toShort = true, visited = null), parse(short.bytes))
        }
    }

    @Test
    fun `each container saves one byte of framing over the rename alone`() {
        // Shorter keys save (long - short) per key; each container saves 1 B (0xBF/0x9F ... 0xFF is
        // two bytes, a definite header is one when the count is under 24, as it is here).
        for (payload in listOf(WatchFaceFixtures.full, WatchFaceFixtures.minimal)) {
            val long = ProtocolCbor.encodeWatchFaceLayout(payload, shortKeys = false).bytes
            val short = ProtocolCbor.encodeWatchFaceLayout(payload, shortKeys = true).bytes
            val keyBytesSaved = keyBytes(long) - keyBytes(short)
            val containers = countIndefinite(long)
            assertEquals(long.size - keyBytesSaved - containers, short.size)
        }
    }

    // ---- walker input handling ---------------------------------------------------

    @Test
    fun `definite-length maps are rewritten too`() {
        // {"version": 1, "theme": {"background": "a", "accent": "b"}}, definite lengths.
        val input = hexBytes("a2" + text("version") + "01" + text("theme") + "a2" + text("background") + text("a") + text("accent") + text("b"))
        val expected = hexBytes("a2" + text("v") + "01" + text("m") + "a2" + text("b") + text("a") + text("a") + text("b"))
        assertArrayEquals(expected, WatchFaceShortKeys.toShortKeys(input))
    }

    @Test
    fun `unknown keys and wide integers are copied unchanged`() {
        // {"zzz": 4294967296, "version": -1}: "zzz" is not a layout key, the 8-byte integer is copied.
        val input = hexBytes("a2" + text("zzz") + "1b0000000100000000" + text("version") + "20")
        val expected = hexBytes("a2" + text("zzz") + "1b0000000100000000" + text("v") + "20")
        assertArrayEquals(expected, WatchFaceShortKeys.toShortKeys(input))
    }

    @Test
    fun `items the rewrite does not handle make it give up`() {
        val cases = mapOf(
            "byte string value" to "a1" + text("version") + "4101",
            "tagged value" to "a1" + text("version") + "c101",
            "float value" to "a1" + text("version") + "fb3ff0000000000000",
            "integer key" to "a1" + "01" + "01",
            "indefinite text" to "a1" + text("version") + "7f6161ff",
            "truncated" to "a2" + text("version") + "01",
            "trailing bytes" to "a0" + "00",
            "length past the end" to "a1" + "7a7fffffff",
        )
        for ((name, hex) in cases) {
            assertNull("$name should not be rewritten", WatchFaceShortKeys.toShortKeys(hexBytes(hex)))
        }
    }

    // ---- independent oracle ------------------------------------------------------

    private val LAYOUT_DESCRIPTOR = WatchFaceLayoutPayload.serializer().descriptor

    private val levelBySerialName: Map<String, String> = mapOf(
        "layout" to WatchFaceLayoutPayload.serializer().descriptor,
        "theme" to WatchTheme.serializer().descriptor,
        "header" to WatchHeader.serializer().descriptor,
        "presence_icon" to WatchPresenceIcon.serializer().descriptor,
        "status_bar" to WatchStatusBar.serializer().descriptor,
        "screen" to WatchScreen.serializer().descriptor,
        "tile" to WatchTile.serializer().descriptor,
        "dashboard" to WatchDashboard.serializer().descriptor,
        "status_group" to WatchStatusGroup.serializer().descriptor,
        "glyph" to WatchGlyph.serializer().descriptor,
        "sensor_group" to WatchSensorGroup.serializer().descriptor,
        "sensor" to WatchSensor.serializer().descriptor,
        "weather" to WatchWeather.serializer().descriptor,
        "event" to WatchEvent.serializer().descriptor,
        "notification" to WatchNotification.serializer().descriptor,
        "logbook" to WatchLogbook.serializer().descriptor,
    ).entries.associate { (level, d) -> d.serialName to level }

    /** Rename the keys of [node] to short (or back to long), level taken from [d]. */
    private fun rename(node: Any?, d: SerialDescriptor?, toShort: Boolean, visited: MutableSet<String>?): Any? =
        when (node) {
            is Map<*, *> -> {
                val level = d?.let { levelBySerialName[it.serialName.removeSuffix("?")] }
                if (level != null) visited?.add(level)
                val pairs = level?.let { WireShortKeys.BY_LEVEL[it] } ?: emptyMap()
                val inverse = pairs.entries.associate { (long, short) -> short to long }
                val out = LinkedHashMap<String, Any?>()
                for ((k, v) in node) {
                    k as String
                    val longKey = if (toShort) k else (inverse[k] ?: k)
                    val index = if (d != null && d.kind == StructureKind.CLASS) d.getElementIndex(longKey) else -1
                    val child = if (d != null && index >= 0) d.getElementDescriptor(index) else null
                    val newKey = if (toShort) (pairs[k] ?: k) else longKey
                    out[newKey] = rename(v, child, toShort, visited)
                }
                out
            }
            is List<*> -> node.map { rename(it, if (d != null && d.kind == StructureKind.LIST) d.getElementDescriptor(0) else null, toShort, visited) }
            else -> node
        }

    /** Minimal CBOR reader: maps (definite or indefinite), arrays, text, integers, booleans, null. */
    private fun parse(bytes: ByteArray): Any? {
        var p = 0
        fun byte() = bytes[p++].toInt() and 0xFF
        fun argument(info: Int): Long {
            if (info < 24) return info.toLong()
            val n = when (info) { 24 -> 1; 25 -> 2; 26 -> 4; 27 -> 8; else -> error("bad additional info $info") }
            var v = 0L
            repeat(n) { v = (v shl 8) or byte().toLong() }
            return v
        }
        fun read(): Any? {
            val ib = byte()
            val info = ib and 0x1F
            return when (ib ushr 5) {
                0 -> argument(info)
                1 -> -1L - argument(info)
                3 -> {
                    val n = argument(info).toInt()
                    String(bytes, p, n, Charsets.UTF_8).also { p += n }
                }
                4 -> {
                    val out = ArrayList<Any?>()
                    if (info == 31) {
                        while (bytes[p].toInt() and 0xFF != 0xFF) out += read()
                        p++
                    } else repeat(argument(info).toInt()) { out += read() }
                    out
                }
                5 -> {
                    val out = LinkedHashMap<String, Any?>()
                    fun entry() { val k = read() as String; out[k] = read() }
                    if (info == 31) {
                        while (bytes[p].toInt() and 0xFF != 0xFF) entry()
                        p++
                    } else repeat(argument(info).toInt()) { entry() }
                    out
                }
                7 -> when (ib) { 0xF4 -> false; 0xF5 -> true; 0xF6 -> null; else -> error("bad simple $ib") }
                else -> error("unsupported major type ${ib ushr 5}")
            }
        }
        return read().also { assertEquals("all bytes consumed", bytes.size, p) }
    }

    /** Number of indefinite-length maps and arrays in [bytes]. */
    private fun countIndefinite(bytes: ByteArray): Int {
        var p = 0
        var count = 0
        fun byte() = bytes[p++].toInt() and 0xFF
        fun argument(info: Int): Long {
            if (info < 24) return info.toLong()
            val n = when (info) { 24 -> 1; 25 -> 2; 26 -> 4; 27 -> 8; else -> error("bad additional info $info") }
            var v = 0L
            repeat(n) { v = (v shl 8) or byte().toLong() }
            return v
        }
        fun read() {
            val ib = byte()
            val info = ib and 0x1F
            when (ib ushr 5) {
                0, 1 -> argument(info)
                3 -> { val n = argument(info).toInt(); p += n }
                4, 5 -> {
                    val per = if (ib ushr 5 == 5) 2 else 1
                    if (info == 31) {
                        count++
                        while (bytes[p].toInt() and 0xFF != 0xFF) repeat(per) { read() }
                        p++
                    } else repeat(argument(info).toInt() * per) { read() }
                }
                7 -> Unit
                else -> error("unsupported major type ${ib ushr 5}")
            }
        }
        read()
        assertEquals("all bytes consumed", bytes.size, p)
        return count
    }

    /** Total bytes of map keys (header and text) in [bytes]. */
    private fun keyBytes(bytes: ByteArray): Int {
        var p = 0
        var total = 0
        fun byte() = bytes[p++].toInt() and 0xFF
        fun argument(info: Int): Long {
            if (info < 24) return info.toLong()
            val n = when (info) { 24 -> 1; 25 -> 2; 26 -> 4; 27 -> 8; else -> error("bad additional info $info") }
            var v = 0L
            repeat(n) { v = (v shl 8) or byte().toLong() }
            return v
        }
        fun read(isKey: Boolean) {
            val start = p
            val ib = byte()
            val info = ib and 0x1F
            when (ib ushr 5) {
                0, 1 -> argument(info)
                3 -> { val n = argument(info).toInt(); p += n }
                4 -> {
                    if (info == 31) { while (bytes[p].toInt() and 0xFF != 0xFF) read(false); p++ }
                    else repeat(argument(info).toInt()) { read(false) }
                }
                5 -> {
                    fun entry() { read(true); read(false) }
                    if (info == 31) { while (bytes[p].toInt() and 0xFF != 0xFF) entry(); p++ }
                    else repeat(argument(info).toInt()) { entry() }
                }
                7 -> Unit
                else -> error("unsupported major type ${ib ushr 5}")
            }
            if (isKey) total += p - start
        }
        read(false)
        return total
    }

    private fun text(s: String): String {
        val b = s.toByteArray(Charsets.UTF_8)
        require(b.size < 24)
        return "%02x".format(0x60 or b.size) + b.joinToString("") { "%02x".format(it) }
    }

    private fun hexBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
