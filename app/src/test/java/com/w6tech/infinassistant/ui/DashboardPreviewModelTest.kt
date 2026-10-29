package com.w6tech.infinassistant.ui

import com.w6tech.infinassistant.core.domain.WatchDashboard
import com.w6tech.infinassistant.core.domain.WatchEvent
import com.w6tech.infinassistant.core.domain.WatchGlyph
import com.w6tech.infinassistant.core.domain.WatchSensor
import com.w6tech.infinassistant.core.domain.WatchSensorGroup
import com.w6tech.infinassistant.core.domain.WatchStatusGroup
import com.w6tech.infinassistant.core.domain.WatchWeather
import com.w6tech.infinassistant.ui.DashboardPreviewModel.Op
import com.w6tech.infinassistant.ui.DashboardPreviewModel.Slot
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dashboard preview's draw list: what appears, where, and in which colour. */
class DashboardPreviewModelTest {

    private val utc = TimeZone.getTimeZone("UTC")

    // Monday 2026-10-26 15:07:00 UTC.
    private val now = 1_793_027_220_000L

    private fun group(type: String, vararg symbols: String) =
        WatchStatusGroup(type = type, glyphs = symbols.map { WatchGlyph(symbol = it) })

    private fun sensors(type: String, count: Int) =
        WatchSensorGroup(type = type, sensors = List(count) { WatchSensor(entityId = "sensor.$type$it") })

    private val owner = WatchDashboard(
        statusGroups = listOf(
            group("doors", "F", "S"),
            group("garage", "G"),
            group("lights", "P", "K"),
            group("media", "L", "H"),
            group("motion", "M"),
        ),
        sensorGroups = listOf(sensors("thermostats", 3), sensors("freezers", 2), sensors("beacons", 4)),
        weather = WatchWeather(entityId = "weather.home"),
        events = listOf(WatchEvent(time = "", title = "calendar:calendar.work")),
    )

    private fun build(dashboard: WatchDashboard, format: String = "HH:mm", index: Int = 0, count: Int = 1) =
        DashboardPreviewModel.build(dashboard, index, count, format, now, utc)

    private fun List<Op>.texts() = filterIsInstance<Op.Text>()
    private fun List<Op>.pills() = filterIsInstance<Op.Box>().filter { it.y == DashboardPreviewModel.PILL_Y }

    @Test
    fun `the time spans the screen and the date sits above it`() {
        val ops = build(owner)
        val time = ops.texts().single { it.font == DashboardPreviewModel.Font.TIME }
        assertEquals("15:07", time.text)
        assertEquals(120, time.x)
        assertEquals(DashboardPreviewModel.Align.CENTER, time.align)
        assertEquals(DashboardPreviewModel.BASE_TIME, time.baseline)
        val date = ops.texts().first { it.baseline == DashboardPreviewModel.BASE_TOP && it.x == 6 }
        assertEquals("MON 26", date.text)
        assertTrue(date.baseline < time.baseline)
    }

    @Test
    fun `the 12-hour format has no leading AM or PM and midnight is 12`() {
        assertEquals("03:07", build(owner, format = "hh:mm").texts().single { it.font == DashboardPreviewModel.Font.TIME }.text)
        assertEquals("12:05", DashboardPreviewModel.clock(0, 5, "hh:mm"))
        assertEquals("00:05", DashboardPreviewModel.clock(0, 5, "HH:mm"))
    }

    @Test
    fun `the screen indicator counts the layout's screens`() {
        val indicator = build(owner, index = 0, count = 6).texts().single { it.x == 120 && it.baseline == DashboardPreviewModel.BASE_TOP }
        assertEquals("1/6", indicator.text)
        assertEquals(DashboardPreviewModel.FAINT, indicator.color)
    }

    @Test
    fun `weather shows its icon, temperature and high and low only with a weather entity`() {
        val with = build(owner)
        assertTrue(with.any { it is Op.Glyph && it.icon == DashboardPreviewModel.Icon.WEATHER })
        assertEquals(listOf("72°", "78/61"), with.texts().filter { it.align == DashboardPreviewModel.Align.RIGHT && it.x == 234 && it.baseline <= DashboardPreviewModel.BASE_HI_LO }.map { it.text })

        val without = build(owner.copy(weather = null))
        assertFalse(without.any { it is Op.Glyph && it.icon == DashboardPreviewModel.Icon.WEATHER })
        assertTrue(without.texts().none { it.text == "78/61" })
    }

    @Test
    fun `two event lines below the time, the first start cyan and the second grey`() {
        val events = build(owner).texts().filter { it.baseline == DashboardPreviewModel.BASE_EVENT || it.baseline == DashboardPreviewModel.BASE_EVENT + 18 }
        assertEquals(4, events.size)
        val starts = events.filter { it.x == 6 }
        assertEquals(listOf(DashboardPreviewModel.CYAN, DashboardPreviewModel.GREY), starts.map { it.color })
        assertTrue(events.filter { it.x == DashboardPreviewModel.TITLE_X }.all { it.color == DashboardPreviewModel.WHITE })
        assertTrue(starts.all { it.baseline > DashboardPreviewModel.BASE_TIME })

        assertTrue(build(owner.copy(events = emptyList())).texts().none { it.baseline == DashboardPreviewModel.BASE_EVENT })
    }

    @Test
    fun `with the doors shut the strip opens with the green check and packs the rest from slot one`() {
        val ops = build(owner)
        val check = ops.filterIsInstance<Op.Glyph>().single { it.icon == DashboardPreviewModel.Icon.CHECK }
        assertEquals(DashboardPreviewModel.CALM, check.color)
        assertEquals(6, check.left)

        // Lights P K, media L H, motion M; the doors and garage glyphs stay dark.
        val pills = ops.pills()
        assertEquals(listOf(29, 52, 75, 98, 121), pills.map { it.x })
        assertEquals(
            listOf(Slot.LIGHTS, Slot.LIGHTS, Slot.MEDIA, Slot.MEDIA, Slot.MOTION).map { it.color },
            pills.map { it.color },
        )
        val letters = ops.texts().filter { it.baseline == DashboardPreviewModel.BASE_PILL }.map { it.text }
        assertEquals(listOf("P", "K", "L", "H", "M"), letters)
        assertTrue(pills.all { it.w == 20 && it.h == 16 && it.radius == 8 })
    }

    @Test
    fun `a layout with no status groups has the check and no pills`() {
        val ops = build(WatchDashboard())
        assertTrue(ops.any { it is Op.Glyph && it.icon == DashboardPreviewModel.Icon.CHECK })
        assertTrue(ops.pills().isEmpty())
    }

    @Test
    fun `an open door takes slot zero and hides the check`() {
        val dash = owner
        val lit = DashboardPreviewModel.litGlyphs(dash) { slot, glyph -> slot == Slot.DOORS && glyph.symbol == "S" || slot == Slot.LIGHTS }
        assertEquals(listOf("S", "P", "K"), lit.map { it.letter })
        assertEquals(listOf(Slot.DOORS, Slot.LIGHTS, Slot.LIGHTS), lit.map { it.slot })
        val (check, pills) = DashboardPreviewModel.statusStrip(lit)
        assertFalse(check)
        assertEquals(listOf(0, 1, 2), pills.map { it.slot })
        assertEquals(Slot.DOORS.color, pills[0].fill)
    }

    @Test
    fun `an open garage door also hides the check`() {
        val lit = DashboardPreviewModel.litGlyphs(owner) { slot, _ -> slot == Slot.GARAGE }
        assertFalse(DashboardPreviewModel.statusStrip(lit).first)
    }

    @Test
    fun `pills pack in type order whatever the order of the groups`() {
        val dash = WatchDashboard(statusGroups = listOf(group("motion", "M"), group("lights", "P"), group("doors", "F")))
        val lit = DashboardPreviewModel.litGlyphs(dash) { _, _ -> true }
        assertEquals(listOf("F", "P", "M"), lit.map { it.letter })
    }

    @Test
    fun `a saturated strip drops the check and shows ten pills`() {
        val dash = WatchDashboard(statusGroups = listOf(group("lights", "A", "B", "C", "D", "E", "F", "G", "H", "I", "J")))
        val lit = DashboardPreviewModel.litGlyphs(dash) { _, _ -> true }
        assertEquals(10, lit.size)
        val (check, pills) = DashboardPreviewModel.statusStrip(lit)
        assertFalse(check)
        assertEquals((0..9).toList(), pills.map { it.slot })
        // Nine lit leave room for the check in slot 0.
        val (check9, pills9) = DashboardPreviewModel.statusStrip(lit.take(9))
        assertTrue(check9)
        assertEquals((1..9).toList(), pills9.map { it.slot })
    }

    @Test
    fun `the watch keeps only one-character ASCII glyphs and ten per group`() {
        val glyphs = listOf("A", "BB", "", "é", "C").map { WatchGlyph(symbol = it) } + List(10) { WatchGlyph(symbol = "x") }
        assertEquals(listOf("A", "C") + List(8) { "x" }, DashboardPreviewModel.keptGlyphs(glyphs).map { it.symbol })
    }

    @Test
    fun `an unknown group type packs with media`() {
        assertEquals(Slot.MEDIA, DashboardPreviewModel.slotOf("speakers"))
        assertEquals(Slot.DOORS, DashboardPreviewModel.slotOf("Doors"))
    }

    @Test
    fun `a freezer group draws the red bar across the whole width with its text`() {
        val ops = build(owner)
        val bar = ops.filterIsInstance<Op.Box>().single { it.color == DashboardPreviewModel.RED && it.w == 240 }
        assertEquals(listOf(0, 175, 240, 20, 0), listOf(bar.x, bar.y, bar.w, bar.h, bar.radius))
        val text = ops.texts().single { it.font == DashboardPreviewModel.Font.BOLD }
        assertEquals("!-12°", text.text)
        assertEquals(DashboardPreviewModel.INK, text.color)
        assertEquals(120, text.x)
        assertEquals(DashboardPreviewModel.BASE_FREEZER, text.baseline)
    }

    @Test
    fun `without a freezer group there is no red bar`() {
        val ops = build(owner.copy(sensorGroups = listOf(sensors("thermostats", 2))))
        assertTrue(ops.filterIsInstance<Op.Box>().none { it.w == 240 })
        assertTrue(ops.texts().none { it.font == DashboardPreviewModel.Font.BOLD })
        // An empty freezer group has nothing to alarm either.
        assertTrue(build(owner.copy(sensorGroups = listOf(sensors("freezers", 0)))).filterIsInstance<Op.Box>().none { it.w == 240 })
    }

    @Test
    fun `thermostats show two temperatures at most and the count at home follows the beacons`() {
        val ops = build(owner)
        val house = ops.texts().filter { it.baseline == DashboardPreviewModel.BASE_HOUSE }
        assertEquals(listOf("72° 70°", "2"), house.map { it.text })
        assertEquals(listOf(6, 206), house.map { it.x })

        val one = build(owner.copy(sensorGroups = listOf(sensors("thermostats", 1), sensors("beacons", 1))))
        assertEquals(listOf("72°", "1"), one.texts().filter { it.baseline == DashboardPreviewModel.BASE_HOUSE }.map { it.text })

        val none = build(WatchDashboard())
        assertTrue(none.texts().none { it.baseline == DashboardPreviewModel.BASE_HOUSE })
        assertTrue("the home icon is always there", none.any { it is Op.Glyph && it.icon == DashboardPreviewModel.Icon.HOME })
    }

    @Test
    fun `the rule and the watch's own row are always drawn`() {
        val ops = build(WatchDashboard())
        assertEquals(Op.Rule(6, 234, 185, DashboardPreviewModel.RULE), ops.filterIsInstance<Op.Rule>().single())
        assertEquals(listOf("80%", "4210", "72"), ops.texts().filter { it.baseline == DashboardPreviewModel.BASE_WATCH }.map { it.text })
        val icons = ops.filterIsInstance<Op.Glyph>().map { it.icon }
        assertTrue(icons.containsAll(listOf(DashboardPreviewModel.Icon.BATTERY, DashboardPreviewModel.Icon.STEPS, DashboardPreviewModel.Icon.HEART)))
    }

    @Test
    fun `everything lies inside the 240 pixel screen`() {
        for (ops in listOf(build(owner), build(WatchDashboard()))) {
            for (op in ops) {
                when (op) {
                    is Op.Box -> assertTrue("$op", op.x >= 0 && op.y >= 0 && op.x + op.w <= 240 && op.y + op.h <= 240)
                    is Op.Rule -> assertTrue("$op", op.x1 >= 0 && op.x2 <= 240 && op.y in 0..239)
                    is Op.Text -> assertTrue("$op", op.x in 0..240 && op.baseline in 0..240)
                    is Op.Glyph -> assertTrue("$op", op.left in 0..240 && op.top in 0..240)
                }
            }
        }
    }

    @Test
    fun `a tile first screen is not a dashboard preview`() {
        val tileFirst = com.w6tech.infinassistant.core.domain.WatchFaceLayoutPayload(
            version = 2,
            theme = com.w6tech.infinassistant.core.domain.WatchTheme(background = "#000000", accent = "#FFFFFF"),
            header = com.w6tech.infinassistant.core.domain.WatchHeader(timeFormat = "HH:mm", timeAlign = "center", presenceIcons = emptyList()),
            screens = listOf(com.w6tech.infinassistant.core.domain.WatchScreen(screenType = 1)),
            statusBar = com.w6tech.infinassistant.core.domain.WatchStatusBar(true, true, true, true, true),
        )
        assertFalse(previewsDashboard(tileFirst))
        assertTrue(previewsDashboard(tileFirst.copy(screens = listOf(com.w6tech.infinassistant.core.domain.WatchScreen(screenType = 0, dashboard = owner)))))
        assertNull(tileFirst.screens.first().dashboard)
    }
}
