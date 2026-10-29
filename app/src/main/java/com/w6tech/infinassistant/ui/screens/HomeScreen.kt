package com.w6tech.infinassistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import com.w6tech.infinassistant.Routes
import com.w6tech.infinassistant.BuildConfig
import com.w6tech.infinassistant.core.ble.BleConnectionState
import com.w6tech.infinassistant.core.common.LocalNetwork
import com.w6tech.infinassistant.core.data.BridgeStatus
import com.w6tech.infinassistant.core.data.appearance.isEffectivelyDark
import com.w6tech.infinassistant.core.ha.HaConnectionState
import com.w6tech.infinassistant.core.ui.SettingRow
import com.w6tech.infinassistant.core.ui.theme.AppColors
import com.w6tech.infinassistant.core.ui.theme.connectionStatusRole
import com.w6tech.infinassistant.ui.AppearanceViewModel
import com.w6tech.infinassistant.ui.BridgeViewModel
import com.w6tech.infinassistant.ui.DfuViewModel
import com.w6tech.infinassistant.ui.SettingsViewModel
import com.w6tech.infinassistant.ui.appearanceSubtitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    nav: NavHostController,
    vm: SettingsViewModel = hiltViewModel(),
    bridgeVm: BridgeViewModel = hiltViewModel(),
    dfuVm: DfuViewModel = hiltViewModel(),
    appearanceVm: AppearanceViewModel = hiltViewModel(),
) {
    val storedThemeMode by appearanceVm.mode.collectAsState()
    var showAppearanceDialog by rememberSaveable { mutableStateOf(false) }
    val settings by vm.settings.collectAsState()
    val status by bridgeVm.status.collectAsState()
    val embeddedFwVersion by dfuVm.embeddedFirmwareVersion.collectAsState()

    // Auto-navigate to onboarding on first launch (no HA token configured)
    val haConfigured = (settings.ha.localUrl.isNotBlank() || settings.ha.remoteUrl.isNotBlank()) && settings.ha.hasToken
    LaunchedEffect(haConfigured) {
        if (!haConfigured) {
            nav.navigate(Routes.Onboarding)
        }
    }

    if (showAppearanceDialog) {
        AppearanceDialog(
            selected = storedThemeMode,
            onSelect = appearanceVm::setMode,
            onDismiss = { showAppearanceDialog = false },
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("InfinAssistant Bridge") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Watch status card (only when connected mode is enabled)
            if (settings.connectedModeEnabled) {
                WatchStatusCard(status = status, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                // The watch's firmware predates the renamed config key: nothing syncs until it is updated.
                if (status.watchUpdateNeeded) {
                    UpdateWatchCard(
                        bundledVersion = embeddedFwVersion,
                        onClick = { nav.navigate(Routes.Dfu) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            // The saved local URL can never be used (plain http to a non-local address): shown for as long as it is saved.
            LocalNetwork.plainHttpProblem(settings.ha.localUrl)?.let { problem ->
                LocalUrlCard(
                    problem = problem,
                    onClick = { nav.navigate(Routes.Onboarding) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            SettingRow(
                title = "Onboarding / Home Assistant",
                subtitle = when {
                    !settings.ha.hasToken -> "Not configured"
                    settings.ha.localUrl.isNotBlank() && settings.ha.remoteUrl.isNotBlank() ->
                        "Local + Remote configured"
                    settings.ha.localUrl.isNotBlank() -> "Local: ${settings.ha.localUrl}"
                    settings.ha.remoteUrl.isNotBlank() -> "Remote: ${settings.ha.remoteUrl}"
                    else -> "Not configured"
                },
                onClick = { nav.navigate(Routes.Onboarding) },
            )
            SettingRow(
                title = "Device pairing",
                subtitle = when {
                    settings.associatedDevices.size > 1 -> "${settings.associatedDevices.size} watches paired"
                    settings.associatedDevices.size == 1 -> settings.associatedDevices[0].let { d ->
                        d.name.ifBlank { d.address }
                    }
                    settings.associatedDeviceAddress != null -> settings.associatedDeviceAddress!!
                    else -> "Not paired"
                },
                onClick = { nav.navigate(Routes.Pairing) },
            )
            SettingRow(
                title = "Connected Mode",
                subtitle = if (settings.connectedModeEnabled) "ON" else "OFF",
                onClick = { nav.navigate(Routes.ConnectedMode) },
            )
            SettingRow(
                title = "Notifications",
                subtitle = if (settings.notificationPrefs.deliverToWatch) "Deliver to watch" else "Disabled",
                onClick = { nav.navigate(Routes.Notifications) },
            )
            SettingRow(
                title = "InfinAssistant (HA Sensors)",
                subtitle = if (settings.infinassistant.enabled) "Enabled" else "Disabled",
                onClick = { nav.navigate(Routes.InfinAssistant) },
            )
            SettingRow(
                title = "Import Watch Face",
                subtitle = "Configure custom watch face layout",
                onClick = { nav.navigate(Routes.WatchFaceImport) },
            )
            SettingRow(
                title = "Firmware Update",
                subtitle = "Update PineTime firmware via DFU",
                onClick = { nav.navigate(Routes.Dfu) },
            )
            SettingRow(
                title = "Battery Optimization",
                subtitle = "Background reliability settings",
                onClick = { nav.navigate(Routes.BatteryOptimization) },
            )
            SettingRow(
                title = "Appearance",
                subtitle = storedThemeMode?.let { mode ->
                    appearanceSubtitle(mode, isEffectivelyDark(mode, isSystemInDarkTheme()))
                },
                onClick = { showAppearanceDialog = true },
            )
            SettingRow(
                title = "Bridge health",
                subtitle = "Reconnects, phone sleep, watch counters, last errors",
                onClick = { nav.navigate(Routes.Health) },
            )
            SettingRow(
                title = "Protocol / Debug",
                subtitle = "Last protocol messages + manual tools",
                onClick = { nav.navigate(Routes.Debug) },
            )

            Text(
                "Tip: Turn on Connected Mode to start the foreground service and keep BLE + HA WebSocket alive.",
                modifier = Modifier.padding(16.dp)
            )

            Spacer(modifier = Modifier.weight(1f))

            val fwLabel = embeddedFwVersion?.let { " • FW v$it" } ?: ""
            Text(
                text = "App v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})$fwLabel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

/**
 * "Update the watch": the watch's HELLO has no CONFIG_KEY_V2 bit, so the app sends it no CONFIG_PUSH
 * and its sensor reports stay off. The card names the firmware bundled with this app and opens the
 * firmware update screen, where the first bundled package is already picked (even when it is the
 * only one), so it opens on a ready Start button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpdateWatchCard(bundledVersion: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Update the watch", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            val firmware = bundledVersion?.let { "firmware $it" } ?: "the firmware"
            Text(
                "The watch runs firmware from before this app's renamed settings, so it gets no settings " +
                    "and sends no sensor reports. Tap to install $firmware from this app.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** The saved local URL is plain http to an address that is not local: it is never used. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalUrlCard(problem: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Fix the local URL", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("$problem Tap to edit it.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun WatchStatusCard(status: BridgeStatus, modifier: Modifier = Modifier) {
    val bleConnected = status.bleState is BleConnectionState.Connected
    val haConnected = status.haState is HaConnectionState.Connected

    // Status dot color: success=all good, warning=connected but unresponsive, danger=disconnected
    val dotColor = AppColors.status(
        connectionStatusRole(
            bleConnected = bleConnected,
            haConnected = haConnected,
            watchResponsive = status.watchResponsive,
        ),
    ).foreground
    val statusLabel = when {
        bleConnected && haConnected && status.watchResponsive -> "Connected"
        bleConnected && !status.watchResponsive -> "Unresponsive"
        status.bleState is BleConnectionState.Connecting -> "Connecting..."
        else -> "Disconnected"
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Battery
                Column {
                    Text("Battery", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val batteryText = when {
                        status.watchBatteryPercent != null -> {
                            val charging = if (status.watchBatteryCharging == true) " (charging)" else ""
                            "${status.watchBatteryPercent}%$charging"
                        }
                        else -> "--"
                    }
                    Text(batteryText, style = MaterialTheme.typography.bodyMedium)
                }
                // Firmware
                Column {
                    Text("Firmware", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(status.watchFirmwareVersion ?: "--", style = MaterialTheme.typography.bodyMedium)
                }
                // Last synced
                Column {
                    Text("Last synced", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val syncText = status.lastSyncTime?.let { ts ->
                        val elapsed = (System.currentTimeMillis() - ts) / 1000
                        when {
                            elapsed < 60 -> "Just now"
                            elapsed < 3600 -> "${elapsed / 60}m ago"
                            else -> "${elapsed / 3600}h ago"
                        }
                    } ?: "--"
                    Text(syncText, style = MaterialTheme.typography.bodyMedium)
                }
            }

            // Connection quality row (RSSI + latency)
            if (bleConnected && (status.rssi != null || status.pingLatencyMs != null)) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text("RSSI", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(status.rssi?.let { "${it} dBm" } ?: "--", style = MaterialTheme.typography.bodyMedium)
                    }
                    Column {
                        Text("Latency", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(status.pingLatencyMs?.let { "${it} ms" } ?: "--", style = MaterialTheme.typography.bodyMedium)
                    }
                    Column {
                        Text("Signal", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val signalText = status.rssi?.let { rssi ->
                            when {
                                rssi >= -60 -> "Excellent"
                                rssi >= -70 -> "Good"
                                rssi >= -80 -> "Fair"
                                else -> "Weak"
                            }
                        } ?: "--"
                        Text(signalText, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
