package com.w6tech.infinassistant.core.data.bridge

import com.w6tech.infinassistant.core.data.CalendarEvent
import com.w6tech.infinassistant.core.domain.*
import com.w6tech.infinassistant.core.ha.HaState
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * DASHBOARD_STATE from a layout's dashboard and HA's states. No I/O and no clock: the caller
 * passes the states, the forecast, the events and the time.
 *
 * The status bits follow the glyphs the watch kept of the layout, in its order: the first
 * [FirmwareLimits.MAX_STATUS_GROUPS] groups and, in each, the first
 * [FirmwareLimits.MAX_GLYPHS_PER_GROUP] glyphs whose symbol is one ASCII character (LayoutDecoder.cpp
 * drops the others). [fingerprint] hashes those glyphs as the watch does (StatusFingerprint,
 * ActiveScreenView.cpp), so a state computed for another layout than the watch's is never drawn.
 */
internal object DashboardStateBuilder {
    /**
     * The encoded message's budget: one frame ([WireFrame.SINGLE_FRAME_PAYLOAD], 244) with room to
     * spare. The owner's dashboard with two events of full-length titles and a freezer alarm is
     * about 205 bytes.
     */
    const val MAX_BYTES = 220
    /** DashLive::kEvents, and its titles' buffer (char[25]). */
    const val MAX_EVENTS = 2
    const val MAX_TITLE_BYTES = 24
    /** The watch still shows an event this long after it began, as "now" (kDashEventGraceSec). */
    const val EVENT_GRACE_SEC = 600L

    /** DashLive::SensorType. */
    const val THERMOSTATS = 1
    const val FREEZERS = 2
    const val BEACONS = 3

    private const val FNV_OFFSET = 0x811C9DC5L
    private const val FNV_PRIME = 16777619L

    /**
     * The states a glyph lights for: the layout's own active list (WatchFaceOrchestrator.isEntityActive)
     * and a door that is opening or closing, which is not shut.
     */
    val ACTIVE_STATES = setOf(
        "on", "open", "opening", "closing", "home", "playing", "heat", "cool", "heat_cool", "auto",
        "unlocked", "above_horizon", "fan_only", "dry",
    )

    /** DashLive::GroupType of a status group's type. */
    fun groupType(type: String): Int = when (type.lowercase()) {
        "doors" -> 1
        "garage" -> 2
        "motion" -> 3
        "lights" -> 4
        "media" -> 5
        else -> 0
    }

    /** DashLive::SensorType of a sensor group's type, 0 for one the watch does not draw. */
    fun sensorType(type: String): Int = when (type.lowercase()) {
        "thermostats" -> THERMOSTATS
        "freezers" -> FREEZERS
        "beacons" -> BEACONS
        else -> 0
    }

    /** WeatherCode (DashboardState.h) of an HA weather condition, 0 for none. */
    fun weatherCode(condition: String): Int = when (condition) {
        "sunny", "clear" -> 1
        "clear-night" -> 2
        "partlycloudy" -> 3
        "cloudy" -> 4
        "rainy" -> 5
        "pouring" -> 6
        "snowy" -> 7
        "snowy-rainy" -> 8
        "hail" -> 9
        "lightning" -> 10
        "lightning-rainy" -> 11
        "windy" -> 12
        "windy-variant" -> 13
        "fog" -> 14
        "exceptional" -> 15
        else -> 0
    }

    /**
     * The dashboard the live state is for: the first one among the screens the watch holds,
     * [screenCap] of them ([WatchFaceOrchestrator.screenCap] of the watch's features; the
     * default is the most any firmware holds).
     */
    fun dashboardOf(layout: WatchFaceLayoutPayload, screenCap: Int = WatchFaceOrchestrator.MAX_FW_SCREENS): WatchDashboard? =
        layout.screens.take(screenCap)
            .firstOrNull { it.screenType == 0 && it.dashboard != null }?.dashboard

    /** The watch keeps a glyph whose symbol is exactly one ASCII character (REQ-028). */
    fun watchKeeps(glyph: WatchGlyph): Boolean = glyph.symbol.length == 1 && glyph.symbol[0].code in 1..0x7F

    /** The status groups and their glyphs as the watch stored them. */
    fun keptGroups(dash: WatchDashboard): List<Pair<WatchStatusGroup, List<WatchGlyph>>> =
        dash.statusGroups.take(FirmwareLimits.MAX_STATUS_GROUPS).map { group ->
            group to group.glyphs.filter(::watchKeeps).take(FirmwareLimits.MAX_GLYPHS_PER_GROUP)
        }

    /** FNV-1a over each kept group's glyph count (a byte), then its symbols (StatusFingerprint). */
    fun fingerprint(dash: WatchDashboard): Long {
        var hash = FNV_OFFSET
        fun add(byte: Int) {
            hash = ((hash xor (byte.toLong() and 0xFF)) * FNV_PRIME) and 0xFFFFFFFFL
        }
        for ((_, glyphs) in keptGroups(dash)) {
            add(glyphs.size)
            glyphs.forEach { add(it.symbol[0].code) }
        }
        return hash
    }

    /** Every entity the dashboard's live state reads. */
    fun entityIds(dash: WatchDashboard): Set<String> = buildSet {
        keptGroups(dash).forEach { (_, glyphs) -> glyphs.forEach { it.entityId?.let(::add) } }
        dash.sensorGroups.take(FirmwareLimits.MAX_SENSOR_GROUPS).forEach { g ->
            g.sensors.take(FirmwareLimits.MAX_SENSORS_PER_GROUP).forEach { add(it.entityId) }
        }
        dash.weather?.let { add(it.entityId) }
        removeAll { it.isBlank() }
    }

    /**
     * The calendars the dashboard's events come from: its "calendar:" references
     * (WatchFaceParser), else, for a layout stored before they were kept (app 2.1.106 and older),
     * every calendar of the face, as the parser takes them by default. [MAX_EVENTS] caps the
     * events shown, not the calendars they come from.
     */
    fun calendarIds(dash: WatchDashboard, layout: WatchFaceLayoutPayload): List<String> {
        val refs = dash.events.filter { it.title.startsWith(CALENDAR_REF) }
            .map { it.title.removePrefix(CALENDAR_REF) }.filter { it.isNotBlank() }
        return refs.ifEmpty { layout.calendarEntityIds.filter { it.isNotBlank() } }.distinct()
    }

    const val CALENDAR_REF = "calendar:"

    /** A temperature-like reading: a climate's current_temperature, else the state as a number. */
    fun reading(entityId: String, state: HaState?): Int? {
        state ?: return null
        if (entityId.startsWith("climate.")) {
            (state.attributes["current_temperature"] as? JsonPrimitive)?.doubleOrNull?.let { return Math.round(it).toInt() }
        }
        return state.state.toDoubleOrNull()?.let { Math.round(it).toInt() }
    }

    /** A beacon: 1 at home, 0 away (another zone included), null when HA does not know. */
    fun presence(state: HaState?): Int? {
        val s = state?.state?.trim()?.lowercase() ?: return null
        return when {
            s.isEmpty() || s == "unavailable" || s == "unknown" -> null
            s == "home" || s == "on" -> 1
            s.toDoubleOrNull() != null -> if (s.toDouble() > 0) 1 else 0
            else -> 0
        }
    }

    /** Whether a sensor reads in Celsius (its unit_of_measurement); Fahrenheit otherwise. */
    fun isCelsius(state: HaState?): Boolean =
        (state?.attributes?.get("unit_of_measurement") as? JsonPrimitive)?.contentOrNull?.contains('C') == true

    /**
     * The live state of [dash] at [nowSec]. [freezerAlert] says whether a freezer at a reading has
     * been warm long enough to alarm (FreezerAlertTracker). [forecast] is today's high and low.
     * The result fits [MAX_BYTES]: the event titles are shortened, then the events dropped, before
     * the message would leave one frame.
     */
    fun build(
        dash: WatchDashboard,
        states: Map<String, HaState>,
        forecast: Pair<Int, Int>?,
        events: List<CalendarEvent>,
        nowSec: Long,
        freezerAlert: (entityId: String, value: Int?, celsius: Boolean) -> Boolean,
    ): DashboardStatePayload {
        val groups = keptGroups(dash).map { (group, glyphs) ->
            var bits = 0
            glyphs.forEachIndexed { i, glyph ->
                if (states[glyph.entityId.orEmpty()]?.state in ACTIVE_STATES) bits = bits or (1 shl i)
            }
            DashStateGroup(type = groupType(group.type), bits = bits)
        }

        val sensors = dash.sensorGroups.take(FirmwareLimits.MAX_SENSOR_GROUPS).mapNotNull { group ->
            val type = sensorType(group.type)
            val list = group.sensors.take(FirmwareLimits.MAX_SENSORS_PER_GROUP)
            if (type == 0 || list.isEmpty()) return@mapNotNull null
            var alerts = 0
            val values = list.mapIndexed { i, sensor ->
                val state = states[sensor.entityId]
                if (type == BEACONS) {
                    presence(state)
                } else {
                    reading(sensor.entityId, state).also { v ->
                        if (type == FREEZERS && freezerAlert(sensor.entityId, v, isCelsius(state))) alerts = alerts or (1 shl i)
                    }
                }
            }
            DashStateSensors(type = type, values = values, alerts = alerts)
        }

        val weather = dash.weather?.let { w ->
            val state = states[w.entityId] ?: return@let null
            val code = weatherCode(state.state)
            val temp = (state.attributes["temperature"] as? JsonPrimitive)?.doubleOrNull
            if (code == 0 || temp == null) return@let null
            DashStateWeather(code = code, temp = Math.round(temp).toInt().coerceIn(-127, 127),
                hi = forecast?.first?.coerceIn(-127, 127), lo = forecast?.second?.coerceIn(-127, 127))
        }

        val upcoming = events.asSequence()
            .filter { it.startEpoch > 0 && it.startEpoch + EVENT_GRACE_SEC > nowSec }
            .distinctBy { it.startEpoch to it.title }
            .sortedBy { it.startEpoch }
            .take(MAX_EVENTS)
            .toList()

        fun payload(titleBytes: Int, eventCount: Int) = DashboardStatePayload(
            fingerprint = fingerprint(dash),
            computedAt = nowSec,
            groups = groups,
            sensors = sensors,
            weather = weather,
            events = upcoming.take(eventCount).map { DashStateEvent(it.startEpoch, WireText.fit(it.title, titleBytes)) },
        )
        // Shortest last: two events with short titles, then one, then none.
        val candidates = sequenceOf(
            payload(MAX_TITLE_BYTES, MAX_EVENTS), payload(12, MAX_EVENTS), payload(12, 1), payload(0, 0),
        )
        return candidates.firstOrNull { encode(it).size <= MAX_BYTES } ?: payload(0, 0)
    }

    fun encode(payload: DashboardStatePayload): ByteArray = ProtocolCbor.encode(DashboardStatePayload.serializer(), payload)
}

/**
 * A freezer alarms when it has read above its threshold, 15 °F (-9 °C), for [sustainMs]: a door
 * left open, not a defrost blip or a reading taken while it was open for a minute. The time a
 * freezer went warm is kept across watch connections (the tracker lives with the bridge).
 */
internal class FreezerAlertTracker(private val sustainMs: Long = SUSTAIN_MS) {
    private val warmSince = HashMap<String, Long>()

    /** Whether [entityId] reading [value] at [nowMs] alarms; records when it went warm. */
    @Synchronized
    fun alert(entityId: String, value: Int?, celsius: Boolean, nowMs: Long): Boolean {
        val threshold = if (celsius) THRESHOLD_C else THRESHOLD_F
        if (value == null || value <= threshold) {
            warmSince.remove(entityId)
            return false
        }
        val since = warmSince.getOrPut(entityId) { nowMs }
        return nowMs - since >= sustainMs
    }

    /** When the next warm freezer starts to alarm, or null when none is about to. */
    @Synchronized
    fun nextAlarmAtMs(nowMs: Long): Long? = warmSince.values.map { it + sustainMs }.filter { it > nowMs }.minOrNull()

    companion object {
        const val SUSTAIN_MS = 20 * 60_000L
        const val THRESHOLD_F = 15
        const val THRESHOLD_C = -9
    }
}
