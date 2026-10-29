package com.w6tech.infinassistant.guard

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Static guard for hardcoded colours. Pure JVM: reads source files from disk, needs no shell and
 * no Gradle wiring, and compares paths with `/` separators so it behaves the same on Windows and
 * on the Linux CI runner.
 *
 * All paths this class reports are of the form `android/<path relative to the Gradle root>`.
 */
object ColorGuard {

    /** The place to add a colour instead of writing a literal. */
    const val PALETTE_FILE =
        "android/core-ui/src/main/java/com/w6tech/infinassistant/core/ui/theme/SemanticPalette.kt"

    /** The one file that may invoke the Material theme. */
    const val THEME_FILE =
        "android/core-ui/src/main/java/com/w6tech/infinassistant/core/ui/theme/AppTheme.kt"

    /** Allowlist location, relative to the located Gradle root. Read by path, not from the classpath. */
    const val ALLOWLIST_RELATIVE_PATH = "app/src/test/resources/color-guard-allowlist.txt"

    const val MANIFEST_RELATIVE_PATH = "app/src/main/AndroidManifest.xml"

    /** Directories scanned for `.kt` files, relative to the Gradle root. A missing one is skipped. */
    val SCOPE_DIRECTORIES = listOf(
        "app/src/main",
        "app/src/debug",
        "app/src/release",
        "core-ui/src/main",
    )

    private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

    enum class Rule(val label: String) {
        NUMERIC_CONSTRUCTOR("Color(...) built from numeric literals"),
        CONSTANT("Color.<Name> constant"),
        PLATFORM_COLOR("android.graphics.Color use"),
    }

    class Violation(val path: String, val line: Int, val text: String, val rule: Rule?) {
        override fun toString(): String = "$path:$line: $text" + (rule?.let { "  [${it.label}]" } ?: "")
    }

    // A numeric literal: hex, decimal, float, with optional sign and Kotlin suffixes.
    private const val NUMBER =
        """[-+]?(?:0[xX][0-9A-Fa-f_]+|\d[\d_]*(?:\.\d+)?(?:[eE][-+]?\d+)?|\.\d+)[fFuUlL]*"""

    /**
     * Decision (numeric-component constructor): `Color(` is flagged when its argument list starts
     * with a numeric literal (`Color(0xFF…)`, `Color(255, 0, 0)`, `Color(0.5f, …)`) or with a named
     * colour component (`Color(red = …)`). `Color(argb)` and any other non-literal argument is NOT
     * flagged: the value comes from somewhere else, which is where a literal would be caught.
     */
    private val NUMERIC_CONSTRUCTOR =
        Regex("""(?<![A-Za-z0-9_])Color\(\s*(?:$NUMBER|(?:red|green|blue|alpha|colorSpace)\s*=)""")

    /**
     * `Color.<Name>`, bounded on the left so `poolColors.`, `AppColors.`, `LocalContentColor.` and
     * `colorScheme.` cannot match, and on the right by a whole identifier that is compared with the
     * two permitted names. The `android.graphics.` form is left to [PLATFORM_COLOR].
     */
    private val CONSTANT =
        Regex("""(?<![A-Za-z0-9_])(?<!android\.graphics\.)Color\.([A-Za-z_][A-Za-z0-9_]*)""")

    private val PLATFORM_COLOR = Regex("""(?<![A-Za-z0-9_])android\.graphics\.Color\.""")

    private val ALLOWED_CONSTANTS = setOf("Transparent", "Unspecified")

    /** `MaterialTheme(` or `MaterialTheme {`; reads such as `MaterialTheme.colorScheme` do not match. */
    private val THEME_INVOCATION = Regex("""(?<![A-Za-z0-9_])MaterialTheme\s*[({]""")

    /**
     * Decision (comments): whole-line comments (a line starting with a double slash, a block
     * comment opener, or the star of a KDoc line) are skipped, so a comment may name a colour.
     * Text after code on the same line is still scanned.
     */
    private fun isWholeLineComment(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")
    }

    /** Joins the lines with comment lines emptied, so line numbers stay put and a call may span lines. */
    private fun codeText(lines: List<String>): String =
        lines.joinToString("\n") { if (isWholeLineComment(it)) "" else it }

    private fun lineOf(text: String, offset: Int): Int = text.substring(0, offset).count { it == '\n' } + 1

    private fun hits(path: String, lines: List<String>, regex: Regex, rule: Rule?, accept: (MatchResult) -> Boolean = { true }): List<Violation> {
        val text = codeText(lines)
        return regex.findAll(text).filter(accept).map {
            val line = lineOf(text, it.range.first)
            Violation(path, line, lines[line - 1].trim(), rule)
        }.toList()
    }

    /**
     * Colour violations in one file's lines. A file named in [allowlist] is exempt and yields
     * nothing. At most one violation is reported per line and rule.
     */
    fun scanColors(path: String, lines: List<String>, allowlist: Set<String>): List<Violation> {
        if (path in allowlist) return emptyList()
        return (
            hits(path, lines, NUMERIC_CONSTRUCTOR, Rule.NUMERIC_CONSTRUCTOR) +
                hits(path, lines, CONSTANT, Rule.CONSTANT) { it.groupValues[1] !in ALLOWED_CONSTANTS } +
                hits(path, lines, PLATFORM_COLOR, Rule.PLATFORM_COLOR)
            ).distinctBy { it.line to it.rule }.sortedWith(compareBy({ it.line }, { it.rule }))
    }

    /** Material theme invocations in one file's lines (not allowlist-aware: the count is global). */
    fun scanThemeInvocations(path: String, lines: List<String>): List<Violation> =
        hits(path, lines, THEME_INVOCATION, null)

    /** The failure message: every violation as `path:line`, plus where to add a colour instead. */
    fun formatFailure(violations: List<Violation>): String = buildString {
        append("Hardcoded colours outside the palette (").append(violations.size).append("):\n")
        violations.forEach { append("  ").append(it).append('\n') }
        append("Add the colour to the palette instead: ").append(PALETTE_FILE).append('\n')
        append("(a file that must own its colours goes in android/").append(ALLOWLIST_RELATIVE_PATH)
        append(" with a one-line reason)")
    }

    /**
     * Allowlist format: one entry per line, `<path> | <reason>`; blank lines and `#` comment lines
     * are ignored. The path is repo-relative in the same form as the reported paths. Returns path to
     * reason. An entry with no path or no reason is an error.
     */
    fun parseAllowlist(lines: List<String>): Map<String, String> {
        val entries = LinkedHashMap<String, String>()
        lines.forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val bar = line.indexOf('|')
            val path = if (bar < 0) line else line.substring(0, bar).trim()
            val reason = if (bar < 0) "" else line.substring(bar + 1).trim()
            require(path.isNotEmpty() && reason.isNotEmpty()) {
                "Allowlist line ${index + 1} must be '<path> | <reason>': $raw"
            }
            require(entries.put(path, reason) == null) { "Allowlist line ${index + 1} repeats $path" }
        }
        return entries
    }

    /** Allowlisted paths that name no file under [root]. */
    fun missingAllowlistEntries(root: File, allowlist: Set<String>): List<String> =
        allowlist.filter { !fileFor(root, it).isFile }

    private fun fileFor(root: File, reportedPath: String): File =
        File(root, reportedPath.removePrefix("android/"))

    /** `android/<relative path>` with `/` separators, whatever the host separator is. */
    fun reportedPath(root: File, file: File): String =
        "android/" + file.relativeTo(root).path.replace('\\', '/')

    /**
     * The Gradle root: the nearest directory at or above [start] holding `settings.gradle.kts`.
     * Nothing is assumed about the test task's working directory.
     */
    fun locateRoot(start: File): File {
        var dir: File? = start.absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("No settings.gradle.kts at or above $start")
    }

    fun locateRoot(): File = locateRoot(File(System.getProperty("user.dir") ?: "."))

    /** Lines of a text file; `\r\n` and `\n` give the same result. */
    fun readSourceLines(file: File): List<String> = file.readLines(Charsets.UTF_8)

    /** Every `.kt` file in scope under [root], sorted by reported path. */
    fun scopeFiles(root: File): List<File> =
        SCOPE_DIRECTORIES.map { File(root, it) }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .sortedBy { reportedPath(root, it) }

    fun readAllowlist(root: File): Map<String, String> =
        parseAllowlist(readSourceLines(File(root, ALLOWLIST_RELATIVE_PATH)))

    /**
     * True when the `activity` element named `MainActivity` declares `android:configChanges`
     * with `uiMode` among its `|`-separated values.
     */
    fun mainActivityHandlesUiMode(manifestXml: String): Boolean {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(manifestXml.byteInputStream(Charsets.UTF_8))
        val activities = doc.getElementsByTagName("activity")
        for (i in 0 until activities.length) {
            val activity = activities.item(i) as Element
            val name = activity.getAttributeNS(ANDROID_NAMESPACE, "name")
            if (name == "MainActivity" || name.endsWith(".MainActivity")) {
                val changes = activity.getAttributeNS(ANDROID_NAMESPACE, "configChanges")
                return changes.split('|').map { it.trim() }.contains("uiMode")
            }
        }
        return false
    }
}
