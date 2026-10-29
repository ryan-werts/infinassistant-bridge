package com.w6tech.infinassistant.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import com.w6tech.infinassistant.Routes
import com.w6tech.infinassistant.core.ble.BleConnectionState
import com.w6tech.infinassistant.core.data.BridgeStatus
import com.w6tech.infinassistant.ui.BridgeViewModel
import com.w6tech.infinassistant.ui.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    nav: NavHostController,
    settingsVm: SettingsViewModel = hiltViewModel(),
    bridgeVm: BridgeViewModel = hiltViewModel(),
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val settings by settingsVm.settings.collectAsState()
    val log by bridgeVm.protocolLog.collectAsState()
    val status by bridgeVm.status.collectAsState()
    val debugUpload by bridgeVm.debugUpload.collectAsState()

    var eventType by remember(settings.notificationEventType) { mutableStateOf(settings.notificationEventType) }
    var sharePath by remember(settings.debugSharePath) { mutableStateOf(settings.debugSharePath) }
    var shareUser by remember(settings.debugShareUsername) { mutableStateOf(settings.debugShareUsername) }
    var sharePass by remember(settings.debugSharePassword) { mutableStateOf(settings.debugSharePassword) }

    Scaffold(topBar = { TopAppBar(title = { Text("Protocol / Debug") }) }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            item {
                OutlinedButton(onClick = { nav.navigate(Routes.Health) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Bridge health: reconnects, phone sleep, watch counters")
                }
                Spacer(Modifier.height(12.dp))

                // --- Watch diagnostics (REQ-066) ---
                HealthScreen(status = status, onRefresh = bridgeVm::refreshDiagnostics)
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                // --- Diagnostic write-type toggle (REQ-033 / REQ-066) ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Acknowledged write mode", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Slower WATCHFACE_LAYOUT writes surface per-chunk BLE errors",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = status.diagnosticWriteTypeEnabled,
                        onCheckedChange = { bridgeVm.setDiagnosticWriteType(it) },
                    )
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                Text("Status: HA=${status.haState} BLE=${status.bleState}")
                if (status.lastError != null) Text("Last error: ${status.lastError}")

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = eventType,
                    onValueChange = { eventType = it },
                    label = { Text("HA notify event type") },
                    supportingText = { Text("HA automations fire this custom event; app listens via websocket") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = { settingsVm.setNotificationEventType(eventType.trim()) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save event type") }

                Spacer(Modifier.height(12.dp))

                Button(onClick = bridgeVm::forceConfigPush, modifier = Modifier.fillMaxWidth()) { Text("Force config push") }
                Spacer(Modifier.height(8.dp))
                Button(onClick = bridgeVm::sendTestNotification, modifier = Modifier.fillMaxWidth()) { Text("Send test notification") }
                Spacer(Modifier.height(8.dp))
                Button(onClick = bridgeVm::forceResync, modifier = Modifier.fillMaxWidth()) { Text("Force resync states") }
                Spacer(Modifier.height(8.dp))
                Button(onClick = bridgeVm::pingWatch, modifier = Modifier.fillMaxWidth()) { Text("Ping watch") }
                Spacer(Modifier.height(8.dp))
                Button(onClick = bridgeVm::findWatch, modifier = Modifier.fillMaxWidth()) { Text("Find watch") }

                Spacer(Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            bridgeVm.exportLogs { file ->
                                if (file != null) {
                                    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/octet-stream"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    ctx.startActivity(Intent.createChooser(shareIntent, "Share diagnostic logs"))
                                } else {
                                    Toast.makeText(ctx, "No logs to export", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Export logs") }

                    OutlinedButton(
                        onClick = {
                            bridgeVm.clearLogs()
                            Toast.makeText(ctx, "Logs cleared", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Clear logs") }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                // --- Debug bundle → SMB share ---
                Text("Debug bundle → SMB share", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Collects watch telemetry + protocol log + the full diagnostic log and uploads it to a network share (anonymous SMB) for the developer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = sharePath,
                    onValueChange = { sharePath = it },
                    label = { Text("SMB share (UNC)") },
                    supportingText = { Text("e.g. \\\\host\\share") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = shareUser,
                        onValueChange = { shareUser = it },
                        label = { Text("Username (optional)") },
                        supportingText = { Text("blank = anonymous") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = sharePass,
                        onValueChange = { sharePass = it },
                        label = { Text("Password (optional)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            settingsVm.setDebugSharePath(sharePath.trim())
                            settingsVm.setDebugShareCredentials(shareUser.trim(), sharePass)
                            Toast.makeText(ctx, "Share settings saved", Toast.LENGTH_SHORT).show()
                        },
                        enabled = !debugUpload.uploading,
                        modifier = Modifier.weight(1f),
                    ) { Text("Save") }
                    OutlinedButton(
                        onClick = { bridgeVm.testDebugShare(sharePath.trim(), shareUser.trim(), sharePass) },
                        enabled = !debugUpload.uploading,
                        modifier = Modifier.weight(1f),
                    ) { Text("Test share") }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        settingsVm.setDebugSharePath(sharePath.trim())
                        settingsVm.setDebugShareCredentials(shareUser.trim(), sharePass)
                        bridgeVm.uploadDebugBundle(sharePath.trim(), shareUser.trim(), sharePass)
                    },
                    enabled = !debugUpload.uploading,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (debugUpload.uploading) "Uploading…" else "Upload debug bundle to share") }
                if (debugUpload.lastMessage != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        debugUpload.lastMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (debugUpload.lastSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Tip: tap \"Send Face\" first, then immediately Upload debug bundle so the failed push is captured.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(16.dp))
                Text("Last ${log.size} protocol entries")
                Spacer(Modifier.height(8.dp))
            }

            items(log) { e ->
                Text("${e.ts} ${e.direction} ${e.type} ${e.msgId ?: ""} ${e.summary}")
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/**
 * REQ-066 — watch diagnostic summary card. Surfaces fields from the most recent watch HELLO:
 *   - Firmware version (REQ-056 HELLO diag rev 2)
 *   - Last reset reason
 *   - Crash breadcrumb code
 *   - Free heap / min free heap
 *   - HELLO diagnostics revision
 *   - Negotiated MTU with <64 warning (REQ-062 deferred surface)
 *   - CONFIG_ACK status (REQ-035)
 *   - Acknowledged write mode flag (REQ-033)
 *
 * The "Refresh diagnostics" button sends a phone HELLO to solicit a fresh watch HELLO response.
 */
@Composable
fun HealthScreen(
    status: BridgeStatus,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val negotiatedMtu = (status.bleState as? BleConnectionState.Connected)?.mtu

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Watch diagnostics", style = MaterialTheme.typography.titleSmall)
            OutlinedButton(onClick = onRefresh) { Text("Refresh") }
        }

        Spacer(Modifier.height(4.dp))

        DiagRow("Firmware", status.watchFirmwareVersion ?: "—")
        DiagRow("HELLO diag rev", status.lastHelloDiagRev?.toString() ?: "—")
        DiagRow("Layout pushes refused", status.lastLayoutRefused?.toString() ?: "—")
        DiagRow("Layout writes failed", status.lastLayoutWriteFailed?.toString() ?: "—")
        DiagRow("Reset reason", status.lastResetReason ?: "—")
        DiagRow("Crash breadcrumb", status.lastCrashBreadcrumb?.let { "$it" } ?: "—")
        DiagRow("Crash context", status.lastCrashContext ?: "—")
        DiagRow("Last activity before restart", status.lastRestartActivity ?: "—")
        // Only meaningful on 2.4.41+; older firmware never captured fault registers,
        // which is why the breadcrumb alone always read as empty.
        DiagRow("Crash fault", status.lastCrashFault ?: "—")
        // Verbatim from HA, so a failed layout fetch can be diagnosed from a
        // bundle instead of from the user's recollection of a transient screen.
        DiagRow("Watch face fetch", status.lastWatchFaceFetchError ?: "ok / not tried")
        DiagRow("Free heap", status.lastFreeHeap?.let { "${it} B" } ?: "—")
        DiagRow("Min free heap", status.lastMinFreeHeap?.let { "${it} B" } ?: "—")

        // Doze is the likeliest cause of a watch that silently goes stale for
        // hours: the bridge stops getting scheduled, so nothing reconnects and
        // nothing warns. isIgnoringBatteryOptimizations() was previously read in
        // exactly one place — BatteryOptimizationScreen — so unless you happened
        // to open that screen there was no way to tell whether the exemption was
        // granted. Surface it next to the rest of the diagnostics.
        val healthCtx = androidx.compose.ui.platform.LocalContext.current
        val ignoringDoze = remember(healthCtx) {
            (healthCtx.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager)
                ?.isIgnoringBatteryOptimizations(healthCtx.packageName)
        }
        DiagRow(
            "Battery optimization",
            when (ignoringDoze) {
                true -> "exempt — OK"
                false -> "ACTIVE  ⚠ can suspend the bridge"
                null -> "—"
            },
        )

        // REQ-062 deferred surface: <64 MTU warning
        val mtuLabel = when {
            negotiatedMtu == null -> "— (not connected)"
            negotiatedMtu < 64 -> "$negotiatedMtu  ⚠ <64 — throughput limited"
            else -> "$negotiatedMtu"
        }
        DiagRow("Negotiated MTU", mtuLabel)

        // REQ-035: CONFIG_ACK status
        DiagRow(
            "Last CONFIG_ACK",
            when (status.lastConfigAckOk) {
                true -> "ok"
                false -> "TIMEOUT / failed"
                null -> "—"
            },
        )

        // REQ-033: write type diagnostic
        DiagRow(
            "Acknowledged write mode",
            if (status.diagnosticWriteTypeEnabled) "on" else "off",
        )

        status.lastSyncTime?.let { ts ->
            val ago = (System.currentTimeMillis() - ts) / 1000
            DiagRow("Last sync", "${ago}s ago")
        }
    }
}

@Composable
internal fun DiagRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
