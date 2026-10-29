package com.w6tech.infinassistant.core.data.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * The tests of this module read their cross-repo inputs from the classpath, so the public bridge
 * repo, which has no firmware tree, runs them unchanged. Here each copy is tied to its source:
 *
 *  - the golden vectors are hashed against `golden-vectors.pin` (CI also compares them with the file
 *    at the pinned firmware tag, FR-28);
 *  - when the monorepo tree is present, every copy is byte-compared with the file it came from.
 *    In the bridge repo there is no such tree and those tests are skipped.
 */
class VendoredFixturesTest {

    private fun resource(path: String): ByteArray =
        requireNotNull(VendoredFixturesTest::class.java.getResourceAsStream(path)) { "test resource $path missing" }
            .use { it.readBytes() }

    private fun pin(key: String): String {
        val line = String(resource("/golden-vectors.pin"), Charsets.UTF_8).lineSequence()
            .map { it.trim() }.firstOrNull { it.startsWith("$key=") }
        return requireNotNull(line) { "golden-vectors.pin has no $key=" }.substringAfter('=').trim()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `the pin names a firmware version tag`() {
        assertTrue("tag=${pin("tag")}", Regex("""\d+\.\d+\.\d+""").matches(pin("tag")))
    }

    @Test
    fun `the vendored golden vectors hash to the pinned sha256`() {
        assertEquals(pin("sha256"), sha256(resource(GoldenVectors.RESOURCE)))
    }

    @Test
    fun `the vendored golden vectors equal the monorepo source`() {
        val source = MonorepoSources.find("firmware/infinitime_overlay/vectors/protocol-vectors.json")
        assumeTrue("no monorepo tree above the working directory", source != null)
        assertArrayEquals(
            "golden-vectors/protocol-vectors.json differs from $source; copy it over and update golden-vectors.pin",
            source!!.readBytes(),
            resource(GoldenVectors.RESOURCE),
        )
    }

    @Test
    fun `the vendored layout seeds equal the monorepo fuzz seeds`() {
        for (name in listOf("app_long_keys.cbor", "app_short_keys.cbor")) {
            val source = MonorepoSources.find("tools/fuzz/seeds/layout_document/$name")
            assumeTrue("no monorepo tree above the working directory", source != null)
            assertNotNull(source)
            assertArrayEquals(
                "fuzz-seeds/layout_document/$name differs from $source; copy it over",
                source!!.readBytes(),
                resource("/fuzz-seeds/layout_document/$name"),
            )
        }
    }
}
