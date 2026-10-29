package com.w6tech.infinassistant.core.ui.theme

/**
 * The one place to add or change a semantic colour.
 *
 * Values are plain ARGB integers so this file compiles and runs on the JVM with no UI library;
 * the theme file turns them into Compose colours. Every value here is pinned by the contrast
 * test, which computes the WCAG ratio of each usage pair in both themes: change a value and run
 * that test before shipping it.
 *
 * Each role has, per theme:
 *  - foreground: text, glyphs and indicators drawn directly on a surface;
 *  - container and on-container: a filled shape and the content drawn on it;
 *  - tint: a subtle container colour, applied at [RolePalette.tintAlpha] over its backdrop.
 */
class RolePalette(
    val foreground: Int,
    val container: Int,
    val onContainer: Int,
    val tint: Int,
    val tintAlpha: Float,
)

/** The three semantic roles for one theme. */
class SemanticPaletteSet(
    val success: RolePalette,
    val warning: RolePalette,
    val danger: RolePalette,
) {
    fun forRole(role: StatusRole): RolePalette = when (role) {
        StatusRole.Success -> success
        StatusRole.Warning -> warning
        StatusRole.Danger -> danger
    }
}

object SemanticPalette {

    val light = SemanticPaletteSet(
        success = RolePalette(
            foreground = 0xFF1B6B2A.toInt(),
            container = 0xFF2E7D32.toInt(),
            onContainer = 0xFFFFFFFF.toInt(),
            tint = 0xFF2E7D32.toInt(),
            tintAlpha = 0.10f,
        ),
        warning = RolePalette(
            foreground = 0xFF8A5300.toInt(),
            container = 0xFFA35F00.toInt(),
            onContainer = 0xFFFFFFFF.toInt(),
            tint = 0xFFA35F00.toInt(),
            tintAlpha = 0.12f,
        ),
        danger = RolePalette(
            foreground = 0xFFB3261E.toInt(),
            container = 0xFFC62828.toInt(),
            onContainer = 0xFFFFFFFF.toInt(),
            tint = 0xFFC62828.toInt(),
            tintAlpha = 0.10f,
        ),
    )

    val dark = SemanticPaletteSet(
        success = RolePalette(
            foreground = 0xFF81C784.toInt(),
            container = 0xFF4CAF50.toInt(),
            onContainer = 0xFF00210A.toInt(),
            tint = 0xFF81C784.toInt(),
            tintAlpha = 0.16f,
        ),
        warning = RolePalette(
            foreground = 0xFFFFB74D.toInt(),
            container = 0xFFF9A825.toInt(),
            onContainer = 0xFF2B1700.toInt(),
            tint = 0xFFFFB74D.toInt(),
            tintAlpha = 0.16f,
        ),
        danger = RolePalette(
            foreground = 0xFFFF9E97.toInt(),
            container = 0xFFE57373.toInt(),
            onContainer = 0xFF2D0004.toInt(),
            tint = 0xFFFF8A80.toInt(),
            tintAlpha = 0.16f,
        ),
    )

    fun forTheme(dark: Boolean): SemanticPaletteSet = if (dark) this.dark else light
}
