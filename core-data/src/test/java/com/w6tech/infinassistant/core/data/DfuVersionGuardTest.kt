package com.w6tech.infinassistant.core.data

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DfuVersionGuardTest {

    private fun manifestJson(binFile: String): ByteArray =
        """{"manifest":{"application":{"bin_file":"$binFile"}}}""".toByteArray()

    @Test
    fun `accepts manifest at floor version`() {
        val bytes = manifestJson("pinetime-mcuboot-app-image-${DfuVersionGuard.MIN_DFU_VERSION}.bin")
        val result = DfuVersionGuard.checkManifest(bytes)
        assertIs<DfuVersionGuard.VersionCheckResult.Ok>(result)
        assertEquals(DfuVersionGuard.MIN_DFU_VERSION, result.version)
    }

    @Test
    fun `the floor is the firmware that reads the renamed config key`() {
        assertEquals("2.4.103", DfuVersionGuard.MIN_DFU_VERSION)
        for (old in listOf("2.4.101", "2.4.102", "1.184.0")) {
            val result = DfuVersionGuard.checkManifest(manifestJson("pinetime-mcuboot-app-image-$old.bin"))
            assertIs<DfuVersionGuard.VersionCheckResult.TooOld>(result)
            assertEquals(old, result.version)
        }
    }

    @Test
    fun `accepts manifest above floor version`() {
        val bytes = manifestJson("pinetime-mcuboot-app-image-2.4.110.bin")
        val result = DfuVersionGuard.checkManifest(bytes)
        assertIs<DfuVersionGuard.VersionCheckResult.Ok>(result)
        assertEquals("2.4.110", result.version)
    }

    @Test
    fun `rejects manifest below floor version`() {
        val bytes = manifestJson("pinetime-mcuboot-app-image-2.4.102.bin")
        val result = DfuVersionGuard.checkManifest(bytes)
        assertIs<DfuVersionGuard.VersionCheckResult.TooOld>(result)
        assertEquals("2.4.102", result.version)
        assertEquals(DfuVersionGuard.MIN_DFU_VERSION, result.floor)
    }

    @Test
    fun `rejects manifest from old major release`() {
        val bytes = manifestJson("pinetime-mcuboot-app-image-0.180.0.bin")
        val result = DfuVersionGuard.checkManifest(bytes)
        assertIs<DfuVersionGuard.VersionCheckResult.TooOld>(result)
        assertEquals("0.180.0", result.version)
    }

    @Test
    fun `accepts manifest without image- infix`() {
        val bytes = manifestJson("pinetime-mcuboot-app-2.4.103.bin")
        val result = DfuVersionGuard.checkManifest(bytes)
        assertIs<DfuVersionGuard.VersionCheckResult.Ok>(result)
        assertEquals("2.4.103", result.version)
    }

    @Test
    fun `unparseable on missing manifest envelope`() {
        val result = DfuVersionGuard.checkManifest("""{"foo":1}""".toByteArray())
        assertIs<DfuVersionGuard.VersionCheckResult.Unparseable>(result)
    }

    @Test
    fun `unparseable on missing bin_file`() {
        val result = DfuVersionGuard.checkManifest("""{"manifest":{"application":{}}}""".toByteArray())
        assertIs<DfuVersionGuard.VersionCheckResult.Unparseable>(result)
    }

    @Test
    fun `unparseable on bad version pattern in bin_file`() {
        val bytes = manifestJson("pinetime-mcuboot-app-image-NOT_A_VERSION.bin")
        val result = DfuVersionGuard.checkManifest(bytes)
        assertIs<DfuVersionGuard.VersionCheckResult.Unparseable>(result)
    }

    @Test
    fun `compareVersions handles equal`() {
        assertEquals(0, DfuVersionGuard.compareVersions("1.184.0", "1.184.0"))
    }

    @Test
    fun `compareVersions ranks correctly across components`() {
        assertTrue(DfuVersionGuard.compareVersions("1.184.0", "1.184.1") < 0)
        assertTrue(DfuVersionGuard.compareVersions("1.184.0", "1.185.0") < 0)
        assertTrue(DfuVersionGuard.compareVersions("1.184.0", "2.0.0") < 0)
        assertTrue(DfuVersionGuard.compareVersions("2.0.0", "1.999.999") > 0)
    }
}
