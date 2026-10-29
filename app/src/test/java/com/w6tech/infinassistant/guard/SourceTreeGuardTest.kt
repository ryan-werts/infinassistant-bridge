package com.w6tech.infinassistant.guard

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-tree checks: they read the source files on disk, found by walking up from the JVM working
 * directory to the folder that holds `settings.gradle.kts`. No Gradle wiring is involved.
 */
class SourceTreeGuardTest {

    private val root: File = ColorGuard.locateRoot()

    private fun allowlist(): Map<String, String> = ColorGuard.readAllowlist(root)

    @Test
    fun scopeContainsSourceFiles() {
        val files = ColorGuard.scopeFiles(root).map { ColorGuard.reportedPath(root, it) }
        assertTrue("guard scope found no files under $root", files.isNotEmpty())
        assertTrue(files.contains(ColorGuard.THEME_FILE))
        assertTrue(files.contains(ColorGuard.PALETTE_FILE))
    }

    @Test
    fun everyAllowlistEntryNamesAnExistingFile() {
        val missing = ColorGuard.missingAllowlistEntries(root, allowlist().keys)
        assertTrue("Allowlist entries naming no file: $missing", missing.isEmpty())
    }

    @Test
    fun noHardcodedColoursOutsideTheAllowlist() {
        val allowed = allowlist().keys
        val violations = ColorGuard.scopeFiles(root).flatMap { file ->
            ColorGuard.scanColors(ColorGuard.reportedPath(root, file), ColorGuard.readSourceLines(file), allowed)
        }
        assertTrue(ColorGuard.formatFailure(violations), violations.isEmpty())
    }

    @Test
    fun exactlyOneMaterialThemeInvocationAndItIsTheAppTheme() {
        val invocations = ColorGuard.scopeFiles(root).flatMap { file ->
            ColorGuard.scanThemeInvocations(ColorGuard.reportedPath(root, file), ColorGuard.readSourceLines(file))
        }
        assertEquals(
            "Expected one Material theme invocation, in ${ColorGuard.THEME_FILE}, found: $invocations",
            listOf(ColorGuard.THEME_FILE),
            invocations.map { it.path },
        )
    }

    @Test
    fun mainActivityHandlesUiModeChanges() {
        val manifest = File(root, ColorGuard.MANIFEST_RELATIVE_PATH)
        assertTrue("Manifest not found: $manifest", manifest.isFile)
        assertTrue(
            "MainActivity in ${ColorGuard.MANIFEST_RELATIVE_PATH} must declare android:configChanges containing uiMode",
            ColorGuard.mainActivityHandlesUiMode(manifest.readText(Charsets.UTF_8)),
        )
    }
}
