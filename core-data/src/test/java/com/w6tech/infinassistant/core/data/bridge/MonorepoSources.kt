package com.w6tech.infinassistant.core.data.bridge

import java.io.File

/**
 * Files that live outside `android/` in the InfinAssistant monorepo (firmware sources, fuzz seeds).
 * The public bridge repo has no such tree, so [find] returns null there and a test that compares a
 * vendored fixture with its source skips instead of failing.
 */
internal object MonorepoSources {

    /** The file at [relative], found by walking up from the test's working directory, or null. */
    fun find(relative: String): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return null
    }
}
