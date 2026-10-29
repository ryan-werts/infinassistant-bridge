package com.w6tech.infinassistant.ui

import com.w6tech.infinassistant.core.data.appearance.ThemeMode

/** The name shown for a mode in the appearance dialog. */
fun appearanceOptionLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.System -> "System default"
    ThemeMode.Light -> "Light"
    ThemeMode.Dark -> "Dark"
}

/**
 * The subtitle of the Appearance row on Home. For the system mode it names the theme actually in
 * force, so [effectiveIsDark] only matters there.
 */
fun appearanceSubtitle(mode: ThemeMode, effectiveIsDark: Boolean): String = when (mode) {
    ThemeMode.System -> if (effectiveIsDark) "System default (Dark)" else "System default (Light)"
    ThemeMode.Light -> "Light"
    ThemeMode.Dark -> "Dark"
}
