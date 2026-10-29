package com.w6tech.infinassistant.core.data.appearance

/** The user's appearance choice. [System] follows the device's dark-mode setting. */
enum class ThemeMode { System, Light, Dark }

/** The value the platform's night-mode setting is asserted to hold. */
enum class PlatformNightMode { FollowSystem, Light, Dark }

/** True when the effective theme is dark for [mode] given the device's [systemIsDark] state. */
fun isEffectivelyDark(mode: ThemeMode, systemIsDark: Boolean): Boolean = when (mode) {
    ThemeMode.System -> systemIsDark
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

/** The string persisted for [mode]. */
fun encodeThemeMode(mode: ThemeMode): String = mode.name

/** Parses a persisted string; absent, empty and unrecognised input all yield [ThemeMode.System]. */
fun parseThemeMode(raw: String?): ThemeMode =
    ThemeMode.entries.firstOrNull { it.name == raw } ?: ThemeMode.System

/**
 * The platform night-mode value to assert at start-up. Only the stored mode decides it;
 * [platformPresent] is deliberately ignored so a stale platform value can never win over the store.
 */
@Suppress("UNUSED_PARAMETER")
fun reconcilePlatformNightMode(stored: ThemeMode?, platformPresent: PlatformNightMode?): PlatformNightMode =
    when (stored) {
        null, ThemeMode.System -> PlatformNightMode.FollowSystem
        ThemeMode.Light -> PlatformNightMode.Light
        ThemeMode.Dark -> PlatformNightMode.Dark
    }

/** The diagnostic line `appearance mode=<MODE> effective=<light or dark>`. */
fun formatAppearanceLine(mode: ThemeMode, effectiveDark: Boolean): String =
    "appearance mode=${encodeThemeMode(mode)} effective=${if (effectiveDark) "dark" else "light"}"
