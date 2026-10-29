package com.w6tech.infinassistant.core.data

import com.w6tech.infinassistant.core.data.bridge.ForwardDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lines that tell a bundle where NOTIFYs came from and why phone notifications were not forwarded. */
class ForwardDiagnosticsTest {

    private val lines = mutableListOf<Pair<String, String>>()
    private var nowMs = 0L
    private val diag = ForwardDiagnostics({ msg, extra -> lines += msg to extra }, { nowMs })

    @Test
    fun `each origin is one line, the phone's with its package name`() {
        diag.fromHa()
        diag.fromPhone("com.example.chat")
        assertEquals(
            listOf(HealthSummary.NOTIFY_HA to "", HealthSummary.NOTIFY_PHONE to "pkg=com.example.chat"),
            lines,
        )
    }

    @Test
    fun `skips are counted and written as one line a flush period`() {
        repeat(500) { diag.skipped("ongoing") }
        repeat(3) { diag.skipped("silent") }
        assertTrue("nothing written yet", lines.isEmpty())
        nowMs = ForwardDiagnostics.FLUSH_MS
        diag.skipped("silent")
        assertEquals(listOf(HealthSummary.NOTIFY_SKIPS to "ongoing=500 silent=4"), lines)
        // Counted from zero again, and nothing is written for nothing.
        diag.flush()
        assertEquals(1, lines.size)
        diag.skipped("empty")
        diag.flush()
        assertEquals(HealthSummary.NOTIFY_SKIPS to "empty=1", lines.last())
    }

    @Test
    fun `anything but a package name or a reason word is cut to a safe alphabet and length`() {
        // A title or text could only get here by mistake: spaces, punctuation and emoji do not survive.
        diag.fromPhone("Alice: dinner at 7? 😀")
        diag.fromPhone("a".repeat(500))
        diag.skipped("two words")
        diag.flush()
        assertEquals("pkg=Alice??dinner?at?7???", lines[0].second)
        assertEquals("pkg=" + "a".repeat(100), lines[1].second)
        assertEquals("two?words=1", lines[2].second)
    }
}
