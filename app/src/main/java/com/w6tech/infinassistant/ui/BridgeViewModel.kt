package com.w6tech.infinassistant.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.w6tech.infinassistant.core.data.BridgeEngine
import com.w6tech.infinassistant.core.data.HaApi
import com.w6tech.infinassistant.core.common.AppResult
import android.content.Context
import com.w6tech.infinassistant.core.data.DiagnosticLogger
import com.w6tech.infinassistant.core.data.HealthSummary
import com.w6tech.infinassistant.ui.debug.DebugBundleBuilder
import com.w6tech.infinassistant.ui.debug.createDebugBundleUploader
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** UI state for the debug-bundle SMB upload (Debug screen). */
data class DebugUploadUi(
    val uploading: Boolean = false,
    val lastMessage: String? = null,
    val lastSuccess: Boolean = false,
)

@HiltViewModel
class BridgeViewModel @Inject constructor(
    private val engine: BridgeEngine,
    private val haApi: HaApi,
    @ApplicationContext private val appContext: Context,
    private val diagnosticLogger: DiagnosticLogger,
) : ViewModel() {

    val status = engine.status
    val protocolLog = engine.protocolLog

    /** Whether the watch is paired and its link encrypted: the gate for forwarding phone notifications. */
    val watchLink = engine.watchLink

    /** Starts Android's bonding with the watch; [onResult] gets false when it could not start. */
    fun pairWithWatch(onResult: (Boolean) -> Unit) {
        viewModelScope.launch { onResult(engine.pairWithWatch()) }
    }

    private val smbUploader = createDebugBundleUploader()
    private val _debugUpload = MutableStateFlow(DebugUploadUi())
    val debugUpload: StateFlow<DebugUploadUi> = _debugUpload

    private val _haTest = MutableStateFlow<AppResult<Unit>?>(null)
    val haTest: StateFlow<AppResult<Unit>?> = _haTest

    /** The Health screen's numbers, from the diagnostic log (the bundle's HEALTH SUMMARY, live). */
    private val _healthSummary = MutableStateFlow<HealthSummary?>(null)
    val healthSummary: StateFlow<HealthSummary?> = _healthSummary

    /** Reads the diagnostic log again for [healthSummary], off the main thread. */
    fun refreshHealthSummary() {
        viewModelScope.launch {
            _healthSummary.value = withContext(Dispatchers.IO) {
                try {
                    engine.flushDiagnostics()
                    HealthSummary.compute(diagnosticLogger.readEntries())
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    fun testHaConnection() {
        viewModelScope.launch {
            _haTest.value = haApi.testConnection()
        }
    }

    fun sendTestNotification() = engine.sendTestNotification()
    fun forceResync() = engine.forceResyncStates()
    fun pingWatch() = engine.pingWatch()
    fun findWatch() = engine.findWatch()
    fun forceConfigPush() = engine.forceConfigPush()

    /**
     * Exports the diagnostic logs to a temporary file for sharing, handed to [onReady] on the main
     * thread (null if there are no logs). Read on an IO thread: the export first waits for the
     * lines still queued, up to 2 s, then copies the files.
     */
    fun exportLogs(onReady: (java.io.File?) -> Unit) {
        viewModelScope.launch {
            onReady(withContext(Dispatchers.IO) { engine.exportLogs() })
        }
    }

    /** Clear all diagnostic logs. */
    fun clearLogs() = engine.clearLogs()

    /**
     * REQ-033 / REQ-066 — toggle acknowledged-write diagnostic mode on the active BLE
     * transport. Reflected in [status].diagnosticWriteTypeEnabled.
     */
    fun setDiagnosticWriteType(enabled: Boolean) = engine.setDiagnosticWriteType(enabled)

    /**
     * REQ-066 — send a phone HELLO to trigger a fresh watch HELLO diagnostic response,
     * updating firmware version, reset reason, heap, and crash context in [status].
     */
    fun refreshDiagnostics() = engine.refreshDiagnostics()

    /**
     * Collect a comprehensive diagnostic bundle (watch telemetry + protocol log +
     * full diagnostic JSONL + versions/limits) and upload it to the configured SMB
     * share anonymously. Refreshes watch diagnostics first so the snapshot is fresh.
     */
    fun uploadDebugBundle(sharePath: String, username: String = "", password: String = "") {
        if (sharePath.isBlank()) {
            _debugUpload.value = DebugUploadUi(false, "SMB upload not configured", false)
            return
        }
        viewModelScope.launch {
            _debugUpload.value = DebugUploadUi(uploading = true, lastMessage = "Collecting + uploading…")
            try { engine.refreshDiagnostics() } catch (_: Exception) {}
            delay(1200)  // let a fresh watch HELLO land in [status]
            val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            // Off the main thread: it waits for the log's last writes, then reads and parses it.
            val bundle = withContext(Dispatchers.IO) {
                engine.flushDiagnostics()
                DebugBundleBuilder.build(
                    context = appContext,
                    status = status.value,
                    protocolLog = protocolLog.value,
                    lastWatchFaceLayout = engine.lastWatchFaceLayout(),
                    debugSharePath = sharePath,
                    diagnosticLogger = diagnosticLogger,
                )
            }
            val result = smbUploader.upload(sharePath, "infinassistant-debug-$ts.txt", bundle, username, password)
            _debugUpload.value = result.fold(
                onSuccess = { DebugUploadUi(false, "Uploaded: $it", true) },
                onFailure = { DebugUploadUi(false, "Upload failed: ${it.message}", false) },
            )
        }
    }

    /** Upload a tiny test file to validate the SMB share before sending a real bundle. */
    fun testDebugShare(sharePath: String, username: String = "", password: String = "") {
        if (sharePath.isBlank()) {
            _debugUpload.value = DebugUploadUi(false, "SMB upload not configured", false)
            return
        }
        viewModelScope.launch {
            _debugUpload.value = DebugUploadUi(uploading = true, lastMessage = "Testing share…")
            val payload = "infinassistant SMB share connection test ${java.util.Date()}\n".toByteArray()
            val result = smbUploader.upload(sharePath, "infinassistant-debug-test.txt", payload, username, password)
            _debugUpload.value = result.fold(
                onSuccess = { DebugUploadUi(false, "Share OK: $it", true) },
                onFailure = { DebugUploadUi(false, "Share test failed: ${it.message}", false) },
            )
        }
    }
}
