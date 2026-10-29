package com.w6tech.infinassistant.core.data

import org.json.JSONObject

/**
 * REQ-043 — DFU version floor.
 *
 * Rejects firmware manifests below [MIN_DFU_VERSION] because the bridge phone
 * app depends on protocol features the older images lack (HELLO diagnostics,
 * CONFIG_PUSH_ACK, MTU negotiation, and since 2.4.103 the renamed CONFIG key:
 * an older image ignores it, so the app could not configure the watch). Flashing
 * an older image would leave the bridge unusable until the user re-flashed a
 * recent build over USB, which is difficult on a watch device.
 *
 * Pure logic, no Android dependencies — testable in core-data's JVM test set.
 */
object DfuVersionGuard {
    /** Lowest acceptable DFU manifest version (semver). */
    const val MIN_DFU_VERSION = "2.4.103"

    sealed interface VersionCheckResult {
        data class Ok(val version: String) : VersionCheckResult
        data class TooOld(val version: String, val floor: String) : VersionCheckResult
        data object Unparseable : VersionCheckResult
    }

    /** Parse "2.4.103" → Triple(2, 4, 103). Returns null on malformed input. */
    private fun parseSemver(s: String): Triple<Int, Int, Int>? {
        val parts = s.split('.')
        if (parts.size != 3) return null
        val n = parts.map { it.toIntOrNull() ?: return null }
        return Triple(n[0], n[1], n[2])
    }

    /** Compares semver strings; returns negative when [a] < [b], 0 if equal or unparseable. */
    fun compareVersions(a: String, b: String): Int {
        val pa = parseSemver(a) ?: return 0
        val pb = parseSemver(b) ?: return 0
        return compareValuesBy(pa, pb, { it.first }, { it.second }, { it.third })
    }

    /**
     * Extract the firmware version from a DFU manifest.json. The manifest is
     * shaped:
     *
     *   {"manifest":{"application":{"bin_file":"pinetime-mcuboot-app-image-2.4.103.bin", ...}}}
     *
     * Some builds drop the "image-" infix; both forms are accepted.
     */
    fun parseManifestVersion(manifestJsonBytes: ByteArray): String? {
        return try {
            val json = JSONObject(String(manifestJsonBytes, Charsets.UTF_8))
            val binFile = json.optJSONObject("manifest")
                ?.optJSONObject("application")
                ?.optString("bin_file")
                ?: return null
            val regex = Regex("""pinetime-mcuboot-app-(?:image-)?(\d+\.\d+\.\d+)\.bin""")
            regex.find(binFile)?.groupValues?.get(1)
        } catch (_: Exception) {
            null
        }
    }

    /** End-to-end check: manifest bytes → ok / too-old / unparseable. */
    fun checkManifest(manifestJsonBytes: ByteArray): VersionCheckResult {
        val version = parseManifestVersion(manifestJsonBytes) ?: return VersionCheckResult.Unparseable
        return if (compareVersions(version, MIN_DFU_VERSION) >= 0) {
            VersionCheckResult.Ok(version)
        } else {
            VersionCheckResult.TooOld(version, MIN_DFU_VERSION)
        }
    }
}
