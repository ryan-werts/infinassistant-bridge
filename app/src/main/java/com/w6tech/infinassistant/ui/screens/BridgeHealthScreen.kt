package com.w6tech.infinassistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import com.w6tech.infinassistant.core.ble.BleConnectionState
import com.w6tech.infinassistant.core.data.HealthSummary
import com.w6tech.infinassistant.core.ha.HaConnectionState
import com.w6tech.infinassistant.ui.BridgeViewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How often the screen reads the diagnostic log again while it is open. */
private const val SUMMARY_REFRESH_MS = 30_000L

/**
 * The bridge's health at a glance: the links now and how long the session has run, then, from the
 * diagnostic log, the reconnects, the phone's deep sleep, the dashboard's last state, the watch's
 * counters and the last errors. The same numbers as a debug bundle's HEALTH SUMMARY
 * ([HealthSummary]); the watch counters come live from the last SENSOR_REPORT when there is one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BridgeHealthScreen(
    nav: NavHostController,
    bridgeVm: BridgeViewModel = hiltViewModel(),
) {
    val status by bridgeVm.status.collectAsState()
    val summary by bridgeVm.healthSummary.collectAsState()
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            bridgeVm.refreshHealthSummary()
            delay(SUMMARY_REFRESH_MS)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            nowMs = System.currentTimeMillis()
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Bridge health") }) }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Now", style = MaterialTheme.typography.titleSmall)
                    OutlinedButton(onClick = bridgeVm::refreshHealthSummary) { Text("Refresh") }
                }
                DiagRow("Home Assistant", haLabel(status.haState))
                DiagRow("Watch (BLE)", bleLabel(status.bleState))
                DiagRow("Session up", status.sessionStartedAtMs?.let { duration(nowMs - it) } ?: "not running")
                if (status.watchUpdateNeeded) {
                    DiagRow("Watch firmware", "update needed: it predates the renamed config key, so no settings are sent")
                }
                status.lastError?.let { DiagRow("Last error", it) }

                val s = summary
                if (s == null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Reading the diagnostic log…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Section("Reconnects")
                    DiagRow("HA lost / reconnected", "${s.haLost} / ${s.haReconnected}")
                    s.haLongestOutageSec?.let { DiagRow("Longest HA outage", duration(it * 1000)) }
                    s.haLostByCause.take(3).forEach { (cause, n) -> DiagRow("  $n×", cause) }
                    DiagRow("HA failed connects", "${s.haConnectFailures}")
                    DiagRow("Watch link lost", "${s.bleLinkLost}")
                    DiagRow(
                        "Watch connected",
                        "${s.bleConnectsDirect + s.bleConnectsBackground} (direct ${s.bleConnectsDirect}, background ${s.bleConnectsBackground})",
                    )
                    DiagRow("Failed watch connects", "${s.bleFailedAttempts}")
                    s.bleLongestWaitSec?.let { DiagRow("Longest background wait", duration(it * 1000)) }

                    Section("Phone sleep")
                    DiagRow("Gaps of 60 s or more", "${s.sleepGaps}")
                    s.longestSleepSec?.let { DiagRow("Longest", duration(it * 1000)) }
                    DiagRow("Asleep in all", duration(s.totalSleepSec * 1000))

                    Section("Dashboard")
                    DiagRow("Last DASHBOARD_STATE sent", s.lastDashboardSendMs?.let { ago(nowMs, it) } ?: "none in the log")
                    if (s.dashboardSendsByReason.isNotEmpty()) {
                        DiagRow(
                            "Sent (${s.dashboardSends})",
                            s.dashboardSendsByReason.joinToString(", ") { (reason, n) -> "$reason $n" },
                        )
                    }
                }

                Section("Watch")
                val report = status.lastSensorReport
                val counters = summary?.watchCounters.orEmpty()
                val reportAt = if (report != null) status.lastSyncTime else summary?.lastSensorReportMs
                DiagRow("Last SENSOR_REPORT", reportAt?.let { ago(nowMs, it) } ?: "none yet")
                DiagRow("Free heap", (report?.freeHeap ?: counters["heap"])?.let { "$it B" } ?: "—")
                DiagRow("Min free heap", (report?.minFreeHeap ?: counters["minHeap"])?.let { "$it B" } ?: "—")
                DiagRow("Stack high-water", (report?.stackHighWater?.toLong() ?: counters["stackHW"])?.let { "$it words" } ?: "—")
                DiagRow("DASHBOARD_STATE decoded", (report?.dashStateOk?.toLong() ?: counters["dsOk"])?.toString() ?: "—")
                DiagRow("DASHBOARD_STATE refused", (report?.dashStateRefused?.toLong() ?: counters["dsBad"])?.toString() ?: "—")
                DiagRow("Fingerprint mismatches", (report?.fingerprintMisses?.toLong() ?: counters["fpMis"])?.toString() ?: "—")
                DiagRow(
                    "Watch's last state",
                    (report?.dashStateAgeSec ?: counters["dsAge"])?.let { age ->
                        val decoded = report?.dashStateOk?.toLong() ?: counters["dsOk"]
                        if (age == 0L && decoded == 0L) "none since the watch booted" else "${duration(age * 1000)} before the report"
                    } ?: "—",
                )

                Section("Last errors")
                if (summary?.lastErrors.isNullOrEmpty()) {
                    Text("none", style = MaterialTheme.typography.bodySmall)
                }
            }

            items(summary?.lastErrors.orEmpty().reversed()) { e ->
                Text(
                    "${clock(e.ts)} ${e.tag}: ${e.msg}${e.extra?.let { " ($it)" } ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }

            item {
                summary?.let { s ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "From the diagnostic log: ${s.entries} entries" +
                            (s.fromMs?.let { " since ${clock(it)}" } ?: "") + ", ${s.sessions} sessions started.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(12.dp))
    HorizontalDivider()
    Spacer(Modifier.height(8.dp))
    Text(title, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
}

private fun haLabel(state: HaConnectionState): String = when (state) {
    is HaConnectionState.Failed -> "Failed: ${state.error.message ?: state.error.javaClass.simpleName}"
    else -> state.toString()
}

private fun bleLabel(state: BleConnectionState): String = when (state) {
    is BleConnectionState.Connected -> "Connected (MTU ${state.mtu})"
    is BleConnectionState.Failed -> "Failed: ${state.error.message ?: state.error.javaClass.simpleName}"
    else -> state.toString()
}

private fun duration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "$s s"
        s < 3600 -> "${s / 60} min ${s % 60} s"
        s < 86_400 -> "${s / 3600} h ${(s % 3600) / 60} min"
        else -> "${s / 86_400} d ${(s % 86_400) / 3600} h"
    }
}

private fun ago(nowMs: Long, thenMs: Long): String = "${duration(nowMs - thenMs)} ago (${clock(thenMs)})"

private fun clock(ms: Long): String = SimpleDateFormat("MMM d HH:mm:ss", Locale.getDefault()).format(Date(ms))
