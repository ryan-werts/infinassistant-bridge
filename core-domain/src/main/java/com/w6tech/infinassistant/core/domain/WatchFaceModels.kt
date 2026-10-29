package com.w6tech.infinassistant.core.domain

import kotlinx.serialization.Serializable

/**
 * Watch Face Import JSON models - used for parsing JSON configurations
 * from Home Assistant or manual input. These structures are designed
 * to be user-friendly and comprehensive.
 */

/**
 * Root structure for watch face import JSON.
 * Represents the complete configuration for a custom watch face layout.
 * Supports both v1 (state data) and v2 (config-only) formats.
 */
@Serializable
data class WatchFaceImportJson(
    /** Version of the watch face configuration format (1 or 2). */
    val version: Int = 1,
    /** Optional timestamp when this configuration was generated (Unix epoch milliseconds). */
    val timestamp: Long? = null,
    /** Color theme configuration. */
    val theme: WatchFaceTheme = WatchFaceTheme(),
    /** Header section with time display and presence icons. */
    val header: WatchFaceHeader = WatchFaceHeader(),
    /** Multi-screen support (max 6 screens). */
    val screens: List<WatchFaceScreenImport> = emptyList(),
    /** Events configuration (v2 config-only format). */
    val events: WatchFaceEventsConfig? = null,
    /**
     * Logbook app configuration (phone-side only). The HA card exports "logbookApp"; until 2.1.97
     * only "logbook_app" was read, so the entities of every layout pushed from HA were dropped.
     * Both spellings are accepted.
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.SerialName("logbookApp")
    @kotlinx.serialization.json.JsonNames("logbook_app")
    val logbookApp: WatchFaceLogbookAppImport? = null,
    /**
     * Home control app configuration (phone-side only). The HA card exports "homeControl"
     * (watch-layout-exporter.ts); until 2.1.96 only "home_control" was read, so the devices of
     * every layout pushed from HA were dropped. Both spellings are accepted.
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.SerialName("homeControl")
    @kotlinx.serialization.json.JsonNames("home_control")
    val homeControl: WatchFaceHomeControlImport? = null,
    /** Information tiles showing entity states (max 8). DEPRECATED: use screens[].tiles */
    val tiles: List<WatchFaceTile> = emptyList(),
    /** Status bar configuration. */
    val statusBar: WatchFaceStatusBar = WatchFaceStatusBar(),
)

/**
 * Events configuration for v2 config-only format.
 * Contains calendar entity IDs and display settings - Android app queries HA for current events.
 */
@Serializable
data class WatchFaceEventsConfig(
    /** Whether events are enabled. */
    val enabled: Boolean = true,
    /** Calendar entity IDs to query for events. */
    val calendars: List<String> = emptyList(),
    /** Maximum number of events to display. */
    val maxEvents: Int = 2,
    /** Time window for "soon" events (ISO duration like "01:00:00"). */
    val soonWindow: String = "01:00:00",
    /** Whether to show empty state when no events. */
    val showIfEmpty: Boolean = false,
    /** How to handle all-day events: "show_all_day", "hide", "banner". */
    val allDay: String = "show_all_day",
)

/**
 * Watch face color theme.
 */
@Serializable
data class WatchFaceTheme(
    /** Background style: "dark", "light", "black", or custom hex color. */
    val background: String = "dark",
    /** Accent color as hex string (e.g., "#FF5722"). */
    val accent: String = "#FF5722",
)

/**
 * Header section configuration.
 * Contains time display format and presence status icons.
 * Supports both v1 (presenceIcons list) and v2 (presence config object) formats.
 */
@Serializable
data class WatchFaceHeader(
    /** Time format configuration. */
    val time: WatchFaceTime = WatchFaceTime(),
    /** Presence status icons (v1 format: list of icons with color/state). */
    val presenceIcons: List<WatchFacePresenceIcon> = emptyList(),
    /** Presence configuration (v2 config-only format: object with icons list and settings). */
    val presence: WatchFacePresenceConfig? = null,
)

/**
 * Presence configuration for v2 config-only format.
 * Contains settings and icons list - Android app queries HA for current states.
 */
@Serializable
data class WatchFacePresenceConfig(
    /** Whether presence icons are enabled. */
    val enabled: Boolean = true,
    /** Home zone entity ID for presence calculation. */
    val homeZone: String = "zone.home",
    /** Duration threshold for "long away" state (ISO duration like "24:00:00"). */
    val longAwayThreshold: String = "24:00:00",
    /** Presence icons to display. */
    val icons: List<WatchFacePresenceIcon> = emptyList(),
)

/**
 * Time display configuration.
 */
@Serializable
data class WatchFaceTime(
    /** Time format string: "HH:mm" (24-hour) or "hh:mm" (12-hour). */
    val format: String = "HH:mm",
    /** Alignment: "left", "center", or "right". */
    val align: String = "center",
)

/**
 * Presence status icon configuration.
 * Used to show location/mode status in the header.
 * v2 format: entity and hideIfUnknown (config-only, no color/state)
 * v1 format: color and state (live data)
 */
@Serializable
data class WatchFacePresenceIcon(
    /** Icon type: "home", "work", "away", "sleep", "car", "truck", "person_male", "person_female", etc. */
    val type: String,
    /** Icon color as hex string (v1 format, deprecated in v2). */
    val color: String = "#FFFFFF",
    /** Entity ID to query for state (v2 config-only format). */
    val entity: String? = null,
    /** Optional state value for display (v1 format, deprecated in v2). */
    val state: String? = null,
    /** Hide icon if entity state is unknown (v2 config-only format). */
    val hideIfUnknown: Boolean = false,
)

/**
 * Information tile configuration.
 * Displays entity state with label and optional color coding.
 */
@Serializable
data class WatchFaceTile(
    /** Display label (e.g., "Living Room"). */
    val label: String,
    /** Current value display (e.g., "72°F"). Optional - can be populated from state. */
    val value: String? = null,
    /** Tile color as hex string. */
    val color: String = "#FFFFFF",
    /** Optional device ID for device-level actions/targeting. */
    @kotlinx.serialization.SerialName("device_id")
    val deviceId: String? = null,
    /** Optional entity ID to bind to live state updates. */
    val entity: String? = null,
    /** Color mode: "auto" (based on entity state) or "manual" (static color). */
    val colorMode: String? = null,
    val activeColor: String? = null,
    val inactiveColor: String? = null,
    /** Icon identifier (e.g., "garage", "light", "door"). */
    val icon: String? = null,
    /** Display mode: 0 = text label (default), 1 = icon glyph. */
    val displayMode: Int? = null,
    /** Show confirmation before toggling (for locks, covers). Also forced true for quick_controls screens. */
    val confirm: Boolean? = null,
)

/**
 * Status bar configuration.
 * Supports two formats:
 * 1. Simple booleans: {"battery": true, "steps": false}
 * 2. Rich objects: {"battery": {"percent": 100, "color": "#FFF"}}
 *
 * Uses JsonElement to accept both formats. Parser will transform to boolean flags.
 */
@Serializable
data class WatchFaceStatusBar(
    /** Show battery - accepts Boolean or an object. */
    val battery: kotlinx.serialization.json.JsonElement? = null,
    /** Show steps - accepts Boolean or an object. */
    val steps: kotlinx.serialization.json.JsonElement? = null,
    /** Show heart rate - accepts Boolean or an object. */
    val heartRate: kotlinx.serialization.json.JsonElement? = null,
    /** Show notifications - accepts Boolean or an object. */
    val notifications: kotlinx.serialization.json.JsonElement? = null,
    /** Show Bluetooth - accepts Boolean or an object. */
    val bluetooth: kotlinx.serialization.json.JsonElement? = null,
)

/**
 * Multi-screen support models for watch face import.
 */

@Serializable
data class WatchFaceScreenImport(
    /** Screen type: can be Int (0=dashboard, 1=tiles_actions, 2=logbook, 3=quick_controls) or String. */
    val screenType: kotlinx.serialization.json.JsonElement? = null,
    val name: String? = null,
    /** Tile grid layout: 4 (2x2 grid) or 6 (3x2 grid). */
    val tileLayout: Int? = null,
    val tiles: List<WatchFaceTile> = emptyList(),
    val dashboard: WatchFaceDashboardImport? = null,
    /** Logbook configuration (used if screenType == 2). */
    val logbook: WatchFaceLogbookImport? = null,
)

/**
 * Logbook screen configuration for import JSON.
 * Contains entity IDs and query parameters — Android app fetches logbook data from HA.
 */
@Serializable
data class WatchFaceLogbookImport(
    /** Entity IDs to fetch logbook events for. */
    val entityIds: List<String> = emptyList(),
    /** Maximum entries to send to the watch (default 15). */
    val maxEntries: Int = 15,
    /** How many hours back to query (default 12). */
    val hoursBack: Int = 12,
)

@Serializable
data class WatchFaceDashboardImport(
    val statusGroups: List<WatchFaceStatusGroupImport> = emptyList(),
    val sensorGroups: List<WatchFaceSensorGroupImport> = emptyList(),
    val weather: WatchFaceWeatherImport? = null,
    /** Events - can be list of WatchFaceEvent (v1) or list of WatchFaceCalendarImport (v2). */
    val events: List<WatchFaceCalendarImport> = emptyList(),
    val notifications: List<WatchFaceNotificationImport> = emptyList(),
    /** Configurable headline value row (FR-19). Null = not configured. */
    val configurableValue: WatchFaceConfigurableValueImport? = null,
    /** Inline metric slots around the clock (FR-20). Up to 2 entries, null entry = not configured. */
    val inlineMetrics: List<WatchFaceInlineMetricImport?> = emptyList(),
)

/**
 * Calendar configuration for dashboard events (v2 config-only format).
 * Contains calendar entity ID - Android app queries HA for current events.
 */
@Serializable
data class WatchFaceCalendarImport(
    /** Calendar entity ID to query for events. */
    val calendar: String,
)

@Serializable
data class WatchFaceStatusGroupImport(
    val type: String,
    val glyphs: List<WatchFaceGlyphImport> = emptyList(),
)

@Serializable
data class WatchFaceGlyphImport(
    val symbol: String,
    val entity: String,
)

@Serializable
data class WatchFaceSensorGroupImport(
    val type: String,
    val sensors: List<WatchFaceSensorImport> = emptyList(),
)

@Serializable
data class WatchFaceSensorImport(
    val entity: String,
    val displayType: String = "temperature",
)

@Serializable
data class WatchFaceWeatherImport(
    val entity: String,
)

@Serializable
data class WatchFaceNotificationImport(
    val source: String,
)

/**
 * Configurable headline value row config (v2 config-only format).
 * Android resolves the HA entity state and serializes label + value into CBOR.
 */
@Serializable
data class WatchFaceConfigurableValueImport(
    /** Optional display label (0–11 chars). Empty = no label column rendered. */
    val label: String = "",
    /** HA entity ID to resolve for the displayed state. */
    val entity: String,
    /** Max characters for the value string (default 19, firmware buffer limit). */
    val truncateTo: Int = 19,
)

/**
 * Inline metric slot config (v2 config-only format).
 * Android resolves the HA entity state and coerces it to ≤3 chars.
 */
@Serializable
data class WatchFaceInlineMetricImport(
    /** Optional 2-char label (empty = no label rendered). */
    val label: String = "",
    /** HA entity ID to resolve for the displayed value. */
    val entity: String,
    /** Round numeric values to nearest integer (default true). */
    val round: Boolean = true,
)

/**
 * Logbook app import configuration.
 * Defines entity IDs and query parameters for the standalone Logbook watch app.
 */
@Serializable
data class WatchFaceLogbookAppImport(
    val entityIds: List<String> = emptyList(),
    val maxEntries: Int = 10,
    val hoursBack: Int = 12,
)

/** A single device entry in the home control app import config. */
@Serializable
data class WatchFaceHomeControlDeviceImport(
    val entityId: String,
    val name: String? = null,
    val domain: String? = null,
)

/**
 * Home control app import configuration.
 * Defines which HA entities appear in the watch Home Control app.
 */
@Serializable
data class WatchFaceHomeControlImport(
    val devices: List<WatchFaceHomeControlDeviceImport> = emptyList(),
)
