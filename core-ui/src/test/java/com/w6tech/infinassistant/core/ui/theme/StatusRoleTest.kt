package com.w6tech.infinassistant.core.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StatusRoleTest {

    @Test
    fun heapPoolBoundaries() {
        assertEquals(StatusRole.Success, heapPoolStatusRole(0))
        assertEquals(StatusRole.Success, heapPoolStatusRole(49))
        assertEquals(StatusRole.Warning, heapPoolStatusRole(50))
        assertEquals(StatusRole.Warning, heapPoolStatusRole(80))
        assertEquals(StatusRole.Danger, heapPoolStatusRole(81))
        assertEquals(StatusRole.Danger, heapPoolStatusRole(100))
    }

    @Test
    fun connectionStatusForAllEightCombinations() {
        // ble, ha, responsive -> expected
        val table = listOf(
            Triple(true, true, true) to StatusRole.Success,
            Triple(true, true, false) to StatusRole.Warning,
            Triple(true, false, true) to StatusRole.Danger,
            Triple(true, false, false) to StatusRole.Warning,
            Triple(false, true, true) to StatusRole.Danger,
            Triple(false, true, false) to StatusRole.Danger,
            Triple(false, false, true) to StatusRole.Danger,
            Triple(false, false, false) to StatusRole.Danger,
        )
        assertEquals(8, table.size)
        for ((input, expected) in table) {
            val (ble, ha, responsive) = input
            assertEquals(
                "ble=$ble ha=$ha responsive=$responsive",
                expected,
                connectionStatusRole(ble, ha, responsive),
            )
        }
    }

    @Test
    fun noTwoRolesShareAForegroundInEitherTheme() {
        for (dark in listOf(false, true)) {
            val set = SemanticPalette.forTheme(dark)
            val foregrounds = StatusRole.values().map { set.forRole(it).foreground }
            for (i in foregrounds.indices) {
                for (j in i + 1 until foregrounds.size) {
                    assertNotEquals(
                        "dark=$dark: ${StatusRole.values()[i]} and ${StatusRole.values()[j]} share a foreground",
                        foregrounds[i],
                        foregrounds[j],
                    )
                }
            }
        }
    }
}
