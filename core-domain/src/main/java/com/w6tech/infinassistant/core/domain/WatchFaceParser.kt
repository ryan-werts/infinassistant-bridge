package com.w6tech.infinassistant.core.domain

import android.util.Log
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull

/**
 * Parser and validator for watch face JSON configurations.
 * Transforms WatchFaceImportJson into WatchFaceLayoutPayload for BLE transmission.
 */
object WatchFaceParser {
    private const val TAG = "WatchFaceParser"

    /** The most screens a layout may have: what a six-screen watch (fw 2.4.100) holds. A watch holding 4 gets the layout trimmed at the push. */
    const val MAX_SCREENS = 6

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Parse and validate watch face JSON string.
     * Returns either a success with the protocol payload or an error message.
     */
    fun parseWatchFaceJson(jsonString: String): ParseResult {
        return try {
            // Parse JSON to import model
            val importJson = json.decodeFromString(WatchFaceImportJson.serializer(), jsonString)

            // Log parsed result
            Log.d(TAG, "Parsed watch face JSON: version=${importJson.version}, screens=${importJson.screens.size}, legacy tiles=${importJson.tiles.size}")

            // Warn if we got defaults (indicates wrapper not unwrapped)
            if (importJson.version == 1 && importJson.screens.isEmpty() && importJson.tiles.isEmpty()) {
                Log.w(TAG, "WARNING: Parsed defaults (version=1, 0 screens, 0 tiles). " +
                    "This often means JSON wrapper was not unwrapped. " +
                    "Expected: {\"version\": 2, \"screens\": [...]}. Check HaApi response handling.")
            }

            // Validate constraints
            val validation = validateConstraints(importJson)
            if (validation is ValidationResult.Invalid) {
                return ParseResult.Error(validation.errors.joinToString("\n"))
            }

            // Transform to protocol format
            val payload = transformToProtocol(importJson)
            ParseResult.Success(payload)

        } catch (e: SerializationException) {
            ParseResult.Error("Invalid JSON format: ${e.message ?: "Unknown error"}")
        } catch (e: IllegalArgumentException) {
            ParseResult.Error("Invalid data: ${e.message ?: "Unknown error"}")
        } catch (e: Exception) {
            ParseResult.Error("Parse error: ${e.message ?: "Unknown error"}")
        }
    }

    /**
     * Transform WatchFaceImportJson to compact WatchFaceLayoutPayload.
     * Flattens nested structures and takes first N items within limits.
     * Supports both multi-screen format and legacy single-screen format.
     * Supports both v1 (state data) and v2 (config-only) formats.
     */
    fun transformToProtocol(importJson: WatchFaceImportJson): WatchFaceLayoutPayload {
        Log.d(TAG, "transformToProtocol: version=${importJson.version}, ${importJson.screens.size} screens, legacy tiles=${importJson.tiles.size}")

        // Check for top-level events config (v2 format)
        val eventsConfig = importJson.events
        if (eventsConfig != null) {
            Log.d(TAG, "Top-level events config: enabled=${eventsConfig.enabled}, calendars=${eventsConfig.calendars.size}, maxEvents=${eventsConfig.maxEvents}")
        }

        // Flatten header structure - handle both v1 (presenceIcons list) and v2 (presence config object)
        val presenceList = when {
            // v2 format: presence is an object with icons list inside
            importJson.header.presence != null && importJson.header.presence.icons.isNotEmpty() ->
                importJson.header.presence.icons
            // v1 format: presenceIcons is a list at top level
            importJson.header.presenceIcons.isNotEmpty() ->
                importJson.header.presenceIcons
            else -> emptyList()
        }

        val header = WatchHeader(
            timeFormat = importJson.header.time.format,
            timeAlign = importJson.header.time.align,
            presenceIcons = presenceList.map { icon ->
                WatchPresenceIcon(
                    type = icon.type,
                    color = icon.color,
                    entity = icon.entity
                )
            }
        )

        // Transform theme (direct copy)
        val theme = WatchTheme(
            background = importJson.theme.background,
            accent = importJson.theme.accent
        )

        // Transform status bar (extract boolean flags from either format)
        val statusBar = WatchStatusBar(
            battery = extractBooleanFlag(importJson.statusBar.battery, defaultValue = true),
            steps = extractBooleanFlag(importJson.statusBar.steps, defaultValue = true),
            heartRate = extractBooleanFlag(importJson.statusBar.heartRate, defaultValue = false),
            notifications = extractBooleanFlag(importJson.statusBar.notifications, defaultValue = true),
            bluetooth = extractBooleanFlag(importJson.statusBar.bluetooth, defaultValue = true)
        )

        // Multi-screen vs legacy format
        val screens = if (importJson.screens.isNotEmpty()) {
            // Multi-screen format: transform screens array
            transformScreens(importJson.screens, eventsConfig)
        } else {
            // Legacy format: create single tile screen from top-level tiles
            listOf(createLegacyScreen(importJson.tiles))
        }

        // Populate logbook app config from import JSON
        val logbookApp = importJson.logbookApp?.let { lb ->
            WatchLogbookAppConfig(
                entityIds = lb.entityIds,
                maxEntries = lb.maxEntries,
                hoursBack = lb.hoursBack,
            )
        }

        // Populate home control config from import JSON
        val homeControlConfig = importJson.homeControl?.let { hc ->
            WatchHomeControlConfig(
                devices = hc.devices.map { dev ->
                    WatchHomeControlDevice(
                        entityId = dev.entityId,
                        name = dev.name,
                        domain = dev.domain,
                    )
                }
            )
        }

        // Collect calendar entity IDs from top-level events config
        val calendarEntityIds = eventsConfig?.takeIf { it.enabled }?.calendars ?: emptyList()

        val payload = WatchFaceLayoutPayload(
            version = importJson.version,
            theme = theme,
            header = header,
            screens = screens,
            tiles = emptyList(),  // Deprecated field
            statusBar = statusBar,
            logbookApp = logbookApp,
            homeControlConfig = homeControlConfig,
            calendarEntityIds = calendarEntityIds,
        )

        Log.d(TAG, "transformToProtocol result: ${payload.screens.size} screens")
        payload.screens.forEachIndexed { idx, screen ->
            Log.d(TAG, "  Screen $idx: type=${screen.screenType}, tiles=${screen.tiles.size}, hasDashboard=${screen.dashboard != null}, hasLogbook=${screen.logbook != null}")
            screen.dashboard?.let { dash ->
                Log.d(TAG, "    Dashboard: ${dash.events.size} events, ${dash.statusGroups.size} statusGroups, ${dash.sensorGroups.size} sensorGroups, weather=${dash.weather != null}")
            }
            screen.logbook?.let { lb ->
                Log.d(TAG, "    Logbook: ${lb.entityIds.size} entities, maxEntries=${lb.maxEntries}, hoursBack=${lb.hoursBack}")
            }
        }

        return payload
    }

    /**
     * Transform multi-screen configurations.
     * Max [MAX_SCREENS] screens total.
     */
    private fun transformScreens(
        screensImport: List<WatchFaceScreenImport>,
        eventsConfig: WatchFaceEventsConfig?
    ): List<WatchScreen> {
        return screensImport.take(MAX_SCREENS).map { screenImport ->
            val screenType = parseScreenType(screenImport.screenType)

            // Tile layout: 4 (2x2 grid) or 6 (3x2 grid)
            val tileLayout = when (screenImport.tileLayout) { 6 -> 6; else -> 4 }
            val tilesBeforeFilter = screenImport.tiles.take(tileLayout)
            Log.d(TAG, "Screen has ${tilesBeforeFilter.size} tiles before filtering (max $tileLayout)")
            val tiles = tilesBeforeFilter
                .filter { tile ->
                    val keep = !tile.entity.isNullOrBlank() || !tile.deviceId.isNullOrBlank()
                    if (!keep) {
                        Log.d(TAG, "Filtering out tile '${tile.label}' (entity='${tile.entity}', deviceId='${tile.deviceId}')")
                    }
                    keep
                }
                .also { Log.d(TAG, "${it.size} tiles after filtering") }
                .map { tile ->
                    WatchTile(
                        label = tile.label,
                        // Use entity if available, otherwise use deviceId (prefixed for identification)
                        entityId = tile.entity?.takeIf { it.isNotBlank() }
                            ?: tile.deviceId?.let { "device:$it" }
                            ?: "",
                        color = tile.color,
                        // ON/OFF: colorEnabled = true unless explicitly disabled
                        colorEnabled = tile.colorMode != "disabled",
                        icon = tile.icon ?: "",
                        displayMode = tile.displayMode ?: 0,
                        // REQ-069 — quick_controls forces long-press confirm on every tile;
                        // per-tile confirm flag still honoured for other screen types.
                        confirm = (screenType == 3) || (tile.confirm == true),
                    )
                }

            val dashboard = screenImport.dashboard?.let { dashImport ->
                // A dashboard with no calendars of its own reads every calendar of the face: the
                // face's maxEvents is how many events show, not how many calendars they come from.
                val dashWithEvents = if (dashImport.events.isEmpty() && eventsConfig != null && eventsConfig.enabled) {
                    val calendarEvents = eventsConfig.calendars.filter { it.isNotBlank() }.distinct().map { calendarId ->
                        WatchFaceCalendarImport(calendar = calendarId)
                    }
                    Log.d(TAG, "Populated dashboard events from top-level config: ${calendarEvents.size} calendars")
                    dashImport.copy(events = calendarEvents)
                } else {
                    Log.d(TAG, "Using dashboard events as-is: ${dashImport.events.size} events already defined")
                    dashImport
                }
                transformDashboard(dashWithEvents)
            }

            val logbook = if (screenType == 2) {
                screenImport.logbook?.let { lb ->
                    WatchLogbook(
                        entityIds = lb.entityIds,
                        maxEntries = lb.maxEntries,
                        hoursBack = lb.hoursBack,
                    )
                } ?: WatchLogbook()
            } else null

            WatchScreen(
                screenType = screenType,
                tileLayout = tileLayout,
                tiles = tiles,
                dashboard = dashboard,
                logbook = logbook,
            )
        }
    }

    /**
     * Transform dashboard configuration.
     */
    private fun transformDashboard(dashImport: WatchFaceDashboardImport): WatchDashboard {
        Log.d(TAG, "Transforming dashboard: ${dashImport.statusGroups.size} status groups, ${dashImport.sensorGroups.size} sensor groups, weather=${dashImport.weather != null}, ${dashImport.events.size} events, ${dashImport.notifications.size} notifications")

        val statusGroups = dashImport.statusGroups.take(5).map { group ->
            val validGlyphs = group.glyphs.filter { glyph ->
                glyph.entity.isNotBlank() && glyph.symbol.isNotBlank()
            }
            val filteredCount = group.glyphs.size - validGlyphs.size
            if (filteredCount > 0) {
                Log.d(TAG, "Status group '${group.type}': filtered $filteredCount blank glyphs")
            }

            WatchStatusGroup(
                type = group.type,
                glyphs = validGlyphs.take(12).map { glyph ->
                    WatchGlyph(
                        symbol = glyph.symbol,
                        entityId = glyph.entity
                    )
                }
            )
        }

        val sensorGroups = dashImport.sensorGroups.take(3).map { group ->
            WatchSensorGroup(
                type = group.type,
                sensors = group.sensors
                    .filter { sensor -> sensor.entity.isNotBlank() }
                    .take(7)
                    .map { sensor ->
                        WatchSensor(
                            entityId = sensor.entity,
                            displayType = sensor.displayType
                        )
                    }
            )
        }

        val weather = dashImport.weather?.let { w ->
            WatchWeather(entityId = w.entity)
        }

        // For v2 config-only format, events are calendar entity references
        // Store calendar entity ID as reference - BridgeEngine will query HA for actual events.
        // Every calendar is kept: the events read from them are what is capped, to the two the
        // watch shows (DashboardStateBuilder.MAX_EVENTS).
        val events = dashImport.events.map { it.calendar }.filter { it.isNotBlank() }.distinct().map { calendarId ->
            WatchEvent(
                time = "",  // Will be populated at runtime from HA calendar
                title = "calendar:$calendarId"  // Calendar entity reference
            )
        }
        Log.d(TAG, "Transformed ${events.size} calendar events: ${events.map { it.title }}")

        val notifications = dashImport.notifications
            .filter { notif -> notif.source.isNotBlank() }
            .take(5)
            .map { notif ->
                WatchNotification(
                    source = notif.source,
                    text = ""  // Text will be populated at runtime from HA state
                )
            }

        // v5: configurable value label + entity ID for enrichment
        val cvImport = dashImport.configurableValue
        val cvLabel = cvImport?.label?.take(11) ?: ""
        val cvEntityId = cvImport?.entity ?: ""

        // v5: inline metric labels + entity IDs for enrichment
        val imImports = dashImport.inlineMetrics.take(2)
        val im0Label = imImports.getOrNull(0)?.label?.take(2) ?: ""
        val im1Label = imImports.getOrNull(1)?.label?.take(2) ?: ""
        val imEntityIds = listOf(
            imImports.getOrNull(0)?.entity ?: "",
            imImports.getOrNull(1)?.entity ?: "",
        )

        return WatchDashboard(
            statusGroups = statusGroups,
            sensorGroups = sensorGroups,
            weather = weather,
            events = events,
            notifications = notifications,
            configurableValueLabel = if (cvEntityId.isNotBlank()) cvLabel else null,
            inlineMetric0Label = if (imEntityIds.getOrNull(0)?.isNotBlank() == true) im0Label else null,
            inlineMetric1Label = if (imEntityIds.getOrNull(1)?.isNotBlank() == true) im1Label else null,
            configurableValueEntityId = cvEntityId,
            inlineMetricEntityIds = imEntityIds,
        )
    }

    /**
     * Create a legacy single screen from top-level tiles/actions.
     * Used for backward compatibility with v1 format.
     */
    private fun createLegacyScreen(
        tiles: List<WatchFaceTile>,
    ): WatchScreen {
        // Filter out tiles with no entity or device ID
        val watchTiles = tiles.take(8)
            .filter { !it.entity.isNullOrBlank() || !it.deviceId.isNullOrBlank() }
            .map { tile ->
                WatchTile(
                    label = tile.label,
                    // Use entity if available, otherwise use deviceId (prefixed for identification)
                    entityId = tile.entity?.takeIf { it.isNotBlank() }
                        ?: tile.deviceId?.let { "device:$it" }
                        ?: "",
                    color = tile.color,
                    // ON/OFF: colorEnabled = true unless explicitly disabled
                    colorEnabled = tile.colorMode != "disabled",
                    icon = tile.icon ?: "",
                )
            }

        return WatchScreen(
            screenType = 1,  // tiles
            tiles = watchTiles,
            dashboard = null
        )
    }

    /**
     * Validate watch face constraints.
     * Returns Invalid with list of error messages if validation fails.
     * Supports both multi-screen and legacy formats.
     */
    fun validateConstraints(importJson: WatchFaceImportJson): ValidationResult {
        val errors = mutableListOf<String>()

        // Version check - accept both v1 (state data) and v2 (config-only)
        if (importJson.version !in listOf(1, 2)) {
            errors.add("Unsupported version: ${importJson.version}. Supported versions: 1, 2.")
        }

        // Multi-screen vs legacy validation
        if (importJson.screens.isNotEmpty()) {
            // Multi-screen format validation
            validateMultiScreen(importJson, errors)
        } else {
            // Legacy format validation
            validateLegacyFormat(importJson, errors)
        }

        // Validate time format
        val validTimeFormats = listOf("HH:mm", "hh:mm")
        if (importJson.header.time.format !in validTimeFormats) {
            errors.add("Invalid time format: '${importJson.header.time.format}'. Must be 'HH:mm' or 'hh:mm'.")
        }

        // Validate time alignment
        val validAlignments = listOf("left", "center", "right")
        if (importJson.header.time.align !in validAlignments) {
            errors.add("Invalid time alignment: '${importJson.header.time.align}'. Must be 'left', 'center', or 'right'.")
        }

        return if (errors.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(errors)
        }
    }

    /**
     * Validate multi-screen format constraints.
     */
    private fun validateMultiScreen(importJson: WatchFaceImportJson, errors: MutableList<String>) {
        // Screen count check
        if (importJson.screens.size > MAX_SCREENS) {
            errors.add("Too many screens: ${importJson.screens.size}. Maximum is $MAX_SCREENS screens.")
        }

        // Validate each screen
        importJson.screens.forEachIndexed { screenIndex, screen ->
            val screenLabel = "Screen ${screenIndex + 1}"

            // Validate screen type - can be Int (0-3) or String ("dashboard", "tiles_actions", "logbook", "quick_controls")
            val screenTypeValue = screen.screenType?.let { element ->
                when (element) {
                    is kotlinx.serialization.json.JsonPrimitive -> element.content
                    else -> null
                }
            }
            if (screenTypeValue != null && screenTypeValue !in listOf("0", "1", "2", "3", "dashboard", "tiles_actions", "logbook", "quick_controls")) {
                errors.add("$screenLabel: Invalid screen type '$screenTypeValue'. Must be 'dashboard' (0), 'tiles_actions' (1), 'logbook' (2), or 'quick_controls' (3).")
            }

            // Parse screenType early to branch validation logic
            val screenType = parseScreenType(screen.screenType)

            when (screenType) {
                0 -> {  // Dashboard screen
                    if (screen.dashboard == null) {
                        errors.add("$screenLabel: Dashboard screen (type=0) requires 'dashboard' configuration.")
                    } else {
                        validateDashboard(screen.dashboard, screenLabel, errors)
                    }
                    // Warn if tiles are present (will be ignored)
                    if (screen.tiles.isNotEmpty()) {
                        Log.w(TAG, "$screenLabel: Dashboard screen has tiles which will be ignored")
                    }
                }
                1 -> {  // Tiles/Actions screen
                    // 4-tile (2x2) or 6-tile (3x2) grid
                    val maxTiles = when (screen.tileLayout) { 6 -> 6; else -> 4 }
                    if (screen.tiles.size > maxTiles) {
                        errors.add("$screenLabel: Too many tiles (${screen.tiles.size}). Maximum is $maxTiles tiles.")
                    }

                    // Validate tile entity IDs (entity or deviceId required)
                    screen.tiles.forEachIndexed { tileIndex, tile ->
                        if (tile.entity.isNullOrBlank() && tile.deviceId.isNullOrBlank()) {
                            errors.add("$screenLabel, Tile ${tileIndex + 1} ('${tile.label}'): Missing entity ID or device ID.")
                        }
                    }
                }
                2 -> {  // Logbook screen
                    if (screen.logbook == null) {
                        errors.add("$screenLabel: Logbook screen (type=2) requires 'logbook' configuration.")
                    } else {
                        if (screen.logbook.entityIds.isEmpty()) {
                            errors.add("$screenLabel: Logbook screen requires at least one entity ID.")
                        }
                    }
                }
                3 -> {  // Quick controls screen (REQ-069) — 2×2 tile grid; all tiles use long-press confirm
                    if (screen.tiles.size > 4) {
                        errors.add("$screenLabel: quick_controls screen supports at most 4 tiles (2×2 grid), got ${screen.tiles.size}.")
                    }
                    screen.tiles.forEachIndexed { tileIndex, tile ->
                        if (tile.entity.isNullOrBlank() && tile.deviceId.isNullOrBlank()) {
                            errors.add("$screenLabel, Tile ${tileIndex + 1} ('${tile.label}'): Missing entity ID or device ID.")
                        }
                    }
                }
            }
        }
    }

    /**
     * Validate dashboard configuration.
     */
    private fun validateDashboard(dashboard: WatchFaceDashboardImport, screenLabel: String, errors: MutableList<String>) {
        // Status groups
        if (dashboard.statusGroups.size > 5) {
            errors.add("$screenLabel: Too many status groups (${dashboard.statusGroups.size}). Maximum is 5.")
        }

        dashboard.statusGroups.forEachIndexed { groupIndex, group ->
            if (group.glyphs.size > 12) {
                errors.add("$screenLabel, Status group ${groupIndex + 1}: Too many glyphs (${group.glyphs.size}). Maximum is 12.")
            }
        }

        // Sensor groups
        if (dashboard.sensorGroups.size > 3) {
            errors.add("$screenLabel: Too many sensor groups (${dashboard.sensorGroups.size}). Maximum is 3.")
        }

        dashboard.sensorGroups.forEachIndexed { groupIndex, group ->
            if (group.sensors.size > 7) {
                errors.add("$screenLabel, Sensor group ${groupIndex + 1}: Too many sensors (${group.sensors.size}). Maximum is 7.")
            }
        }

        // Events: no limit. They are the calendars the events come from, not the events shown.

        // Notifications
        if (dashboard.notifications.size > 5) {
            errors.add("$screenLabel: Too many notifications (${dashboard.notifications.size}). Maximum is 5.")
        }
    }

    /**
     * Validate legacy single-screen format.
     */
    private fun validateLegacyFormat(importJson: WatchFaceImportJson, errors: MutableList<String>) {
        // Tile count check
        if (importJson.tiles.size > 8) {
            errors.add("Too many tiles: ${importJson.tiles.size}. Maximum is 8 tiles.")
        }

        // Validate tile entity IDs (entity or deviceId required)
        importJson.tiles.forEachIndexed { index, tile ->
            if (tile.entity.isNullOrBlank() && tile.deviceId.isNullOrBlank()) {
                errors.add("Tile ${index + 1} ('${tile.label}') has no entity ID or device ID.")
            }
        }
    }

    /**
     * Parse screenType from JsonElement that can be either:
     * - Int: 0 = dashboard, 1 = tiles_actions, 2 = logbook
     * - String: "dashboard", "tiles_actions", or "logbook"
     *
     * @param element The JsonElement to parse
     * @return Screen type as Int (0 = dashboard, 1 = tiles_actions, 2 = logbook)
     */
    private fun parseScreenType(element: kotlinx.serialization.json.JsonElement?): Int {
        if (element == null) return 1  // Default to tiles_actions

        return when (element) {
            is kotlinx.serialization.json.JsonPrimitive -> {
                // Could be Int or String
                val content = element.content
                when {
                    content == "0" || content == "dashboard" -> 0
                    content == "1" || content == "tiles_actions" -> 1
                    content == "2" || content == "logbook" -> 2
                    // REQ-069 — quick_controls: 2×2 grid, all tiles carry long-press confirm.
                    content == "3" || content == "quick_controls" -> 3
                    element.isString -> when (content) {
                        "dashboard" -> 0
                        "logbook" -> 2
                        "quick_controls" -> 3
                        else -> 1
                    }
                    else -> try { content.toInt() } catch (e: Exception) { 1 }
                }
            }
            else -> 1  // Default to tiles_actions
        }
    }

    /**
     * Extract a boolean flag from JsonElement that can be either:
     * - Boolean primitive: true/false
     * - Object: check "enabled" field, or presence of object = true if no "enabled" field
     *
     * @param element The JsonElement to parse (can be JsonPrimitive boolean or JsonObject)
     * @param defaultValue Default value if element is null
     * @return Boolean flag indicating whether to show this status bar item
     */
    private fun extractBooleanFlag(element: kotlinx.serialization.json.JsonElement?, defaultValue: Boolean): Boolean {
        if (element == null) return defaultValue

        return when (element) {
            is kotlinx.serialization.json.JsonPrimitive -> {
                // Simple boolean format: {"battery": true}
                element.booleanOrNull ?: defaultValue
            }
            is kotlinx.serialization.json.JsonObject -> {
                // Rich object format: {"battery": {"enabled": false, "percent": 100}}
                val enabledElement = element["enabled"]
                if (enabledElement != null && enabledElement is kotlinx.serialization.json.JsonPrimitive) {
                    enabledElement.booleanOrNull ?: true
                } else {
                    true  // No "enabled" key = presence implies enabled
                }
            }
            else -> defaultValue
        }
    }
}

/**
 * Result of parsing watch face JSON.
 */
sealed interface ParseResult {
    data class Success(val payload: WatchFaceLayoutPayload) : ParseResult
    data class Error(val message: String) : ParseResult
}

/**
 * Result of validating watch face constraints.
 */
sealed interface ValidationResult {
    object Valid : ValidationResult
    data class Invalid(val errors: List<String>) : ValidationResult
}
