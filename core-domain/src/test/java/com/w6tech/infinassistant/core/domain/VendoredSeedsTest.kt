package com.w6tech.infinassistant.core.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The fuzz seed this module's tests read is a copy in the test resources, so the public bridge repo,
 * which has no firmware tree, runs them unchanged. With the monorepo tree present the copy is
 * byte-compared with the original; without it the test is skipped.
 */
class VendoredSeedsTest {

    private fun findUp(relative: String): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return null
    }

    @Test
    fun `the vendored sensor_report seed equals the monorepo fuzz seed`() {
        val source = findUp("tools/fuzz/seeds/sensor_report/watch_report.cbor")
        assumeTrue("no monorepo tree above the working directory", source != null)
        val copy = requireNotNull(VendoredSeedsTest::class.java.getResourceAsStream("/fuzz-seeds/sensor_report/watch_report.cbor")) {
            "test resource fuzz-seeds/sensor_report/watch_report.cbor missing"
        }.use { it.readBytes() }
        assertArrayEquals("fuzz-seeds/sensor_report/watch_report.cbor differs from $source; copy it over", source!!.readBytes(), copy)
    }
}
