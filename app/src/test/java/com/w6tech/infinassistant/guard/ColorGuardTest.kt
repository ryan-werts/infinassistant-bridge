package com.w6tech.infinassistant.guard

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Matcher tests: the guard is checked against strings, not only against today's source tree. */
class ColorGuardTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val noAllowlist = emptySet<String>()

    private fun flagged(code: String): List<ColorGuard.Rule?> =
        ColorGuard.scanColors("android/x/Sample.kt", code.lines(), noAllowlist).map { it.rule }

    // --- rule 1: Color(...) built from numeric literals ---

    @Test
    fun hexConstructorIsFlagged() {
        assertEquals(listOf(ColorGuard.Rule.NUMERIC_CONSTRUCTOR), flagged("val c = Color(0xFF123456)"))
        assertEquals(listOf(ColorGuard.Rule.NUMERIC_CONSTRUCTOR), flagged("parseHexColor(x, Color(0xFF1A1A1A))"))
    }

    @Test
    fun fullyQualifiedHexConstructorIsFlagged() {
        val code = "private val guardSeed = androidx.compose.ui.graphics.Color(0xFF123456)"
        assertEquals(listOf(ColorGuard.Rule.NUMERIC_CONSTRUCTOR), flagged(code))
    }

    @Test
    fun numericComponentConstructorIsFlagged() {
        assertEquals(listOf(ColorGuard.Rule.NUMERIC_CONSTRUCTOR), flagged("Color(255, 128, 0)"))
        assertEquals(listOf(ColorGuard.Rule.NUMERIC_CONSTRUCTOR), flagged("Color(0.5f, 0.25f, 0.1f)"))
        assertEquals(listOf(ColorGuard.Rule.NUMERIC_CONSTRUCTOR), flagged("Color(red = 0.5f, green = 0f, blue = 0f)"))
    }

    @Test
    fun constructorSplitAcrossLinesIsFlaggedAtItsOwnLine() {
        val violations = ColorGuard.scanColors(
            "android/x/Sample.kt",
            listOf("val a = 1", "val c = Color(", "    0xFF123456,", ")"),
            noAllowlist,
        )
        assertEquals(listOf(2), violations.map { it.line })
    }

    /**
     * Decision: "numeric-component constructor" means an argument list that starts with a numeric
     * literal or a named colour component. `Color(argb)` in the theme converts a palette value, so
     * the literal lives in the palette, and it is not flagged.
     */
    @Test
    fun constructorAppliedToANonLiteralIsNotFlagged() {
        assertTrue(flagged("foreground = Color(foreground),").isEmpty())
        assertTrue(flagged("tint = Color(tint).copy(alpha = tintAlpha),").isEmpty())
        assertTrue(flagged("Color(android.graphics.Color.parseColor(\"#\$clean\"))").none { it == ColorGuard.Rule.NUMERIC_CONSTRUCTOR })
    }

    // --- rule 2: Color.<Name> constants ---

    @Test
    fun namedConstantsAreFlaggedExceptTransparentAndUnspecified() {
        assertEquals(listOf(ColorGuard.Rule.CONSTANT), flagged("drawText(\"x\", Color.White)"))
        assertEquals(listOf(ColorGuard.Rule.CONSTANT), flagged("val g = Color.Gray"))
        assertEquals(listOf(ColorGuard.Rule.CONSTANT), flagged("Color.White.copy(alpha = 0.7f)"))
        assertEquals(listOf(ColorGuard.Rule.CONSTANT), flagged("val r = androidx.compose.ui.graphics.Color.Red"))
        assertEquals(listOf(ColorGuard.Rule.CONSTANT), flagged("val n = Color.TransparentBlack"))
    }

    @Test
    fun transparentAndUnspecifiedAreNotFlagged() {
        assertTrue(flagged("val a = Color.Transparent").isEmpty())
        assertTrue(flagged("val b = Color.Unspecified").isEmpty())
        assertTrue(flagged("containerColor = Color.Transparent,").isEmpty())
    }

    // --- rule 3: android.graphics.Color. ---

    @Test
    fun platformColorIsFlaggedOnce() {
        assertEquals(listOf(ColorGuard.Rule.PLATFORM_COLOR), flagged("this.color = android.graphics.Color.argb(1, 2, 3, 4)"))
        assertEquals(listOf(ColorGuard.Rule.PLATFORM_COLOR), flagged("android.graphics.Color.parseColor(hex)"))
    }

    // --- non-violations, including near misses in this codebase ---

    @Test
    fun paletteReadsAndNearMissesAreNotFlagged() {
        val clean = listOf(
            "MaterialTheme.colorScheme.primary",
            "Text(color = MaterialTheme.colorScheme.onSurface)",
            "Color(argb).copy(alpha = 0.6f)",
            "x.copy(alpha = 0.6f)",
            "val c = parseHexColor(hex, fallback)",
            "poolColors.container",
            "AppColors.previewBoundary",
            "AppColors.semantic.success.foreground",
            "class StepCircleColors(val circle: Color, val glyph: Color)",
            "class RoleColors(",
            "fun previewBoundaryColor(scheme: ColorScheme): Color = scheme.onSurfaceVariant",
            "val s = colorScheme.primary",
            "LocalContentColor.current",
            "val tileColor = parseHexColor(tile.color, fallback)",
            "import androidx.compose.ui.graphics.Color",
        )
        for (line in clean) {
            assertTrue("should not be flagged: $line -> ${flagged(line)}", flagged(line).isEmpty())
        }
    }

    // --- comments ---

    /** Decision: whole-line comments are skipped; text after code on the same line is not. */
    @Test
    fun wholeLineCommentsAreSkipped() {
        assertTrue(flagged("// was Color(0xFF123456), now a palette role").isEmpty())
        assertTrue(flagged("    /* Color.Red */").isEmpty())
        assertTrue(flagged(" * Use Color.White sparingly").isEmpty())
        assertEquals(listOf(ColorGuard.Rule.CONSTANT), flagged("val c = Color.Red // not a comment line"))
    }

    // --- failure output and line numbers ---

    @Test
    fun violationCarriesPathLineAndText() {
        val v = ColorGuard.scanColors(
            "android/app/src/main/Home.kt",
            listOf("fun f() {", "    val c = Color(0xFF123456)", "}"),
            noAllowlist,
        ).single()
        assertEquals(2, v.line)
        assertEquals("android/app/src/main/Home.kt:2: val c = Color(0xFF123456)  [Color(...) built from numeric literals]", v.toString())
    }

    @Test
    fun failureMessageNamesEachViolationAndThePaletteFile() {
        val violations = ColorGuard.scanColors(
            "android/app/src/main/Home.kt",
            listOf("val a = Color.Red", "val b = Color(0xFF000001)"),
            noAllowlist,
        )
        val message = ColorGuard.formatFailure(violations)
        assertTrue(message, message.contains("android/app/src/main/Home.kt:1: val a = Color.Red"))
        assertTrue(message, message.contains("android/app/src/main/Home.kt:2: val b = Color(0xFF000001)"))
        assertTrue(message, message.contains("Add the colour to the palette instead: " + ColorGuard.PALETTE_FILE))
    }

    // --- allowlist ---

    @Test
    fun allowlistedFileWithALiteralPasses() {
        val lines = listOf("val bg = Color(0xFF1A1A1A)", "val w = Color.White")
        val path = "android/app/src/main/Preview.kt"
        assertEquals(2, ColorGuard.scanColors(path, lines, noAllowlist).size)
        assertTrue(ColorGuard.scanColors(path, lines, setOf(path)).isEmpty())
        assertEquals(2, ColorGuard.scanColors(path, lines, setOf("android/app/src/main/Other.kt")).size)
    }

    @Test
    fun allowlistParsingKeepsPathAndReasonAndSkipsCommentsAndBlanks() {
        val parsed = ColorGuard.parseAllowlist(
            listOf("# header", "", "android/a/B.kt | because of C", "  android/d/E.kt|  reason two  "),
        )
        assertEquals(mapOf("android/a/B.kt" to "because of C", "android/d/E.kt" to "reason two"), parsed)
    }

    @Test
    fun allowlistEntryWithoutAReasonIsRejected() {
        for (bad in listOf("android/a/B.kt", "android/a/B.kt |", "| just a reason")) {
            try {
                ColorGuard.parseAllowlist(listOf(bad))
                fail("expected a rejection for: $bad")
            } catch (expected: IllegalArgumentException) {
                assertNotNull(expected.message)
            }
        }
    }

    @Test
    fun allowlistEntryNamingAMissingFileIsReported() {
        val root = temp.newFolder("android")
        File(root, "app/src/main").mkdirs()
        File(root, "app/src/main/Present.kt").writeText("val a = 1")
        val missing = ColorGuard.missingAllowlistEntries(
            root,
            setOf("android/app/src/main/Present.kt", "android/app/src/main/Gone.kt"),
        )
        assertEquals(listOf("android/app/src/main/Gone.kt"), missing)
    }

    // --- portability ---

    @Test
    fun crlfAndLfFilesReadAlike() {
        val lf = temp.newFile("Lf.kt").apply { writeText("val a = 1\nval c = Color.Red\n") }
        val crlf = temp.newFile("Crlf.kt").apply { writeText("val a = 1\r\nval c = Color.Red\r\n") }
        assertEquals(ColorGuard.readSourceLines(lf), ColorGuard.readSourceLines(crlf))
        assertEquals(listOf("val a = 1", "val c = Color.Red"), ColorGuard.readSourceLines(crlf))
        val violation = ColorGuard.scanColors("android/x/Crlf.kt", ColorGuard.readSourceLines(crlf), noAllowlist).single()
        assertEquals(2, violation.line)
        assertFalse(violation.text.contains('\r'))
    }

    @Test
    fun rootIsFoundFromANestedDirectory() {
        val root = temp.newFolder("android")
        File(root, "settings.gradle.kts").writeText("")
        val nested = File(root, "app/build/tmp/deep").apply { mkdirs() }
        assertEquals(root.canonicalFile, ColorGuard.locateRoot(nested).canonicalFile)
    }

    @Test
    fun reportedPathsUseForwardSlashes() {
        val root = temp.newFolder("android")
        val file = File(root, "app/src/main/A.kt")
        assertEquals("android/app/src/main/A.kt", ColorGuard.reportedPath(root, file))
    }

    // --- Material theme invocation matcher ---

    private fun invocations(code: String) = ColorGuard.scanThemeInvocations("android/x/T.kt", code.lines()).size

    @Test
    fun themeInvocationsAreMatchedAndReadsAreNot() {
        assertEquals(1, invocations("MaterialTheme(colorScheme = scheme, content = content)"))
        assertEquals(1, invocations("MaterialTheme {"))
        assertEquals(1, invocations("    MaterialTheme (colorScheme = c) { Body() }"))
        assertEquals(1, invocations("androidx.compose.material3.MaterialTheme(colorScheme = c)"))
        assertEquals(0, invocations("Text(color = MaterialTheme.colorScheme.primary)"))
        assertEquals(0, invocations("style = MaterialTheme.typography.bodyMedium"))
        assertEquals(0, invocations("shape = MaterialTheme.shapes.medium"))
        assertEquals(0, invocations("import androidx.compose.material3.MaterialTheme"))
        assertEquals(0, invocations("// MaterialTheme(colorScheme = c)"))
        assertEquals(0, invocations("AppMaterialTheme(content)"))
    }

    // --- manifest matcher ---

    private fun manifest(activityAttributes: String, other: String = "") = """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
          <application>
            $other
            <activity android:name=".MainActivity" android:exported="true" $activityAttributes>
              <intent-filter/>
            </activity>
          </application>
        </manifest>
    """.trimIndent()

    @Test
    fun manifestMatcherRequiresUiModeOnMainActivity() {
        assertTrue(ColorGuard.mainActivityHandlesUiMode(manifest("""android:configChanges="uiMode"""")))
        assertTrue(ColorGuard.mainActivityHandlesUiMode(manifest("""android:configChanges="orientation|uiMode|screenSize"""")))
        assertFalse(ColorGuard.mainActivityHandlesUiMode(manifest("")))
        assertFalse(ColorGuard.mainActivityHandlesUiMode(manifest("""android:configChanges="orientation|screenSize"""")))
        assertFalse(ColorGuard.mainActivityHandlesUiMode(manifest("""android:configChanges="uiModeExtra"""")))
    }

    @Test
    fun manifestMatcherIgnoresUiModeOnAnotherActivity() {
        val other = """<activity android:name=".Other" android:configChanges="uiMode"/>"""
        assertFalse(ColorGuard.mainActivityHandlesUiMode(manifest("", other)))
    }
}
