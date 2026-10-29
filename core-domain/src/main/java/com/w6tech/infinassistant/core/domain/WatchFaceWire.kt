package com.w6tech.infinassistant.core.domain

/**
 * The watch-face layout as the watch needs it on the wire (REQ009-FR-B4).
 *
 * [stripNonRendered] returns a copy of a layout in which every field the firmware
 * never renders holds its declared default. The layout encoder omits a property
 * that holds its default, so those fields cost nothing on the wire. The phone keeps
 * and persists the full layout; only the copy handed to the encoder is stripped.
 *
 * The set is the fields that nothing on the firmware's render path reads (the
 * decoder stores some of them, which is not a read):
 *  - `theme` (background, accent): only the deleted WatchFace screen read it;
 *  - `header.timeAlign`, `header.presenceIcons`, and `statusBar` (all five flags):
 *    same reader. `header.timeFormat` is read on every HA screen and is kept;
 *  - `screens[].tileLayout`: stored by the decoder only when it equals 4, read nowhere;
 *  - `dashboard.notifications`: the dashboard counts the service's own notifications;
 *  - `statusGroups[].type`, `sensorGroups[].type`: no render site reads a group name;
 *  - `weather.entityId`, `weather.temp`: the decoder never matches the first and
 *    nothing reads the second;
 *  - `glyph.entityId`, `sensor.entityId`, `sensor.displayType`: the decoder skips them.
 *
 * Every other field is left alone, including `header.timeFormat`, the default
 * colours, the legacy layout-level `tiles`, and the tile, event, glyph, sensor,
 * weather condition/hi/lo and dashboard text fields the render path reads.
 */
object WatchFaceWire {

    // Defaults come from the declared defaults of the wire types, so this function
    // cannot drift from them.
    private val EMPTY_THEME = WatchTheme(background = "", accent = "")
    private val EMPTY_STATUS_BAR = WatchStatusBar(
        battery = false, steps = false, heartRate = false, notifications = false, bluetooth = false,
    )
    private val DEFAULT_TILE_LAYOUT = WatchScreen(screenType = 0).tileLayout

    fun stripNonRendered(payload: WatchFaceLayoutPayload): WatchFaceLayoutPayload =
        payload.copy(
            theme = EMPTY_THEME,
            header = payload.header.copy(timeAlign = "", presenceIcons = null),
            statusBar = EMPTY_STATUS_BAR,
            screens = payload.screens.map { screen ->
                screen.copy(
                    tileLayout = DEFAULT_TILE_LAYOUT,
                    dashboard = screen.dashboard?.let(::stripDashboard),
                )
            },
        )

    private fun stripDashboard(dash: WatchDashboard): WatchDashboard =
        dash.copy(
            statusGroups = dash.statusGroups.map { group ->
                group.copy(type = "", glyphs = group.glyphs.map { it.copy(entityId = null) })
            },
            sensorGroups = dash.sensorGroups.map { group ->
                group.copy(
                    type = "",
                    sensors = group.sensors.map { it.copy(entityId = "", displayType = "") },
                )
            },
            weather = dash.weather?.copy(entityId = "", temp = 0),
            notifications = emptyList(),
        )
}
