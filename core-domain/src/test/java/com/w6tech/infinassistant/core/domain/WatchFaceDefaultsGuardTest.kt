package com.w6tech.infinassistant.core.domain

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * REQ009-FR-C4 default guard. The watch-face encoder omits every property that still holds its
 * Kotlin default (the shared encoder sent them all), so a property whose default is non-zero
 * reaches the firmware only as an absent key, and the firmware must supply the same value.
 *
 * This test enumerates `WatchFaceLayoutPayload` and every type reachable from it, lists each
 * property with a non-zero default, and fails on any that [WIRE_REACHABLE] or [PHONE_SIDE_ONLY]
 * does not name, so a new non-zero default cannot be added without a decision about the
 * firmware's absent-key value. It also fails on a reachable type it has no serializer for.
 *
 * **Parity with the host defaults-equivalence cases (4c.1).** The checked list here is the
 * shared list: [WIRE_REACHABLE] is compared, by property name, with the single-field
 * `"<property> omitted"` cases of `TestDefaultsEquivalence` in
 * `tools/fuzz/layout_document_test.cpp`, and both directions must match. The limits YAML
 * (`scripts/config/firmware-limits.yaml`) is not used: it carries revision constants and
 * would need a generator change for default values.
 */
class WatchFaceDefaultsGuardTest {

    companion object {
        /**
         * Non-zero defaults that can reach the wire, with the value the firmware must supply for
         * an absent key. Each has a `"<property> omitted"` host case.
         */
        val WIRE_REACHABLE: Map<String, String> = mapOf(
            "WatchFaceLayoutPayload.defaultActiveColor" to "\"#FFD700\" (BridgeController FillDefaultColors)",
            "WatchFaceLayoutPayload.defaultInactiveColor" to "\"#800080\" (BridgeController FillDefaultColors)",
            "WatchTile.colorEnabled" to "true (DecodeTileInline presets it)",
            "WatchScreen.tileLayout" to "4; omitted decodes to 0, which the firmware reads as the same 2x2 layout",
        )

        /** Non-zero defaults on types the orchestrator nulls before send, so they never reach the wire. */
        val PHONE_SIDE_ONLY: Set<String> = setOf(
            "WatchLogbook.maxEntries",
            "WatchLogbook.hoursBack",
            "WatchLogbookAppConfig.maxEntries",
            "WatchLogbookAppConfig.hoursBack",
        )

        /** One serializer per reachable class, keyed by simple serial name. */
        private val SERIALIZERS: Map<String, KSerializer<*>> = listOf<KSerializer<*>>(
            WatchFaceLayoutPayload.serializer(),
            WatchTheme.serializer(),
            WatchHeader.serializer(),
            WatchPresenceIcon.serializer(),
            WatchTile.serializer(),
            WatchStatusBar.serializer(),
            WatchScreen.serializer(),
            WatchDashboard.serializer(),
            WatchStatusGroup.serializer(),
            WatchGlyph.serializer(),
            WatchSensorGroup.serializer(),
            WatchSensor.serializer(),
            WatchWeather.serializer(),
            WatchEvent.serializer(),
            WatchNotification.serializer(),
            WatchLogbook.serializer(),
            WatchLogbookAppConfig.serializer(),
            WatchHomeControlConfig.serializer(),
            WatchHomeControlDevice.serializer(),
        ).associateBy { simpleName(it.descriptor) }

        private fun simpleName(d: SerialDescriptor): String = d.serialName.removeSuffix("?").substringAfterLast('.')

        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    }

    // ---- enumeration -------------------------------------------------------------

    /** Simple names of every class descriptor reachable from [root]. */
    private fun reachableClasses(root: SerialDescriptor): Set<String> {
        val seen = linkedSetOf<String>()
        fun visit(d: SerialDescriptor) {
            when (d.kind) {
                StructureKind.CLASS -> {
                    if (!seen.add(simpleName(d))) return
                    for (i in 0 until d.elementsCount) visit(d.getElementDescriptor(i))
                }
                StructureKind.LIST, StructureKind.MAP -> for (i in 0 until d.elementsCount) visit(d.getElementDescriptor(i))
                else -> Unit
            }
        }
        visit(root)
        return seen
    }

    /** A JSON value that satisfies a required element of kind [d], all leaves zero. */
    private fun placeholder(d: SerialDescriptor): JsonElement = when (val kind = d.kind) {
        PrimitiveKind.STRING, PrimitiveKind.CHAR -> JsonPrimitive("")
        PrimitiveKind.BOOLEAN -> JsonPrimitive(false)
        PrimitiveKind.DOUBLE, PrimitiveKind.FLOAT -> JsonPrimitive(0.0)
        is PrimitiveKind -> JsonPrimitive(0)
        StructureKind.LIST -> JsonArray(emptyList())
        StructureKind.MAP -> JsonObject(emptyMap())
        StructureKind.CLASS -> requiredOnly(d)
        SerialKind.ENUM -> JsonPrimitive(d.getElementName(0))
        is PolymorphicKind, SerialKind.CONTEXTUAL, StructureKind.OBJECT ->
            error("no placeholder for ${d.serialName} ($kind); extend the guard")
    }

    /** An object holding only the required elements of [d]: every optional element is left at its default. */
    private fun requiredOnly(d: SerialDescriptor): JsonObject = JsonObject(
        (0 until d.elementsCount)
            .filter { !d.isElementOptional(it) }
            .associate { d.getElementName(it) to placeholder(d.getElementDescriptor(it)) },
    )

    private fun isNonZero(e: JsonElement): Boolean = when (e) {
        is JsonNull -> false
        is JsonObject -> e.values.any(::isNonZero)
        is JsonArray -> e.any(::isNonZero)
        is JsonPrimitive -> when {
            e.isString -> e.content.isNotEmpty()
            e.booleanOrNull != null -> e.booleanOrNull == true
            else -> (e.doubleOrNull ?: 0.0) != 0.0
        }
    }

    /** `Type.property` for every optional property of every reachable type whose default is non-zero. */
    private fun nonZeroDefaults(): Set<String> {
        val found = sortedSetOf<String>()
        for (name in reachableClasses(WatchFaceLayoutPayload.serializer().descriptor)) {
            @Suppress("UNCHECKED_CAST")
            val serializer = SERIALIZERS[name] as? KSerializer<Any> ?: continue
            val d = serializer.descriptor
            val instance = json.decodeFromJsonElement(serializer, requiredOnly(d))
            val tree = json.encodeToJsonElement(serializer, instance) as JsonObject
            for (i in 0 until d.elementsCount) {
                if (!d.isElementOptional(i)) continue
                val prop = d.getElementName(i)
                if (isNonZero(tree.getValue(prop))) found += "$name.$prop"
            }
        }
        return found
    }

    // ---- tests -------------------------------------------------------------------

    @Test
    fun `every type reachable from the layout payload has a serializer in the guard`() {
        val reachable = reachableClasses(WatchFaceLayoutPayload.serializer().descriptor)
        val missing = reachable - SERIALIZERS.keys
        assertTrue("reachable types the guard cannot instantiate: $missing", missing.isEmpty())
        val stale = SERIALIZERS.keys - reachable
        assertTrue("guard serializers for types no longer reachable: $stale", stale.isEmpty())
    }

    @Test
    fun `every non-zero default is listed`() {
        val listed = WIRE_REACHABLE.keys + PHONE_SIDE_ONLY
        val found = nonZeroDefaults()
        val unlisted = found - listed
        val gone = listed - found
        assertTrue(
            "non-zero default(s) not on the guard list: $unlisted. Decide the firmware's absent-key value, " +
                "add a host defaults-equivalence case, then list it in WIRE_REACHABLE.",
            unlisted.isEmpty(),
        )
        assertTrue("listed default(s) that are no longer non-zero or no longer exist: $gone", gone.isEmpty())
    }

    @Test
    fun `the enumeration sees a known non-zero default and a known zero default`() {
        val found = nonZeroDefaults()
        assertTrue(found.contains("WatchTile.colorEnabled"))
        assertTrue(!found.contains("WatchFaceLayoutPayload.tiles"))
        assertTrue(!found.contains("WatchFaceLayoutPayload.logbookApp"))
    }

    @Test
    fun `the watch-face encoder omits every wire-reachable default and keeps an override`() {
        val layout = WatchFaceLayoutPayload(
            version = 1,
            theme = WatchTheme(background = "#000000", accent = "#FF8800"),
            header = WatchHeader(timeFormat = "HH:mm", timeAlign = "center", presenceIcons = emptyList()),
            screens = listOf(WatchScreen(screenType = 1, tiles = listOf(WatchTile(label = "Lamp", color = "#FFFFFF")))),
            statusBar = WatchStatusBar(battery = true, steps = false, heartRate = true, notifications = false, bluetooth = true),
        )
        val omitted = String(ProtocolCbor.encodeWatchFaceLayout(layout, shortKeys = false).bytes, Charsets.ISO_8859_1)
        for (key in listOf("defaultActiveColor", "defaultInactiveColor", "colorEnabled", "tileLayout")) {
            assertTrue("$key must be absent when it holds its default", !omitted.contains(key))
        }
        val overridden = layout.copy(
            defaultActiveColor = "#112233",
            screens = listOf(WatchScreen(screenType = 1, tileLayout = 6, tiles = listOf(WatchTile(label = "Lamp", color = "#FFFFFF", colorEnabled = false)))),
        )
        val sent = String(ProtocolCbor.encodeWatchFaceLayout(overridden, shortKeys = false).bytes, Charsets.ISO_8859_1)
        for (key in listOf("defaultActiveColor", "colorEnabled", "tileLayout")) {
            assertTrue("$key must be present when it is overridden", sent.contains(key))
        }
    }

    @Test
    fun `wire-reachable list matches the host defaults-equivalence cases`() {
        val hostOrNull = findHostTest()
        // The host test is firmware-side C++ and is not vendored: in the bridge repo this test is skipped.
        assumeTrue("no firmware tree above the working directory", hostOrNull != null)
        val host = hostOrNull!!
        val text = host.readText()
        val start = text.indexOf("static void TestDefaultsEquivalence()")
        assertTrue("TestDefaultsEquivalence not found in ${host.path}", start >= 0)
        val body = text.substring(start)
        val cases = Regex("""\{\s*"(\w+) omitted"""").findAll(body).map { it.groupValues[1] }.toSet() - "all"
        val listedProps = WIRE_REACHABLE.keys.map { it.substringAfterLast('.') }.toSet()
        assertEquals(
            "single-field 'omitted' cases in ${host.name} must equal the wire-reachable list",
            listedProps,
            cases,
        )
    }

    /** The host test lives outside the Gradle project; walk up from the working directory to find it, or null. */
    private fun findHostTest(): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "tools/fuzz/layout_document_test.cpp")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return null
    }
}
