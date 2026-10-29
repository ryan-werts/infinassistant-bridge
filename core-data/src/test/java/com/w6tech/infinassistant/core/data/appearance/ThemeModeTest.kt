package com.w6tech.infinassistant.core.data.appearance

import com.w6tech.infinassistant.core.common.Redactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeTest {

    @Test
    fun resolver_coversAllSixCombinations() {
        assertFalse(isEffectivelyDark(ThemeMode.System, systemIsDark = false))
        assertTrue(isEffectivelyDark(ThemeMode.System, systemIsDark = true))
        assertFalse(isEffectivelyDark(ThemeMode.Light, systemIsDark = false))
        assertFalse(isEffectivelyDark(ThemeMode.Light, systemIsDark = true))
        assertTrue(isEffectivelyDark(ThemeMode.Dark, systemIsDark = false))
        assertTrue(isEffectivelyDark(ThemeMode.Dark, systemIsDark = true))
    }

    @Test
    fun codec_absentParsesToSystem() {
        assertEquals(ThemeMode.System, parseThemeMode(null))
    }

    @Test
    fun codec_eachValidValueRoundTrips() {
        for (mode in ThemeMode.entries) {
            assertEquals(mode, parseThemeMode(encodeThemeMode(mode)))
        }
        assertEquals(ThemeMode.System, parseThemeMode("System"))
        assertEquals(ThemeMode.Light, parseThemeMode("Light"))
        assertEquals(ThemeMode.Dark, parseThemeMode("Dark"))
    }

    @Test
    fun codec_emptyParsesToSystem() {
        assertEquals(ThemeMode.System, parseThemeMode(""))
    }

    @Test
    fun codec_unrecognisedParsesToSystem() {
        assertEquals(ThemeMode.System, parseThemeMode("sepia"))
    }

    @Test
    fun reconcile_emptyStoreWithPlatformValuePresent_givesFollowSystem() {
        for (platform in PlatformNightMode.entries) {
            assertEquals(PlatformNightMode.FollowSystem, reconcilePlatformNightMode(null, platform))
        }
    }

    @Test
    fun reconcile_storeAndPlatformDisagree_givesTheStoresValue() {
        assertEquals(
            PlatformNightMode.Dark,
            reconcilePlatformNightMode(ThemeMode.Dark, PlatformNightMode.Light),
        )
        assertEquals(
            PlatformNightMode.Light,
            reconcilePlatformNightMode(ThemeMode.Light, PlatformNightMode.Dark),
        )
        assertEquals(
            PlatformNightMode.FollowSystem,
            reconcilePlatformNightMode(ThemeMode.System, PlatformNightMode.Dark),
        )
    }

    @Test
    fun reconcile_platformValueNeverChangesTheAnswer() {
        val platformValues = PlatformNightMode.entries + null
        for (stored in listOf<ThemeMode?>(null) + ThemeMode.entries) {
            val answers = platformValues.map { reconcilePlatformNightMode(stored, it) }.toSet()
            assertEquals("stored=$stored", 1, answers.size)
        }
    }

    @Test
    fun formatter_producesTheDiagnosticLine() {
        assertEquals("appearance mode=System effective=dark", formatAppearanceLine(ThemeMode.System, true))
        assertEquals("appearance mode=Light effective=light", formatAppearanceLine(ThemeMode.Light, false))
        assertEquals("appearance mode=Dark effective=dark", formatAppearanceLine(ThemeMode.Dark, true))
    }

    @Test
    fun formatter_lineIsUnchangedByRedactor() {
        for (mode in ThemeMode.entries) {
            for (dark in listOf(false, true)) {
                val line = formatAppearanceLine(mode, dark)
                assertEquals(line, Redactor.redact(line))
            }
        }
    }
}
