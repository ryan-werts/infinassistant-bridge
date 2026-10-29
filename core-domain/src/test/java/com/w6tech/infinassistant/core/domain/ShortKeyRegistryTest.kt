package com.w6tech.infinassistant.core.domain

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REQ009-FR-C1: the generated [WireShortKeys] registry.
 *
 * Per map level: short keys are unique, and no short key equals a long key of
 * the same level. The "long" key is the property's current wire name, read from
 * the kotlinx serial descriptors, so a renamed or newly added property that the
 * registry does not know about fails here.
 */
@OptIn(ExperimentalSerializationApi::class)
class ShortKeyRegistryTest {

    /** Level name (the YAML key) to the serializer that encodes that map. */
    private val descriptors: Map<String, SerialDescriptor> = mapOf(
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
    )

    private fun wireNames(d: SerialDescriptor): List<String> =
        (0 until d.elementsCount).map { d.getElementName(it) }

    private fun pairs(level: String): Map<String, String> =
        WireShortKeys.BY_LEVEL[level] ?: emptyMap()

    private fun longOnly(level: String): List<String> =
        WireShortKeys.LONG_ONLY_BY_LEVEL[level] ?: emptyList()

    @Test
    fun `registry names exactly the levels the app encodes`() {
        val registered = WireShortKeys.BY_LEVEL.keys + WireShortKeys.LONG_ONLY_BY_LEVEL.keys
        assertEquals(descriptors.keys, registered)
    }

    @Test
    fun `short keys are unique within each level`() {
        for (level in descriptors.keys) {
            val shorts = pairs(level).values.toList()
            assertEquals(
                "level '$level' repeats a short key: $shorts",
                shorts.size,
                shorts.toSet().size,
            )
        }
    }

    @Test
    fun `no short key equals a long key of the same level`() {
        for (level in descriptors.keys) {
            val longs = pairs(level).keys + longOnly(level) + wireNames(descriptors.getValue(level))
            for ((long, short) in pairs(level)) {
                assertFalse(
                    "level '$level': short '$short' (for '$long') equals a long key",
                    short in longs,
                )
            }
        }
    }

    @Test
    fun `short keys are non-empty and shorter than their long key`() {
        for (level in descriptors.keys) {
            for ((long, short) in pairs(level)) {
                assertTrue("level '$level': empty short key for '$long'", short.isNotEmpty())
                assertTrue(
                    "level '$level': short '$short' is not shorter than '$long'",
                    short.length < long.length,
                )
            }
        }
    }

    @Test
    fun `every current wire name is mapped or listed as long only`() {
        for ((level, descriptor) in descriptors) {
            val covered = pairs(level).keys + longOnly(level)
            for (name in wireNames(descriptor)) {
                assertTrue("level '$level': wire name '$name' is not in the registry", name in covered)
            }
        }
    }

    @Test
    fun `every registry long key is a current wire name`() {
        for ((level, descriptor) in descriptors) {
            val wire = wireNames(descriptor).toSet()
            for (long in pairs(level).keys + longOnly(level)) {
                assertTrue("level '$level': registry key '$long' is not a wire name", long in wire)
            }
        }
    }

    @Test
    fun `a key is either mapped or long only, never both`() {
        for (level in descriptors.keys) {
            val both = pairs(level).keys.intersect(longOnly(level).toSet())
            assertTrue("level '$level': $both is both mapped and long only", both.isEmpty())
        }
    }

    @Test
    fun `already abbreviated wire names are the long form`() {
        val dashboard = pairs("dashboard")
        for (name in listOf("lea", "cvl", "cvv", "im0l", "im0v", "im1l", "im1v")) {
            assertTrue("dashboard '$name' missing from registry", name in dashboard)
        }
        assertTrue("event 'ese' missing from registry", "ese" in pairs("event"))
    }
}
