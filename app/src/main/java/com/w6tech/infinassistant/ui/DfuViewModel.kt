package com.w6tech.infinassistant.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.w6tech.infinassistant.bridge.BridgeForegroundService
import com.w6tech.infinassistant.core.ble.BleConnectionState
import com.w6tech.infinassistant.core.data.BridgeEngine
import com.w6tech.infinassistant.core.data.DiagnosticLogger
import com.w6tech.infinassistant.core.data.DfuVersionGuard
import com.w6tech.infinassistant.core.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.nordicsemi.android.dfu.DfuProgressListener
import no.nordicsemi.android.dfu.DfuProgressListenerAdapter
import no.nordicsemi.android.dfu.DfuServiceController
import no.nordicsemi.android.dfu.DfuServiceInitiator
import no.nordicsemi.android.dfu.DfuServiceListenerHelper
import org.json.JSONObject
import java.util.zip.ZipInputStream
import javax.inject.Inject

data class DfuState(
    val deviceAddress: String? = null,
    val fileUri: Uri? = null,
    val isInProgress: Boolean = false,
    val progress: Int = 0,
    val status: String = "Ready",
    val error: String? = null,
)

@HiltViewModel
class DfuViewModel @Inject constructor(
    application: Application,
    private val settingsRepo: SettingsRepository,
    private val engine: BridgeEngine,
    private val diagnosticLogger: DiagnosticLogger,
) : AndroidViewModel(application) {

    /**
     * Every step of an update goes into the diagnostic log, so a debug bundle shows whether
     * a transfer finished and how it ended. The progress state below lives only on screen:
     * a transfer that stopped part way looked, from a bundle, exactly like a new image that
     * booted and was rolled back.
     */
    private fun dfuLog(msg: String, extra: String? = null) {
        diagnosticLogger.log(level = "INFO", tag = "dfu", msg = msg, extra = extra)
    }

    /** Connected Mode was on when the update began: turn it back on when the update ends. */
    private var restoreConnectedMode = false

    private companion object {
        /** How long the phone stays off the watch after an update ends (see restoreConnectedModeAfterDfu). */
        const val RECONNECT_DELAY_MS = 120_000L

        const val NO_FIRMWARE_ERROR = "No firmware is bundled with this app (firmware/index.json is missing or lists no package)."

        /** The wake lock outlasts the wait by this much, for the engine to reach the watch. */
        const val RECONNECT_WAKE_TAIL_MS = 30_000L

        /** No report from the library for this long, once the transfer has started, is a stall. */
        const val STALL_TIMEOUT_MS = 60_000L
        const val STALL_CHECK_MS = 5_000L

        /** How long an abort may take before the screen is released anyway. */
        const val ABORT_GRACE_MS = 10_000L
    }

    /** The last progress decile logged (0..10): ten progress lines per update, not a hundred. */
    private var loggedDecile = -1

    /** The running update, kept so a stalled or cancelled one can be aborted. */
    private var dfuController: DfuServiceController? = null

    /** When the library last reported anything (elapsedRealtime). */
    @Volatile
    private var lastDfuReportAt = 0L

    private var stallWatch: Job? = null

    /** Set when the stall watch aborted the update: the progress it stalled at. */
    private var stalledAtPercent: Int? = null

    /** The upload is done: the watch is validating the image, or the library is disconnecting. */
    private var dfuPastUpload = false

    private fun noteDfuReport() {
        lastDfuReportAt = SystemClock.elapsedRealtime()
    }

    /**
     * Once the transfer has started, the library reports at least every few seconds. A lost
     * packet receipt notification used to leave it waiting for good, with no error, disconnect
     * or end: 2.4.84's update sat at 84 % for 30 minutes. No report for [STALL_TIMEOUT_MS] now
     * aborts it, so the user can try again at once.
     */
    private fun startStallWatch() {
        stallWatch?.cancel()
        noteDfuReport()
        stallWatch = viewModelScope.launch {
            while (_state.value.isInProgress) {
                delay(STALL_CHECK_MS)
                val idleMs = SystemClock.elapsedRealtime() - lastDfuReportAt
                if (!_state.value.isInProgress || idleMs < STALL_TIMEOUT_MS) continue
                val percent = _state.value.progress
                if (dfuPastUpload) {
                    // Validated and disconnecting: the image is on the watch, which restarts by
                    // itself; only the library's end report is missing (2.4.80's attempt at 98 %).
                    dfuLog("end unreported", "no report for ${idleMs / 1000} s at $percent%")
                    _state.value = _state.value.copy(
                        status = "Update sent; the watch restarts by itself",
                        progress = 100,
                        isInProgress = false,
                    )
                    restoreConnectedModeAfterDfu("end unreported")
                    return@launch
                }
                stalledAtPercent = percent
                dfuLog("stalled", "no report for ${idleMs / 1000} s at $percent%; aborting")
                dfuController?.abort()
                // A library that does not answer the abort still releases the screen.
                delay(ABORT_GRACE_MS)
                if (_state.value.isInProgress) {
                    dfuLog("abort unanswered")
                    _state.value = _state.value.copy(
                        status = "Transfer stalled",
                        progress = 0,
                        isInProgress = false,
                        error = stalledMessage(percent),
                    )
                    restoreConnectedModeAfterDfu("stalled")
                }
                return@launch
            }
        }
    }

    private fun stopStallWatch() {
        stallWatch?.cancel()
        stallWatch = null
    }

    private fun stalledMessage(percent: Int) =
        "The transfer stalled at $percent%. Try again. If it does not start, force-stop the app " +
            "first; if it stalls again, restart the watch."

    /**
     * An update ends by completing, failing or being aborted, and the watch then reboots
     * into, or stays on, some firmware. With Connected Mode back on the phone reconnects and
     * logs that firmware's HELLO (version and uptime), so a bundle shows whether the new
     * image booted and stayed, with nobody watching the watch.
     */
    private fun restoreConnectedModeAfterDfu(outcome: String) {
        if (!restoreConnectedMode) return
        restoreConnectedMode = false
        viewModelScope.launch {
            // Leave the watch alone while it installs and boots the new image (bootloader,
            // swap, first boot, and the restart that follows if that boot fails: about a
            // minute and a half), so its first boot runs without the phone, as every first boot
            // before this delay did. NonCancellable: leaving this screen must not leave the
            // phone off the watch.
            withContext(NonCancellable) {
                // The service and the engine start now, with Connected Mode still off (the engine
                // idles until it is on), and the wait runs inside a process the service keeps
                // alive. Started after the wait, as until 2.1.97, nothing ran in between: Android
                // froze the cached process, and after one update Connected Mode came back after
                // 59 min, when the app was opened, instead of 120 s.
                val earlyNote = startEngineAndService()
                dfuLog("Connected Mode back on in ${RECONNECT_DELAY_MS / 1000} s", "$outcome; $earlyNote")
                // The wait runs on the uptime clock, which stops while the phone sleeps, and with
                // the watch link down nothing wakes it: 120 s took 8 min 52 s after one update
                // (2.1.105), the watch showing 1 January and "No phone" until the phone came
                // back. A partial wake lock holds the wait to 120 s, and the engine's first
                // attempt on the watch after it; it times out on its own as well.
                val wakeLock = (getApplication<Application>().getSystemService(Context.POWER_SERVICE) as? PowerManager)
                    ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "InfinAssistant:DfuReconnect")
                    ?.apply {
                        setReferenceCounted(false)
                        acquire(RECONNECT_DELAY_MS + RECONNECT_WAKE_TAIL_MS + 10_000L)
                    }
                try {
                    delay(RECONNECT_DELAY_MS)
                    settingsRepo.setConnectedModeEnabled(true)
                    // Again, in case the early start was refused (a service started from the
                    // background); both are no-ops when already running.
                    val serviceNote = startEngineAndService()
                    dfuLog("Connected Mode back on", "$outcome; $serviceNote")
                    delay(RECONNECT_WAKE_TAIL_MS)
                } finally {
                    if (wakeLock?.isHeld == true) wakeLock.release()
                }
            }
        }
    }

    /**
     * The update stopped the engine outright, and the Connected Mode setting alone does not restart
     * it: the foreground service starts the engine only when it is created (without this the phone
     * stayed off the watch until Connected Mode was toggled by hand, 46 min after one update). An
     * engine without its foreground service does not survive in the background either (27 min of
     * silence after another update, until the app was opened). engine.start() is a no-op when the
     * engine runs, and starting a running service changes nothing. Returns a note for the log.
     */
    private fun startEngineAndService(): String {
        engine.start()
        val app = getApplication<Application>()
        val service = Intent(app, BridgeForegroundService::class.java).apply {
            action = BridgeForegroundService.ACTION_START
            // Not started from the screen: the service must not ask for the microphone type.
            putExtra(BridgeForegroundService.EXTRA_BACKGROUND_START, true)
        }
        return try {
            ContextCompat.startForegroundService(app, service)
            "service started"
        } catch (e: Exception) {
            "service not started: ${e.javaClass.simpleName}"
        }
    }

    private val _state = MutableStateFlow(DfuState())
    val state: StateFlow<DfuState> = _state.asStateFlow()

    // Cached embedded firmware version (read once from DFU zip manifest)
    private val _embeddedFirmwareVersion = MutableStateFlow<String?>(null)
    val embeddedFirmwareVersion: StateFlow<String?> = _embeddedFirmwareVersion.asStateFlow()

    /** The packages listed in firmware/index.json, in display order; empty without a usable index. */
    private val _bundledFirmware = MutableStateFlow<List<BundledFirmware>>(emptyList())
    val bundledFirmware: StateFlow<List<BundledFirmware>> = _bundledFirmware.asStateFlow()

    /**
     * The bundled package picked to flash, as an index into [bundledFirmware]. Null until one is
     * picked, and again once a custom file or a file from HA takes its place.
     */
    private val _selectedFirmware = MutableStateFlow<Int?>(null)
    val selectedFirmware: StateFlow<Int?> = _selectedFirmware.asStateFlow()

    /** Copies the picked package into the cache; picking again cancels it. */
    private var prepareJob: Job? = null

    private val dfuProgressListener: DfuProgressListener = object : DfuProgressListenerAdapter() {
        override fun onDeviceConnecting(deviceAddress: String) {
            dfuLog("connecting")
            _state.value = _state.value.copy(
                status = "Connecting to device...",
                progress = 0
            )
        }

        override fun onDeviceConnected(deviceAddress: String) {
            dfuLog("connected")
            _state.value = _state.value.copy(
                status = "Device connected",
                progress = 2
            )
        }

        override fun onDfuProcessStarting(deviceAddress: String) {
            dfuLog("process starting")
            _state.value = _state.value.copy(
                status = "Starting DFU process...",
                progress = 5
            )
        }

        override fun onDfuProcessStarted(deviceAddress: String) {
            dfuLog("process started")
            _state.value = _state.value.copy(
                status = "DFU started",
                progress = 10
            )
            startStallWatch()
        }

        override fun onEnablingDfuMode(deviceAddress: String) {
            dfuLog("enabling DFU mode")
            _state.value = _state.value.copy(
                status = "Enabling DFU mode...",
                progress = 15
            )
        }

        override fun onProgressChanged(
            deviceAddress: String,
            percent: Int,
            speed: Float,
            avgSpeed: Float,
            currentPart: Int,
            partsTotal: Int
        ) {
            noteDfuReport()
            val decile = percent / 10
            if (decile > loggedDecile) {
                loggedDecile = decile
                dfuLog("progress $percent%", "avg ${"%.1f".format(avgSpeed)} kB/s")
            }
            _state.value = _state.value.copy(
                status = "Uploading firmware... (Part $currentPart/$partsTotal)",
                progress = percent
            )
        }

        override fun onFirmwareValidating(deviceAddress: String) {
            noteDfuReport()
            dfuPastUpload = true
            dfuLog("validating")
            _state.value = _state.value.copy(
                status = "Validating firmware...",
                progress = 95
            )
        }

        override fun onDeviceDisconnecting(deviceAddress: String?) {
            noteDfuReport()
            dfuPastUpload = true
            dfuLog("disconnecting")
            _state.value = _state.value.copy(
                status = "Disconnecting...",
                progress = 98
            )
        }

        override fun onDeviceDisconnected(deviceAddress: String) {
            noteDfuReport()
            dfuLog("disconnected")
            _state.value = _state.value.copy(
                status = "Update complete!",
                progress = 100
            )
        }

        override fun onDfuCompleted(deviceAddress: String) {
            stopStallWatch()
            dfuController = null
            dfuLog("completed")
            _state.value = _state.value.copy(
                status = "DFU completed successfully",
                progress = 100,
                isInProgress = false
            )
            restoreConnectedModeAfterDfu("completed")
        }

        override fun onDfuAborted(deviceAddress: String) {
            stopStallWatch()
            dfuController = null
            val stalledAt = stalledAtPercent
            stalledAtPercent = null
            dfuLog("aborted", if (stalledAt != null) "stalled at $stalledAt%" else null)
            _state.value = _state.value.copy(
                status = if (stalledAt != null) "Transfer stalled" else "DFU aborted",
                progress = 0,
                isInProgress = false,
                error = if (stalledAt != null) stalledMessage(stalledAt) else "Update was cancelled"
            )
            restoreConnectedModeAfterDfu(if (stalledAt != null) "stalled" else "aborted")
        }

        override fun onError(deviceAddress: String, error: Int, errorType: Int, message: String?) {
            stopStallWatch()
            dfuController = null
            dfuLog("error", "code $error type $errorType: ${message ?: "-"}")
            _state.value = _state.value.copy(
                status = "Error occurred",
                progress = 0,
                isInProgress = false,
                error = message ?: "DFU error: $error (type: $errorType)"
            )
            restoreConnectedModeAfterDfu("error $error")
        }
    }

    init {
        // Register the DFU progress listener
        DfuServiceListenerHelper.registerProgressListener(getApplication(), dfuProgressListener)

        // Load the bundled firmware index, then the embedded firmware version from the
        // default package's manifest (cached for app lifetime)
        viewModelScope.launch {
            loadBundledFirmware()
            loadEmbeddedFirmwareVersion()
        }

        // Load device address from settings
        viewModelScope.launch {
            settingsRepo.settings.collect { settings ->
                // Only update deviceAddress if not in the middle of a DFU
                if (!_state.value.isInProgress) {
                    _state.value = _state.value.copy(
                        deviceAddress = settings.primaryDeviceAddress,
                        status = if (settings.primaryDeviceAddress != null) "Ready" else "No device paired"
                    )
                }
            }
        }
    }

    /**
     * Reads the bundled firmware index (firmware/index.json). Without that asset, or when it lists
     * no usable package, the list stays empty and the screen shows an error.
     */
    private suspend fun loadBundledFirmware() {
        val index = withContext(Dispatchers.IO) {
            try {
                getApplication<Application>().assets.open(FIRMWARE_INDEX_ASSET).use { input ->
                    String(input.readBytes(), Charsets.UTF_8)
                }
            } catch (_: Exception) {
                null
            }
        }
        val firmware = index?.let { parseFirmwareIndex(it) }.orEmpty()
        if (firmware.isEmpty()) {
            dfuLog("no usable firmware index", FIRMWARE_INDEX_ASSET)
            _state.value = _state.value.copy(error = NO_FIRMWARE_ERROR, status = "No firmware bundled")
        }
        _bundledFirmware.value = firmware
    }

    /**
     * Reads the default bundled package's (the first in firmware/index.json) manifest.json to
     * extract the firmware version. The version is parsed from the bin_file field (e.g., "pinetime-mcuboot-app-image-1.35.0.bin").
     * This is cached for the lifetime of the app process.
     */
    private suspend fun loadEmbeddedFirmwareVersion() {
        if (_embeddedFirmwareVersion.value != null) return // Already cached

        withContext(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val assetName = _bundledFirmware.value.firstOrNull()?.file ?: return@withContext

                context.assets.open(assetName).use { assetStream ->
                    ZipInputStream(assetStream).use { zipStream ->
                        var entry = zipStream.nextEntry
                        while (entry != null) {
                            if (entry.name == "manifest.json") {
                                val manifestBytes = zipStream.readBytes()
                                val manifestJson = JSONObject(String(manifestBytes, Charsets.UTF_8))

                                // Parse: {"manifest":{"application":{"bin_file":"pinetime-mcuboot-app-image-1.35.0.bin",...}}}
                                val binFile = manifestJson
                                    .optJSONObject("manifest")
                                    ?.optJSONObject("application")
                                    ?.optString("bin_file")

                                if (!binFile.isNullOrBlank()) {
                                    // Extract version from filename: pinetime-mcuboot-app-X.X.X.bin (or with -image- prefix)
                                    val versionRegex = Regex("""pinetime-mcuboot-app-(?:image-)?(\d+\.\d+\.\d+)\.bin""")
                                    val match = versionRegex.find(binFile)
                                    _embeddedFirmwareVersion.value = match?.groupValues?.get(1)
                                }
                                break
                            }
                            entry = zipStream.nextEntry
                        }
                    }
                }
            } catch (e: Exception) {
                // Failed to read version, leave as null (will show fallback text)
                _embeddedFirmwareVersion.value = null
            }
        }
    }

    /**
     * REQ-043 helper — reads the manifest.json bytes from the DFU zip at [uri]
     * so DfuVersionGuard can validate against MIN_DFU_VERSION. Returns null on
     * any IO/zip exception or when the zip has no manifest.json (legacy DFU
     * format predating Nordic's manifest convention).
     */
    private suspend fun readManifestBytes(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        try {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (entry.name == "manifest.json") return@withContext zip.readBytes()
                        entry = zip.nextEntry
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Unregister the DFU progress listener
        DfuServiceListenerHelper.unregisterProgressListener(getApplication(), dfuProgressListener)
    }

    fun selectFile(uri: Uri) {
        // A custom file takes the place of any bundled package picked on screen
        prepareJob?.cancel()
        _selectedFirmware.value = null
        _state.value = _state.value.copy(
            fileUri = uri,
            error = null,
            status = "File selected: ${uri.lastPathSegment}"
        )
    }

    /**
     * Picks the first bundled package, the default, unless a file is already chosen. The screen
     * calls this when it shows the picker, so the selected package is ready for Start Update.
     */
    fun selectDefaultFirmware() {
        if (_selectedFirmware.value != null || _state.value.fileUri != null) return
        selectBundledFirmware(0)
    }

    /**
     * Picks the bundled package at [index] and gets it ready to flash: the same copy into the
     * cache and FileProvider URI as the embedded package, under the package's own file name so
     * the update's start line in the log names the package. Ignored during an update.
     */
    fun selectBundledFirmware(index: Int) {
        if (_state.value.isInProgress) return
        val firmware = _bundledFirmware.value.getOrNull(index) ?: return
        prepareJob?.cancel()
        _selectedFirmware.value = index
        // Nothing can start until this package is ready, and the last update's outcome no
        // longer describes what is on screen
        _state.value = _state.value.copy(
            fileUri = null,
            progress = 0,
            error = null,
            status = "Preparing ${firmware.title}..."
        )
        prepareJob = viewModelScope.launch {
            try {
                val (uri, size) = withContext(Dispatchers.IO) { copyBundledFirmware(firmware) }
                val version = readManifestBytes(uri)?.let { DfuVersionGuard.parseManifestVersion(it) }
                // A later pick, a custom file or a file from HA has taken this one's place
                if (!pickStillPending(index)) return@launch
                val versionInfo = version?.let { "v$it" } ?: "unknown version"
                _state.value = _state.value.copy(
                    fileUri = uri,
                    error = null,
                    status = "Ready: ${firmware.title} ($versionInfo, ${size / 1024} KB)"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!pickStillPending(index)) return@launch
                _state.value = _state.value.copy(
                    error = "Failed to load bundled firmware ${firmware.file}: ${e.message}",
                    status = "Error loading bundled firmware"
                )
            }
        }
    }

    /** True while the pick at [index] is still the one to get ready: no other file and no update since. */
    private fun pickStillPending(index: Int): Boolean {
        val current = _state.value
        return _selectedFirmware.value == index && current.fileUri == null && !current.isInProgress
    }

    /**
     * Copies [firmware] from the assets into the cache under its own file name and returns its
     * FileProvider URI and size. The copy is written to a temporary file and renamed over the
     * previous one, so the URI never leads to a half-written zip. Blocking: call on Dispatchers.IO.
     */
    private fun copyBundledFirmware(firmware: BundledFirmware): Pair<Uri, Long> {
        val context = getApplication<Application>()
        val cacheFile = java.io.File(context.cacheDir, firmware.fileName)
        val partFile = java.io.File.createTempFile("bundled-", ".part", context.cacheDir)
        try {
            context.assets.open(firmware.file).use { input ->
                partFile.outputStream().use { output -> input.copyTo(output) }
            }
            if (partFile.length() == 0L) {
                throw IllegalStateException("Failed to copy firmware file - file is empty")
            }
            if (!partFile.renameTo(cacheFile)) {
                throw IllegalStateException("Failed to replace ${cacheFile.name} in the cache")
            }
        } finally {
            partFile.delete() // Already gone once renamed
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", cacheFile)
        return uri to cacheFile.length()
    }

    fun useEmbeddedFirmware() {
        if (_bundledFirmware.value.isEmpty()) {
            _state.value = _state.value.copy(error = NO_FIRMWARE_ERROR, status = "No firmware bundled")
            return
        }
        selectBundledFirmware(_selectedFirmware.value ?: 0)
    }

    fun startDfu() {
        viewModelScope.launch {
            try {
                // Validate device address
                val settings = settingsRepo.settings.first()
                val deviceAddress = settings.primaryDeviceAddress
                if (deviceAddress.isNullOrBlank()) {
                    _state.value = _state.value.copy(
                        error = "No device associated. Please pair a device first.",
                        status = "Error: No device"
                    )
                    return@launch
                }

                // Validate file URI
                val fileUri = _state.value.fileUri
                if (fileUri == null) {
                    _state.value = _state.value.copy(
                        error = "No firmware file selected",
                        status = "Error: No file"
                    )
                    return@launch
                }

                // REQ-043 — block manifests below DfuVersionGuard.MIN_DFU_VERSION.
                // Older firmware lacks the protocol features (HELLO diagnostics,
                // CONFIG_PUSH_ACK, MTU negotiation) the bridge phone app depends
                // on; flashing it would brick the bridge until manual re-flash.
                val manifestBytes = readManifestBytes(fileUri)
                if (manifestBytes != null) {
                    val check = DfuVersionGuard.checkManifest(manifestBytes)
                    if (check is DfuVersionGuard.VersionCheckResult.TooOld) {
                        _state.value = _state.value.copy(
                            error = "Firmware ${check.version} is older than the minimum supported ${check.floor}. " +
                                "Pick a newer DFU zip or use the bundled firmware.",
                            status = "Rejected: firmware too old (MIN_DFU_VERSION=${DfuVersionGuard.MIN_DFU_VERSION})"
                        )
                        return@launch
                    }
                }
                // Unparseable manifest is allowed through (proceed with caller-supplied
                // file) — the legacy upload path that predates manifest.json should not
                // be blocked. The version-floor check is best-effort, not load-bearing.

                loggedDecile = -1
                stalledAtPercent = null
                dfuPastUpload = false
                dfuController = null
                dfuLog(
                    "start",
                    "file ${fileUri.lastPathSegment}, manifest " +
                        (manifestBytes?.let { DfuVersionGuard.checkManifest(it).toString() } ?: "none") +
                        ", connectedMode ${settings.connectedModeEnabled}",
                )

                _state.value = _state.value.copy(
                    deviceAddress = deviceAddress,
                    isInProgress = true,
                    error = null,
                    status = "Preparing DFU...",
                    progress = 0
                )

                // Check if Connected Mode is enabled
                if (settings.connectedModeEnabled) {
                    _state.value = _state.value.copy(status = "Disabling Connected Mode...")

                    // Disable Connected Mode; restoreConnectedModeAfterDfu turns it back on
                    settingsRepo.setConnectedModeEnabled(false)
                    restoreConnectedMode = true

                    // Explicitly stop the bridge engine
                    engine.stop()

                    // Wait for BLE to disconnect
                    _state.value = _state.value.copy(status = "Waiting for disconnect...")
                    var attempts = 0
                    val maxAttempts = 60 // Increased to 60 seconds
                    while (attempts < maxAttempts) {
                        val bridgeStatus = engine.status.first()
                        if (bridgeStatus.bleState is BleConnectionState.Disconnected) {
                            break
                        }
                        delay(1000)
                        attempts++

                        // Log progress every 5 seconds
                        if (attempts % 5 == 0) {
                            _state.value = _state.value.copy(
                                status = "Waiting for disconnect... (${attempts}s / ${maxAttempts}s)"
                            )
                        }
                    }

                    // Check if we timed out
                    val finalStatus = engine.status.first()
                    if (finalStatus.bleState !is BleConnectionState.Disconnected) {
                        _state.value = _state.value.copy(
                            isInProgress = false,
                            error = "Timeout waiting for BLE disconnect. Please manually disable Connected Mode and wait 10 seconds before trying DFU again.",
                            status = "Disconnect timeout"
                        )
                        return@launch
                    }

                    // Give it a bit more time to fully release
                    delay(2000)
                }

                // Wait for watch bootloader to initialize
                // The watch needs time to reboot into DFU bootloader mode and start advertising
                _state.value = _state.value.copy(status = "Waiting for bootloader...")
                delay(8000) // Total 10 seconds after disconnect (2s + 8s)

                // Start Nordic DFU
                _state.value = _state.value.copy(status = "Initializing DFU...")

                // Create DFU notification channel (required for Android 8+)
                DfuServiceInitiator.createDfuNotificationChannel(getApplication())

                val initiator = DfuServiceInitiator(deviceAddress)
                    .setDeviceName("PineTime")
                    // Keep the phone's bond: the watch keeps its own across an update (it lives
                    // in littlefs), and ConnectionManager refuses an unbonded watch. With false
                    // the library removes the bond when the update completes, which left the
                    // phone unable to reconnect after the 2.4.75 update until the user paired
                    // again. (false also clears Android's GATT cache; these firmwares all expose
                    // the same services, so there is nothing stale to clear.)
                    .setKeepBond(true)
                    .setForceDfu(false)

                    // CRITICAL for Legacy DFU bootloader 0.7:
                    // Force MTU=23 (20-byte payload) to prevent oversized writes
                    // that would crash the bootloader's fixed buffer
                    .setMtu(23)
                    .disableMtuRequest()

                    // A packet receipt notification every 10 packets, not every packet. Each one
                    // costs a round trip, so 1 held transfers to about 0.3 kB/s (25 minutes for a
                    // 450 KB image), and a lost notification could leave the library waiting for
                    // good: 2.4.84's update sat at 84 % for 30 minutes. Upstream InfiniTime's
                    // ota-dfu tool uses 10, and up to 12 packets in flight fit the watch's 12-block
                    // NimBLE mbuf pool. Recovery firmware included, the packets stay 20 B (above).
                    .setPacketsReceiptNotificationsEnabled(true)
                    .setPacketsReceiptNotificationsValue(10)
                    .setPrepareDataObjectDelay(300) // 300ms is sufficient with 20-byte writes
                    .setNumberOfRetries(3)
                    .setUnsafeExperimentalButtonlessServiceInSecureDfuEnabled(true)
                    .setZip(fileUri)

                dfuController = initiator.start(getApplication(), DfuService::class.java)

            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isInProgress = false,
                    error = e.message ?: "Unknown error",
                    status = "Error: ${e.message}"
                )
            }
        }
    }

    fun cancelDfu() {
        if (_state.value.isInProgress) {
            val controller = dfuController
            if (controller != null) {
                controller.abort()
                return
            }
            // Not started yet: the library has nothing to abort.
            _state.value = _state.value.copy(
                status = "Cancel DFU from notification",
                error = "Use the notification to cancel the update"
            )
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun reset() {
        if (!_state.value.isInProgress) {
            _state.value = DfuState()
            // A bundled package stays picked: get it ready again for the next update
            _selectedFirmware.value?.let { selectBundledFirmware(it) }
        }
    }
}
