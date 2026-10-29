package com.w6tech.infinassistant.ui

import com.w6tech.infinassistant.core.data.appearance.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test

class AppearanceLabelsTest {

    @Test
    fun optionLabels_matchTheOnScreenNames() {
        assertEquals("System default", appearanceOptionLabel(ThemeMode.System))
        assertEquals("Light", appearanceOptionLabel(ThemeMode.Light))
        assertEquals("Dark", appearanceOptionLabel(ThemeMode.Dark))
    }

    @Test
    fun subtitle_systemNamesTheThemeInForce() {
        assertEquals("System default (Light)", appearanceSubtitle(ThemeMode.System, effectiveIsDark = false))
        assertEquals("System default (Dark)", appearanceSubtitle(ThemeMode.System, effectiveIsDark = true))
    }

    @Test
    fun subtitle_lightIgnoresTheSystemTheme() {
        assertEquals("Light", appearanceSubtitle(ThemeMode.Light, effectiveIsDark = false))
        assertEquals("Light", appearanceSubtitle(ThemeMode.Light, effectiveIsDark = true))
    }

    @Test
    fun subtitle_darkIgnoresTheSystemTheme() {
        assertEquals("Dark", appearanceSubtitle(ThemeMode.Dark, effectiveIsDark = false))
        assertEquals("Dark", appearanceSubtitle(ThemeMode.Dark, effectiveIsDark = true))
    }
}
