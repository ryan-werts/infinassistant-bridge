package com.w6tech.infinassistant.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** A semantic role's colours as Compose colours. [tint] already carries its alpha. */
@Immutable
class RoleColors(
    val foreground: Color,
    val container: Color,
    val onContainer: Color,
    val tint: Color,
)

/** The three semantic roles for the current theme. */
@Immutable
class SemanticColors(
    val success: RoleColors,
    val warning: RoleColors,
    val danger: RoleColors,
) {
    fun forRole(role: StatusRole): RoleColors = when (role) {
        StatusRole.Success -> success
        StatusRole.Warning -> warning
        StatusRole.Danger -> danger
    }
}

/** Circle and glyph colours of a step marker that is not done. */
@Immutable
class StepCircleColors(val circle: Color, val glyph: Color)

private fun RolePalette.toColors() = RoleColors(
    foreground = Color(foreground),
    container = Color(container),
    onContainer = Color(onContainer),
    tint = Color(tint).copy(alpha = tintAlpha),
)

/** The semantic colours of one theme, built from the palette. Usable off the composition. */
fun semanticColors(dark: Boolean): SemanticColors {
    val set = SemanticPalette.forTheme(dark)
    return SemanticColors(
        success = set.success.toColors(),
        warning = set.warning.toColors(),
        danger = set.danger.toColors(),
    )
}

/** The Material 3 baseline scheme for one theme, with no customisation. Shared by theme and tests. */
fun baselineColorScheme(dark: Boolean): ColorScheme =
    if (dark) darkColorScheme() else lightColorScheme()

/** Colour of the boundary drawn around a watch face preview, over the given scheme. */
fun previewBoundaryColor(scheme: ColorScheme): Color = scheme.onSurfaceVariant

/** Not-done marker in the onboarding progress row: outline-toned circle, card-toned glyph. */
fun progressStepNotDoneColors(scheme: ColorScheme): StepCircleColors =
    StepCircleColors(circle = scheme.onSurfaceVariant, glyph = scheme.surfaceVariant)

/** Not-done marker in an onboarding step card: primary circle with its own on-primary glyph. */
fun cardStepNotDoneColors(scheme: ColorScheme): StepCircleColors =
    StepCircleColors(circle = scheme.primary, glyph = scheme.onPrimary)

private val LocalSemanticColors = staticCompositionLocalOf { semanticColors(dark = false) }

/** Reads the app's own colours for the current theme from a composable. */
object AppColors {
    /** The semantic roles: `AppColors.semantic.success.foreground`, `.container`, `.onContainer`, `.tint`. */
    val semantic: SemanticColors
        @Composable @ReadOnlyComposable get() = LocalSemanticColors.current

    @Composable
    @ReadOnlyComposable
    fun status(role: StatusRole): RoleColors = LocalSemanticColors.current.forRole(role)

    val previewBoundary: Color
        @Composable @ReadOnlyComposable get() = previewBoundaryColor(MaterialTheme.colorScheme)

    val progressStepNotDone: StepCircleColors
        @Composable @ReadOnlyComposable get() = progressStepNotDoneColors(MaterialTheme.colorScheme)

    val cardStepNotDone: StepCircleColors
        @Composable @ReadOnlyComposable get() = cardStepNotDoneColors(MaterialTheme.colorScheme)
}

/**
 * The app theme: Material 3 baseline colours, light or dark, no dynamic colour, plus the
 * semantic palette read through [AppColors].
 */
@Composable
fun AppTheme(dark: Boolean, content: @Composable () -> Unit) {
    val semantic = remember(dark) { semanticColors(dark) }
    CompositionLocalProvider(LocalSemanticColors provides semantic) {
        MaterialTheme(colorScheme = baselineColorScheme(dark), content = content)
    }
}
