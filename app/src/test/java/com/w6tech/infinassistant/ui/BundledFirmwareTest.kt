package com.w6tech.infinassistant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledFirmwareTest {

    private fun entryJson(n: Int) =
        """{"file": "firmware/fw-$n.zip", "title": "Build $n", "detail": "Package number $n."}"""

    @Test
    fun parse_validListKeepsEveryEntryInOrder() {
        val json = """
            [
              {"file": "firmware/stock-2.4.64.zip", "title": "Stock 2.4.64", "detail": "The release build."},
              {"file": "firmware/diag-2.4.71.zip", "title": "Diagnostic 2.4.71", "detail": "Logs more. Use it to report a fault."},
              {"file": "firmware/old-2.4.60.zip", "title": "Previous 2.4.60", "detail": "The build before."}
            ]
        """.trimIndent()

        assertEquals(
            listOf(
                BundledFirmware("firmware/stock-2.4.64.zip", "Stock 2.4.64", "The release build."),
                BundledFirmware("firmware/diag-2.4.71.zip", "Diagnostic 2.4.71", "Logs more. Use it to report a fault."),
                BundledFirmware("firmware/old-2.4.60.zip", "Previous 2.4.60", "The build before."),
            ),
            parseFirmwareIndex(json),
        )
    }

    @Test
    fun parse_moreThanFourEntriesKeepsTheFirstFour() {
        val json = (1..6).joinToString(prefix = "[", postfix = "]") { entryJson(it) }

        assertEquals(
            listOf("firmware/fw-1.zip", "firmware/fw-2.zip", "firmware/fw-3.zip", "firmware/fw-4.zip"),
            parseFirmwareIndex(json).map { it.file },
        )
    }

    @Test
    fun parse_entriesWithMissingFieldsAreSkipped() {
        val json = """
            [
              {"title": "No file", "detail": "Skipped."},
              {"file": "firmware/a.zip", "detail": "No title, skipped."},
              {"file": "firmware/b.zip", "title": "No detail"},
              {"file": "firmware/c.zip", "title": "  ", "detail": "Blank title, skipped."},
              {"file": 7, "title": "A number for a file", "detail": "Skipped."},
              {"file": "firmware/d.zip", "title": null, "detail": "Null title, skipped."},
              {"file": "firmware/", "title": "No file name", "detail": "Skipped."},
              "not an object",
              {"file": " firmware/ok.zip ", "title": " Complete ", "detail": "Kept, trimmed."}
            ]
        """.trimIndent()

        assertEquals(
            listOf(BundledFirmware("firmware/ok.zip", "Complete", "Kept, trimmed.")),
            parseFirmwareIndex(json),
        )
    }

    @Test
    fun parse_skippedEntriesDoNotCountTowardsTheLimit() {
        val json = (listOf("""{"file": "firmware/bad.zip"}""") + (1..5).map { entryJson(it) })
            .joinToString(prefix = "[", postfix = "]")

        assertEquals(
            listOf("firmware/fw-1.zip", "firmware/fw-2.zip", "firmware/fw-3.zip", "firmware/fw-4.zip"),
            parseFirmwareIndex(json).map { it.file },
        )
    }

    @Test
    fun parse_garbageGivesAnEmptyList() {
        val garbage = listOf(
            "",
            "   ",
            "not json at all",
            "null",
            "42",
            "[]",
            """{"file": "firmware/a.zip", "title": "A", "detail": "An object, not a list."}""",
            """[{"file": "firmware/a.zip", "title": "A", "detail": "Cut off""",
            """[{"file": "firmware/a.zip", "title": "A", "detail": "Trailing comma."},]""",
            "\u0000\u0001\u0002",
        )
        for (input in garbage) {
            assertTrue("expected no entries for: $input", parseFirmwareIndex(input).isEmpty())
        }
    }

    @Test
    fun parse_leadingByteOrderMarkIsIgnored() {
        assertEquals(
            listOf("firmware/fw-1.zip"),
            parseFirmwareIndex("﻿[${entryJson(1)}]").map { it.file },
        )
    }

    @Test
    fun fileName_isThePackagesOwnFileName() {
        assertEquals(
            "pinetime-mcuboot-dfu-2.4.64.zip",
            BundledFirmware("firmware/pinetime-mcuboot-dfu-2.4.64.zip", "Stock", "The release build.").fileName,
        )
    }
}
