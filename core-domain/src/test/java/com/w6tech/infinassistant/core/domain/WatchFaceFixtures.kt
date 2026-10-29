package com.w6tech.infinassistant.core.domain

/**
 * Layouts used to pin and exercise the WATCHFACE_LAYOUT encoders (REQ009-FR-C3).
 *
 * [full] sets a value on every wire property of every nested type reachable from
 * [WatchFaceLayoutPayload], and leaves a few properties at their declared default
 * so the defaults-omitted encoder has something to omit. [minimal] is all defaults.
 */
internal object WatchFaceFixtures {

    val full: WatchFaceLayoutPayload = WatchFaceLayoutPayload(
        version = 1,
        theme = WatchTheme(background = "#000000", accent = "#FF8800"),
        header = WatchHeader(
            timeFormat = "HH:mm",
            timeAlign = "center",
            presenceIcons = listOf(
                WatchPresenceIcon(type = "person", color = "#00FF00", entity = "person.a"),
                WatchPresenceIcon(type = "door", color = "#FF0000"),
            ),
        ),
        screens = listOf(
            WatchScreen(
                screenType = 0,
                dashboard = WatchDashboard(
                    statusGroups = listOf(
                        WatchStatusGroup(
                            type = "doors",
                            glyphs = listOf(
                                WatchGlyph(symbol = "D", entityId = "", active = true),
                                WatchGlyph(symbol = "G", entityId = "cover.g"),
                            ),
                        ),
                    ),
                    sensorGroups = listOf(
                        WatchSensorGroup(
                            type = "thermostats",
                            sensors = listOf(
                                WatchSensor(value = 71),
                                WatchSensor(entityId = "sensor.x", displayType = "temperature", value = -3),
                            ),
                        ),
                    ),
                    weather = WatchWeather(entityId = "weather.home", condition = "sunny", temp = 72, hi = 80, lo = 60),
                    events = listOf(WatchEvent(time = "09:30", title = "Standup", startEpoch = 1_790_000_000L)),
                    notifications = listOf(WatchNotification(source = "HA", text = "Door open")),
                    lastEnrichedAt = 1_790_000_123L,
                    configurableValueLabel = "Temp",
                    configurableValueValue = "72",
                    inlineMetric0Label = "A",
                    inlineMetric0Value = "1",
                    inlineMetric1Label = "B",
                    inlineMetric1Value = "2",
                ),
            ),
            WatchScreen(
                screenType = 1,
                tileLayout = 2,
                tiles = listOf(
                    WatchTile(
                        label = "Lamp",
                        entityId = "light.lamp",
                        color = "#FFFFFF",
                        colorEnabled = false,
                        icon = "bulb",
                        displayMode = 1,
                        confirm = true,
                    ),
                    WatchTile(label = "Fan", color = "#00FFFF"),
                ),
                logbook = WatchLogbook(entityIds = listOf("light.lamp"), maxEntries = 5, hoursBack = 3),
            ),
        ),
        tiles = listOf(WatchTile(label = "Legacy", entityId = "light.legacy", color = "#123456")),
        statusBar = WatchStatusBar(battery = true, steps = false, heartRate = true, notifications = false, bluetooth = true),
        defaultActiveColor = "#111111",
        defaultInactiveColor = "#222222",
        logbookApp = WatchLogbookAppConfig(entityIds = listOf("light.lamp"), maxEntries = 7, hoursBack = 4),
        homeControlConfig = WatchHomeControlConfig(),
        calendarEntityIds = listOf("calendar.family"),
    )

    val minimal: WatchFaceLayoutPayload = WatchFaceLayoutPayload(
        version = 1,
        theme = WatchTheme(background = "#000000", accent = "#FF8800"),
        header = WatchHeader(timeFormat = "HH:mm", timeAlign = "left", presenceIcons = emptyList()),
        statusBar = WatchStatusBar(battery = true, steps = true, heartRate = true, notifications = true, bluetooth = true),
    )

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
