package com.w6tech.infinassistant.core.ui.theme

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * WCAG 2.1 contrast of every usage pair of the semantic palette, in both themes. Ratios are
 * computed from the palette and from the Material 3 baseline scheme the theme itself uses;
 * none is typed in by hand.
 */
class SemanticPaletteContrastTest {

    private class Rgb(val r: Double, val g: Double, val b: Double)

    private class Case(val name: String, val fg: Rgb, val bg: Rgb, val threshold: Double)

    private fun Int.rgb() = Rgb(
        ((this shr 16) and 0xFF) / 255.0,
        ((this shr 8) and 0xFF) / 255.0,
        (this and 0xFF) / 255.0,
    )

    /** Source-over compositing of [this] at [alpha] onto an opaque backdrop. */
    private fun Rgb.over(bg: Rgb, alpha: Double) = Rgb(
        alpha * r + (1 - alpha) * bg.r,
        alpha * g + (1 - alpha) * bg.g,
        alpha * b + (1 - alpha) * bg.b,
    )

    private fun channel(c: Double) = if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun luminance(c: Rgb) = 0.2126 * channel(c.r) + 0.7152 * channel(c.g) + 0.0722 * channel(c.b)

    private fun contrast(a: Rgb, b: Rgb): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun casesFor(dark: Boolean): List<Case> {
        val scheme = baselineColorScheme(dark)
        val palette = SemanticPalette.forTheme(dark)
        val background = scheme.background.toArgb().rgb()
        val surface = scheme.surface.toArgb().rgb()
        val surfaceVariant = scheme.surfaceVariant.toArgb().rgb()
        val surfaceVariant60 = surfaceVariant.over(background, 0.6)

        val cases = mutableListOf<Case>()
        fun add(name: String, fg: Rgb, bg: Rgb, threshold: Double) {
            cases += Case(name, fg, bg, threshold)
        }

        val success = palette.success
        val warning = palette.warning
        val danger = palette.danger
        val roles = listOf("success" to success, "warning" to warning, "danger" to danger)

        // Status dots: foreground on surfaceVariant (HomeScreen, ConnectedModeScreen).
        for ((n, p) in roles) add("$n foreground dot on surfaceVariant", p.foreground.rgb(), surfaceVariant, 3.0)
        add("warning foreground text on surfaceVariant", warning.foreground.rgb(), surfaceVariant, 4.5)
        add("success foreground text on surfaceVariant (heap pool percentage, WatchFaceImportScreen)", success.foreground.rgb(), surfaceVariant, 4.5)
        add("danger foreground text on surfaceVariant (heap pool percentage, WatchFaceImportScreen)", danger.foreground.rgb(), surfaceVariant, 4.5)

        // Success text in the onboarding step card, both card backdrops.
        add("success foreground text on surface", success.foreground.rgb(), surface, 4.5)
        add("success foreground text on surfaceVariant at 60% over background", success.foreground.rgb(), surfaceVariant60, 4.5)

        // Success text on the tint container.
        val tintOverBackground = success.tint.rgb().over(background, success.tintAlpha.toDouble())
        add("success foreground text on success tint over background", success.foreground.rgb(), tintOverBackground, 4.5)

        // Connector line.
        add("success foreground connector on background", success.foreground.rgb(), background, 3.0)

        // Done circle: container on each backdrop, glyph on the container.
        add("success container circle on background", success.container.rgb(), background, 3.0)
        add("success container circle on surface", success.container.rgb(), surface, 3.0)
        add("success container circle on surfaceVariant at 60% over background", success.container.rgb(), surfaceVariant60, 3.0)
        add("success on-container glyph on container", success.onContainer.rgb(), success.container.rgb(), 4.5)

        // Heap badge: container on surfaceVariant, label on container.
        for ((n, p) in roles) {
            add("$n container badge on surfaceVariant", p.container.rgb(), surfaceVariant, 3.0)
            add("$n on-container label on container", p.onContainer.rgb(), p.container.rgb(), 4.5)
        }

        // Preview boundary, once per embed site.
        val boundary = previewBoundaryColor(scheme).toArgb().rgb()
        add("preview boundary on surfaceVariant (watch face import card)", boundary, surfaceVariant, 3.0)
        add("preview boundary on surfaceVariant (template gallery card)", boundary, surfaceVariant, 3.0)

        // Not-done step circles.
        val progress = progressStepNotDoneColors(scheme)
        val card = cardStepNotDoneColors(scheme)
        add("progress step not-done glyph on circle", progress.glyph.toArgb().rgb(), progress.circle.toArgb().rgb(), 4.5)
        add("card step not-done glyph on circle", card.glyph.toArgb().rgb(), card.circle.toArgb().rgb(), 4.5)
        add("card step not-done circle on surface", card.circle.toArgb().rgb(), surface, 3.0)
        add("progress step not-done circle on background", progress.circle.toArgb().rgb(), background, 3.0)

        return cases
    }

    private fun check(dark: Boolean) {
        val theme = if (dark) "dark" else "light"
        val cases = casesFor(dark)
        val failures = cases.mapNotNull { c ->
            val ratio = contrast(c.fg, c.bg)
            if (ratio < c.threshold) "[$theme] ${c.name}: ratio %.2f below threshold %.1f".format(ratio, c.threshold) else null
        }
        for (threshold in listOf(3.0, 4.5)) {
            val lowest = cases.filter { it.threshold == threshold }.minByOrNull { contrast(it.fg, it.bg) }!!
            println("[$theme] lowest ratio at %.1f: %.2f (%s)".format(threshold, contrast(lowest.fg, lowest.bg), lowest.name))
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun lightThemeMeetsEveryThreshold() = check(dark = false)

    @Test
    fun darkThemeMeetsEveryThreshold() = check(dark = true)

    @Test
    fun everyListedPairIsPresentInBothThemes() {
        assertEquals(26, casesFor(dark = false).size)
        assertEquals(26, casesFor(dark = true).size)
    }

    @Test
    fun contrastFunctionMatchesKnownWcagValues() {
        val black = 0xFF000000.toInt().rgb()
        val white = 0xFFFFFFFF.toInt().rgb()
        assertEquals(21.0, contrast(black, white), 1e-9)
        assertEquals(1.0, contrast(white, white), 1e-9)
    }

    @Test
    fun compositingRespectsAlphaEndpoints() {
        val fg = 0xFF336699.toInt().rgb()
        val bg = 0xFFFFFFFF.toInt().rgb()
        assertEquals(contrast(fg, bg), contrast(fg.over(bg, 1.0), bg), 1e-9)
        assertEquals(1.0, contrast(fg.over(bg, 0.0), bg), 1e-9)
    }
}
