package com.w6tech.infinassistant.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.w6tech.infinassistant.core.common.AppResult
import com.w6tech.infinassistant.core.data.BridgeEngine
import com.w6tech.infinassistant.core.data.HaApi
import com.w6tech.infinassistant.core.data.SettingsRepository
import com.w6tech.infinassistant.core.data.bridge.WatchFaceOrchestrator
import com.w6tech.infinassistant.core.data.bridge.WatchFaceOrchestrator.WatchFaceLayoutResult
import com.w6tech.infinassistant.core.domain.FirmwareLimits
import com.w6tech.infinassistant.core.domain.ParseResult
import com.w6tech.infinassistant.core.domain.ProtocolCbor
import com.w6tech.infinassistant.core.domain.WatchFaceLayoutPayload
import com.w6tech.infinassistant.core.domain.WatchFaceParser
import com.w6tech.infinassistant.core.domain.WatchFaceWire
import com.w6tech.infinassistant.core.ha.HaDeviceRegistryEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The Home Assistant domain of the InfinAssistant integration, as it appears in a device's identifiers. */
internal const val HA_INTEGRATION_DOMAIN = "infinassistant"

/** True when [device] was registered by the InfinAssistant integration. */
internal fun isInfinAssistantDevice(device: HaDeviceRegistryEntry): Boolean =
    device.identifiers.any { identifier -> identifier.getOrNull(0)?.lowercase() == HA_INTEGRATION_DOMAIN }

/**
 * ViewModel for importing watch face configurations.
 * Handles parsing JSON, fetching from Home Assistant, and applying configuration.
 */
@HiltViewModel
class WatchFaceImportViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val bridgeEngine: BridgeEngine,
    private val haApi: HaApi,
) : ViewModel() {

    private val _uiState = MutableStateFlow<WatchFaceImportUiState>(WatchFaceImportUiState.Idle)
    val uiState: StateFlow<WatchFaceImportUiState> = _uiState

    private val _devices = MutableStateFlow<List<HaDeviceRegistryEntry>>(emptyList())
    val devices: StateFlow<List<HaDeviceRegistryEntry>> = _devices

    private val _deviceFetchError = MutableStateFlow<String?>(null)
    val deviceFetchError: StateFlow<String?> = _deviceFetchError

    private val _lastPushedJson = MutableStateFlow<String?>(null)
    val lastPushedJson: StateFlow<String?> = _lastPushedJson

    init {
        // Fetch devices on init
        fetchDevices()

        // Load last pushed JSON
        viewModelScope.launch {
            settingsRepo.settings.collect { settings ->
                _lastPushedJson.value = settings.lastPushedWatchFaceJson
            }
        }
    }

    /**
     * Import watch face configuration from JSON string.
     * Parses and validates the JSON, then updates UI state with result.
     */
    fun importFromJsonString(jsonString: String) {
        viewModelScope.launch {
            _uiState.value = WatchFaceImportUiState.Loading

            val parseResult = WatchFaceParser.parseWatchFaceJson(jsonString)
            when (parseResult) {
                is ParseResult.Success -> {
                    val summary = buildSummary(parseResult.payload, jsonString)
                    _uiState.value = WatchFaceImportUiState.Parsed(
                        payload = parseResult.payload,
                        summary = summary,
                        originalJson = jsonString
                    )
                }
                is ParseResult.Error -> {
                    _uiState.value = WatchFaceImportUiState.Error(parseResult.message)
                }
            }
        }
    }

    /**
     * Fetch available devices from Home Assistant.
     * Called on init to populate device dropdown.
     * Filters to only show InfinAssistant devices (PineTime watches).
     */
    private fun fetchDevices() {
        viewModelScope.launch {
            _deviceFetchError.value = null
            try {
                when (val result = haApi.fetchDeviceRegistry()) {
                    is AppResult.Ok -> {
                        // Filter to only show InfinAssistant devices
                        val infinassistantDevices = result.value.filter(::isInfinAssistantDevice)

                        _devices.value = infinassistantDevices.sortedBy {
                            it.nameByUser ?: it.name ?: it.id
                        }

                        if (infinassistantDevices.isEmpty()) {
                            _deviceFetchError.value = "No InfinAssistant (PineTime) devices found.\nMake sure the InfinAssistant integration is configured in Home Assistant."
                        }
                    }
                    is AppResult.Err -> {
                        _deviceFetchError.value = "Failed to load devices: ${result.error.message}"
                    }
                }
            } catch (e: Exception) {
                _deviceFetchError.value = "Error loading devices: ${e.message ?: "Unknown error"}"
            }
        }
    }

    /** Retry fetching devices */
    fun retryFetchDevices() {
        fetchDevices()
    }

    /**
     * Fetch watch face configuration from Home Assistant service.
     * Calls infinassistant.get_watchface with the provided device ID.
     */
    fun fetchFromHA(deviceId: String) {
        viewModelScope.launch {
            _uiState.value = WatchFaceImportUiState.Loading

            when (val result = haApi.fetchWatchFaceForDevice(deviceId)) {
                is AppResult.Ok -> {
                    bridgeEngine.recordWatchFaceFetchError(null)
                    importFromJsonString(result.value)
                }
                is AppResult.Err -> {
                    val detail = result.error.message ?: "Unknown error"
                    // Also record it on BridgeStatus so it reaches the Debug
                    // screen and any debug bundle. This screen is transient, so
                    // a failure here otherwise leaves no trace to diagnose from
                    // — which is exactly how this stayed unexplained while the
                    // user worked around it with a cached layout.
                    bridgeEngine.recordWatchFaceFetchError("device=$deviceId: $detail")
                    _uiState.value = WatchFaceImportUiState.Error(
                        "Failed to fetch from HA: $detail"
                    )
                }
            }
        }
    }

    /**
     * Apply the parsed configuration to the watch.
     * Sends the watch face layout to BridgeEngine and shows result feedback.
     */
    fun applyConfiguration(nav: NavHostController) {
        val currentState = _uiState.value
        if (currentState !is WatchFaceImportUiState.Parsed) {
            return
        }

        viewModelScope.launch {
            _uiState.value = WatchFaceImportUiState.Applying()
            try {
                // Send to bridge engine and wait for result with progress updates
                when (val result = bridgeEngine.sendWatchFaceLayout(
                    payload = currentState.payload,
                    onProgress = { current, total ->
                        _uiState.value = WatchFaceImportUiState.Applying(current, total)
                    }
                )) {
                    is WatchFaceLayoutResult.Success -> {
                        // Save the JSON for future reference
                        settingsRepo.setLastPushedWatchFaceJson(currentState.originalJson)

                        _uiState.value = WatchFaceImportUiState.Applied()
                        // Navigate back after a brief delay to show success message
                        kotlinx.coroutines.delay(1500)
                        nav.popBackStack()
                    }
                    is WatchFaceLayoutResult.SuccessTrimmed -> {
                        settingsRepo.setLastPushedWatchFaceJson(currentState.originalJson)

                        // Screens were left out: stay long enough to read which.
                        _uiState.value = WatchFaceImportUiState.Applied(result.warning)
                        kotlinx.coroutines.delay(5000)
                        nav.popBackStack()
                    }
                    is WatchFaceLayoutResult.AckFailed -> {
                        // Save JSON even on ACK failure - watch may have received it
                        settingsRepo.setLastPushedWatchFaceJson(currentState.originalJson)

                        _uiState.value = WatchFaceImportUiState.Error(
                            "Watch rejected configuration: ${result.message}"
                        )
                    }
                    is WatchFaceLayoutResult.Timeout -> {
                        // Save JSON even on timeout - watch may have received it
                        settingsRepo.setLastPushedWatchFaceJson(currentState.originalJson)

                        _uiState.value = WatchFaceImportUiState.Error(
                            "Watch did not respond. Check BLE connection and try again."
                        )
                    }
                    is WatchFaceLayoutResult.NotConnected -> {
                        _uiState.value = WatchFaceImportUiState.Error(
                            "Watch not connected. Please connect via Connected Mode first."
                        )
                    }
                    is WatchFaceLayoutResult.PreflightFailed -> {
                        _uiState.value = WatchFaceImportUiState.Error(
                            "Layout exceeds firmware budget: ${result.reason}"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.value = WatchFaceImportUiState.Error(
                    "Failed to apply configuration: ${e.message ?: "Unknown error"}"
                )
            }
        }
    }

    /**
     * REQ-074 — Compute the four real-time editor metrics for a parsed layout, measuring
     * `cborBytes` in the key spelling the connected watch negotiated (see [buildConfigSummary]).
     */
    private fun buildSummary(payload: WatchFaceLayoutPayload, originalJson: String): ConfigSummary =
        buildConfigSummary(
            payload,
            shortKeys = bridgeEngine.watchAcceptsShortKeys(),
            screenCap = bridgeEngine.watchScreenCap(),
        )

    /**
     * Clear error state and return to idle.
     */
    fun clearError() {
        _uiState.value = WatchFaceImportUiState.Idle
    }
}

/**
 * REQ-074 — Compute the four real-time editor metrics for a parsed layout.
 *
 * Metrics:
 *   objectCount   — total LVGL objects: glyphs + sensors across all dashboard screens
 *   styleCount    — total style slots: one per status-group + one per sensor-group
 *   cborBytes     — the bytes a push of this layout would hand the transport: the watch-face
 *                   encoder (defaults omitted) in the connected watch's negotiated key
 *                   spelling, [shortKeys] true for short and false for long or unknown
 *                   (REQ009-FR-C4). Same call as the push path,
 *                   [ProtocolCbor.encodeWatchFaceLayout].
 *   lvglPoolPct   — projected LVGL pool occupancy in percent; constants sourced from
 *                   FirmwareLimits (SOT: scripts/config/firmware-limits.yaml).
 *                   Saturates at 100.
 */
internal fun buildConfigSummary(
    payload: WatchFaceLayoutPayload,
    shortKeys: Boolean,
    screenCap: Int = WatchFaceOrchestrator.LEGACY_FW_SCREENS,
): ConfigSummary {
    var objectCount = 0
    var styleCount = 0
    for (screen in payload.screens) {
        val dash = screen.dashboard ?: continue
        objectCount += dash.statusGroups.sumOf { it.glyphs.size }
        objectCount += dash.sensorGroups.sumOf { it.sensors.size }
        styleCount += dash.statusGroups.size + dash.sensorGroups.size
    }
    val cborBytes = try {
        // The stripped copy, as the push sends it (WatchFaceOrchestrator), so the figure is what the transport gets.
        ProtocolCbor.encodeWatchFaceLayout(WatchFaceWire.stripNonRendered(payload), shortKeys).bytes.size
    } catch (_: Exception) {
        0
    }
    val projectedBytes = objectCount * FirmwareLimits.LVGL_BYTES_PER_OBJECT +
        styleCount * FirmwareLimits.LVGL_BYTES_PER_STYLE
    val lvglPoolPct = ((projectedBytes.toLong() * 100L) / FirmwareLimits.LVGL_POOL_BYTES.toLong())
        .coerceIn(0L, 100L).toInt()
    return ConfigSummary(
        version = payload.version,
        screenCount = payload.screens.size,
        screenCap = screenCap,
        tileCount = payload.screens.sumOf { it.tiles.size },
        themeAccent = payload.theme.accent,
        objectCount = objectCount,
        styleCount = styleCount,
        cborBytes = cborBytes,
        lvglPoolPct = lvglPoolPct,
    )
}

/**
 * UI state for watch face import screen.
 */
sealed interface WatchFaceImportUiState {
    /** Initial state, no import in progress. */
    object Idle : WatchFaceImportUiState

    /** Loading state while parsing or fetching. */
    object Loading : WatchFaceImportUiState

    /** Successfully parsed configuration, ready to apply. */
    data class Parsed(
        val payload: WatchFaceLayoutPayload,
        val summary: ConfigSummary,
        val originalJson: String
    ) : WatchFaceImportUiState

    /** Sending configuration to watch, waiting for response. */
    data class Applying(val current: Int = 0, val total: Int = 0) : WatchFaceImportUiState

    /** Configuration successfully applied to watch; [warning] says which screens the watch's cap left out, if any. */
    data class Applied(val warning: String? = null) : WatchFaceImportUiState

    /** Error state with user-friendly message. */
    data class Error(val message: String) : WatchFaceImportUiState
}

/**
 * Summary of parsed configuration for display in UI.
 *
 * REQ-074 adds objectCount, styleCount, cborBytes, and lvglPoolPct for the
 * real-time Green/Yellow/Red editor score.
 */
data class ConfigSummary(
    val version: Int,
    val screenCount: Int,
    /** The screens the connected watch holds (6 with the six-screen HELLO bit, else 4); the layout is trimmed to it at the push. */
    val screenCap: Int,
    val tileCount: Int,
    val themeAccent: String,
    // REQ-074 — real-time editor metrics
    /** Total LVGL objects: sum of all glyphs + sensors across dashboard screens. */
    val objectCount: Int = 0,
    /** Total style slots: one per status-group + one per sensor-group. */
    val styleCount: Int = 0,
    /** CBOR-encoded payload size in bytes: the bytes a push would send, in the watch's negotiated key spelling. */
    val cborBytes: Int = 0,
    /** Projected LVGL pool occupancy 0-100 (%). Green <50, Yellow 50-80, Red >80. */
    val lvglPoolPct: Int = 0,
)
