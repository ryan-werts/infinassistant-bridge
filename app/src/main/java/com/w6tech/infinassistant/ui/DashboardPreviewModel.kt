package com.w6tech.infinassistant.ui

import com.w6tech.infinassistant.core.domain.WatchDashboard
import com.w6tech.infinassistant.core.domain.WatchGlyph
import java.util.Calendar
import java.util.TimeZone

/**
 * What the import preview draws for a dashboard screen: the watch's dashboard "v8" (firmware
 * 2.4.98 and later, docs/face_dashboard/v8/design-spec.md) as a flat list of draw operations in
 * the watch's own 240 x 240 pixels. The card's preview (dashboard-preview.ts, DASH_BASE, PILL)
 * is the reference for the geometry, the order of the pills and the colours.
 *
 * The preview has the layout but no live states, so the values are samples: the time is the
 * phone's now, the weather, events, temperatures and the count at home are made up, and the
 * strip lights the layout's lights, media and motion glyphs with the doors and garage shut.
 * What the layout does decide is which parts exist: a weather block only with a weather entity,
 * event lines only with calendars, the thermostat line and the count at home only with those
 * sensor groups, and the freezer bar only with a freezer group.
 *
 * The colours are the watch display's own, not the app theme's, so they are plain ARGB values
 * here and the canvas turns them into colours (WatchFacePreview).
 */
internal object DashboardPreviewModel {
    // The watch's palette (design-spec.md "Colours").
    const val WHITE = 0xFFE5E5EA.toInt()
    const val GREY = 0xFF8E8E93.toInt()
    const val FAINT = 0xFF48484A.toInt()
    const val RULE = 0xFF2C2C2E.toInt()
    const val CYAN = 0xFF5AC8FA.toInt()
    const val CALM = 0xFF2F7D4F.toInt()
    const val RED = 0xFFFF453A.toInt()
    const val INK = 0xFF000000.toInt()
    const val BACKGROUND = 0xFF000000.toInt()

    /** The pill types in the order they pack: security first, then activity. */
    enum class Slot(val color: Int) {
        DOORS(0xFFFF9500.toInt()),
        GARAGE(0xFFFF453A.toInt()),
        LIGHTS(0xFFFFCC00.toInt()),
        MEDIA(0xFFBF5AF2.toInt()),
        MOTION(0xFF5AC8FA.toInt()),
    }

    // Geometry (DASH_BASE, PILL and DASH_ICON of dashboard-preview.ts).
    const val PILL_X0 = 6
    const val PILL_PITCH = 23
    const val PILL_W = 20
    const val PILL_H = 16
    const val PILL_Y = 155
    const val PILL_RADIUS = 8
    const val PILL_SLOTS = 10
    const val TITLE_X = 88
    const val BASE_TOP = 19
    const val BASE_HI_LO = 36
    const val BASE_TIME = 106
    const val BASE_EVENT = 128
    const val BASE_PILL = 168
    const val BASE_FREEZER = 191
    const val BASE_HOUSE = 212
    const val BASE_WATCH = 234
    const val MAX_STATUS_GROUPS = 5
    const val MAX_GLYPHS = 10

    /** The fonts the watch uses; the canvas picks the nearest typeface for each. */
    enum class Font { M14, M18, TIME, BOLD }

    enum class Align { LEFT, CENTER, RIGHT }

    enum class Icon { CHECK, HOME, BATTERY, STEPS, HEART, WEATHER }

    sealed interface Op {
        /** A filled box; [radius] 0 is square. */
        data class Box(val x: Int, val y: Int, val w: Int, val h: Int, val radius: Int, val color: Int) : Op

        /** The 1 px rule. */
        data class Rule(val x1: Int, val x2: Int, val y: Int, val color: Int) : Op

        /** Text placed by its baseline. */
        data class Text(val text: String, val x: Int, val baseline: Int, val font: Font, val color: Int, val align: Align) : Op

        /** An icon in its box, [left], [top]; [level] is the battery's charge in percent. */
        data class Glyph(val icon: Icon, val left: Int, val top: Int, val color: Int, val level: Int = 100) : Op
    }

    /** A glyph that is on and the type it packs and colours as. */
    data class Lit(val letter: String, val slot: Slot)

    /** A pill: its letter, its slot (0 to 9) and its fill. */
    data class Pill(val letter: String, val slot: Int, val fill: Int)

    /** A pill's left edge. */
    fun pillX(slot: Int): Int = PILL_X0 + PILL_PITCH * slot

    /** The type a group's pills pack and colour as; any other type packs with media. */
    fun slotOf(type: String): Slot = when (type.lowercase()) {
        "doors" -> Slot.DOORS
        "garage" -> Slot.GARAGE
        "lights" -> Slot.LIGHTS
        "motion" -> Slot.MOTION
        else -> Slot.MEDIA
    }

    /** The glyphs the watch keeps of a group: one ASCII character each, at most ten. */
    fun keptGlyphs(glyphs: List<WatchGlyph>): List<WatchGlyph> =
        glyphs.filter { it.symbol.length == 1 && it.symbol[0].code in 1..0x7F }.take(MAX_GLYPHS)

    /**
     * The glyphs that are on, in type order (doors, garage, lights, media, motion), each type's
     * groups in the layout's order. [isOn] says whether a glyph of a type is on.
     */
    fun litGlyphs(dashboard: WatchDashboard, isOn: (Slot, WatchGlyph) -> Boolean): List<Lit> {
        val groups = dashboard.statusGroups.take(MAX_STATUS_GROUPS)
        return Slot.entries.flatMap { slot ->
            groups.filter { slotOf(it.type) == slot }.flatMap { group ->
                keptGlyphs(group.glyphs).filter { it.symbol != "#" && isOn(slot, it) }.map { Lit(it.symbol, slot) }
            }
        }
    }

    /**
     * The strip of ten pills. Slot 0 is the doors verdict: the calm green check when no door or
     * garage door is open, with the pills packing from slot 1; else the first door or garage pill.
     * With the check and ten pills both due the strip is saturated and the check gives way.
     */
    fun statusStrip(lit: List<Lit>): Pair<Boolean, List<Pill>> {
        val shown = lit.take(PILL_SLOTS)
        val doorsShut = lit.none { it.slot == Slot.DOORS || it.slot == Slot.GARAGE }
        val showCheck = doorsShut && shown.size < PILL_SLOTS
        val first = if (showCheck) 1 else 0
        return showCheck to shown.mapIndexed { k, g -> Pill(g.letter, first + k, g.slot.color) }
    }

    private val DAYS = listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")

    /** "HH:MM", or the 12-hour form without AM/PM when the layout's format starts with "h". */
    fun clock(hour24: Int, minute: Int, timeFormat: String): String {
        var hour = hour24
        if (timeFormat.startsWith("h")) {
            hour %= 12
            if (hour == 0) hour = 12
        }
        return "%02d:%02d".format(hour, minute)
    }

    private fun sensorsOf(dashboard: WatchDashboard, type: String): Int =
        dashboard.sensorGroups.take(3).filter { it.type.equals(type, ignoreCase = true) }.sumOf { it.sensors.take(7).size }

    // Sample values: made up, to show where things go.
    private val SAMPLE_TEMPS = listOf(72, 70)
    private const val SAMPLE_WEATHER_TEMP = "72°"
    private const val SAMPLE_HI_LO = "78/61"
    private const val SAMPLE_FREEZER = "!-12°"
    private val SAMPLE_EVENTS = listOf(Triple("25m", CYAN, "Team standup"), Triple("3.5h", GREY, "Dentist"))
    private const val SAMPLE_BATTERY = 80
    private const val SAMPLE_STEPS = "4210"
    private const val SAMPLE_HEART = "72"

    /**
     * The draw list of [dashboard], the dashboard on screen [screenIndex] (0-based) of [screenCount],
     * for the time [nowMs] in [zone].
     */
    fun build(
        dashboard: WatchDashboard,
        screenIndex: Int,
        screenCount: Int,
        timeFormat: String,
        nowMs: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): List<Op> {
        val now = Calendar.getInstance(zone).apply { timeInMillis = nowMs }
        val ops = mutableListOf<Op>()
        fun text(s: String, x: Int, baseline: Int, font: Font, color: Int, align: Align = Align.LEFT) {
            ops += Op.Text(s, x, baseline, font, color, align)
        }

        text("${DAYS[now.get(Calendar.DAY_OF_WEEK) - 1]} ${now.get(Calendar.DAY_OF_MONTH)}", 6, BASE_TOP, Font.M14, GREY)
        text("${screenIndex + 1}/$screenCount", 120, BASE_TOP, Font.M14, FAINT, Align.CENTER)
        if (dashboard.weather != null) {
            ops += Op.Glyph(Icon.WEATHER, 155, 8, WHITE)
            text(SAMPLE_WEATHER_TEMP, 234, BASE_TOP, Font.M18, WHITE, Align.RIGHT)
            text(SAMPLE_HI_LO, 234, BASE_HI_LO, Font.M14, GREY, Align.RIGHT)
        }

        text(clock(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), timeFormat), 120, BASE_TIME, Font.TIME, WHITE, Align.CENTER)

        if (dashboard.events.isNotEmpty()) {
            SAMPLE_EVENTS.forEachIndexed { i, (start, color, title) ->
                text(start, 6, BASE_EVENT + 18 * i, Font.M14, color)
                text(title, TITLE_X, BASE_EVENT + 18 * i, Font.M14, WHITE)
            }
        }

        val (check, pills) = statusStrip(litGlyphs(dashboard) { slot, _ -> slot != Slot.DOORS && slot != Slot.GARAGE })
        if (check) ops += Op.Glyph(Icon.CHECK, 6, 152, CALM)
        for (pill in pills) {
            ops += Op.Box(pillX(pill.slot), PILL_Y, PILL_W, PILL_H, PILL_RADIUS, pill.fill)
            text(pill.letter, pillX(pill.slot) + PILL_W / 2, BASE_PILL, Font.M14, INK, Align.CENTER)
        }

        ops += Op.Rule(6, 234, 185, RULE)
        if (sensorsOf(dashboard, "freezers") > 0) {
            ops += Op.Box(0, 175, 240, 20, 0, RED)
            text(SAMPLE_FREEZER, 120, BASE_FREEZER, Font.BOLD, INK, Align.CENTER)
        }

        val thermostats = minOf(sensorsOf(dashboard, "thermostats"), SAMPLE_TEMPS.size)
        if (thermostats > 0) {
            text(SAMPLE_TEMPS.take(thermostats).joinToString(" ") { "$it°" }, 6, BASE_HOUSE, Font.M18, WHITE)
        }
        ops += Op.Glyph(Icon.HOME, 180, 196, GREY)
        val beacons = sensorsOf(dashboard, "beacons")
        if (beacons > 0) text(minOf(beacons, 2).toString(), 206, BASE_HOUSE, Font.M18, WHITE)

        ops += Op.Glyph(Icon.BATTERY, 6, 218, GREY, SAMPLE_BATTERY)
        text("$SAMPLE_BATTERY%", 34, BASE_WATCH, Font.M18, WHITE)
        ops += Op.Glyph(Icon.STEPS, 88, 218, GREY)
        text(SAMPLE_STEPS, 116, BASE_WATCH, Font.M18, WHITE)
        ops += Op.Glyph(Icon.HEART, 180, 218, GREY)
        text(SAMPLE_HEART, 206, BASE_WATCH, Font.M18, WHITE)
        return ops
    }
}
