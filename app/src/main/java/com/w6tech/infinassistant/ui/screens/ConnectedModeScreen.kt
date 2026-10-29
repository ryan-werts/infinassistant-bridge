package com.w6tech.infinassistant.ui.screens

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import com.w6tech.infinassistant.bridge.BridgeForegroundService
import com.w6tech.infinassistant.core.ble.BleConnectionState
import com.w6tech.infinassistant.core.data.BridgeStatus
import com.w6tech.infinassistant.core.domain.BridgeSettings
import com.w6tech.infinassistant.core.ha.HaConnectionState
import com.w6tech.infinassistant.core.ui.theme.AppColors
import com.w6tech.infinassistant.core.ui.theme.connectionStatusRole
import com.w6tech.infinassistant.ui.BridgeViewModel
import com.w6tech.infinassistant.ui.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectedModeScreen(
    nav: NavHostController,
    settingsVm: SettingsViewModel = hiltViewModel(),
    bridgeVm: BridgeViewModel = hiltViewModel(),
) {
    val ctx = LocalContext.current
    val settings by settingsVm.settings.collectAsState()
    val status by bridgeVm.status.collectAsState()

    // Track bond state changes via BroadcastReceiver
    var bondStateVersion by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                    bondStateVersion++
                }
            }
        }

        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            ctx.registerReceiver(receiver, filter)
        }

        onDispose {
            ctx.unregisterReceiver(receiver)
        }
    }

    val permsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { /* UI re-renders */ }
    )

    fun ensurePermissions() {
        permsLauncher.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        )
    }

    val associated = settings.associatedDeviceAddress != null
    @Suppress("UNUSED_EXPRESSION")
    bondStateVersion

    val bonded = settings.associatedDeviceAddress?.let {
        try {
            val bluetoothManager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothManager.adapter?.getRemoteDevice(it)?.bondState == BluetoothDevice.BOND_BONDED
        } catch (_: Throwable) {
            false
        }
    } ?: false

    val haConfigured = (settings.ha.localUrl.isNotBlank() || settings.ha.remoteUrl.isNotBlank()) && settings.ha.hasToken

    // Auto-start service if Connected Mode is already enabled (e.g., after app restart)
    LaunchedEffect(settings.connectedModeEnabled, haConfigured, associated, bonded) {
        if (settings.connectedModeEnabled && haConfigured && associated && bonded) {
            val intent = Intent(ctx, BridgeForegroundService::class.java).apply {
                action = BridgeForegroundService.ACTION_START
            }
            ContextCompat.startForegroundService(ctx, intent)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Connected Mode") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Requires: HA configured, PineTime associated, and bonded (paired).")
            Spacer(modifier = Modifier.height(8.dp))
            Text("HA configured: ${if (haConfigured) "Yes" else "No"}")
            Text("Associated: ${if (associated) "Yes" else "No"}")
            Text("Bonded: ${if (bonded) "Yes" else "No"}")

            Spacer(modifier = Modifier.height(16.dp))

            SwitchRow(
                title = "Connected Mode",
                checked = settings.connectedModeEnabled,
                enabled = haConfigured && associated && bonded,
                onCheckedChange = { enabled ->
                    ensurePermissions()
                    settingsVm.setConnectedModeEnabled(enabled)
                    val intent = Intent(ctx, BridgeForegroundService::class.java).apply {
                        action = if (enabled) BridgeForegroundService.ACTION_START else BridgeForegroundService.ACTION_STOP
                    }
                    if (enabled) {
                        ContextCompat.startForegroundService(ctx, intent)
                    } else {
                        ctx.stopService(Intent(ctx, BridgeForegroundService::class.java))
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { ensurePermissions() }, modifier = Modifier.weight(1f)) {
                    Text("Request permissions")
                }
                OutlinedButton(
                    onClick = {
                        // Toggle connected mode off and on to force reconnect
                        settingsVm.setConnectedModeEnabled(false)
                        ctx.stopService(Intent(ctx, BridgeForegroundService::class.java))
                        settingsVm.setConnectedModeEnabled(true)
                        val intent = Intent(ctx, BridgeForegroundService::class.java).apply {
                            action = BridgeForegroundService.ACTION_START
                        }
                        ContextCompat.startForegroundService(ctx, intent)
                    },
                    modifier = Modifier.weight(1f),
                    enabled = settings.connectedModeEnabled,
                ) {
                    Text("Retry connection")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            StatusBlock(status)

            Spacer(modifier = Modifier.height(16.dp))

            VoiceAssistSection(settings, status)

            Spacer(modifier = Modifier.height(16.dp))

            BatteryOptimizationCard(ctx)
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title)
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun StatusBlock(status: BridgeStatus) {
    val bleConnected = status.bleState is BleConnectionState.Connected
    val haConnected = status.haState is HaConnectionState.Connected

    val dotColor = AppColors.status(
        connectionStatusRole(
            bleConnected = bleConnected,
            haConnected = haConnected,
            watchResponsive = status.watchResponsive,
        ),
    ).foreground

    Card(
        modifier = Modifier.fillMaxWidth(),
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
                Text("Connection Status", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(8.dp))
            Text("HA: ${status.haState}")
            Text("BLE: ${status.bleState}")
            if (!status.watchResponsive) {
                Text("Watch unresponsive (PONG timeout)", color = AppColors.semantic.warning.foreground)
            }
            if (status.lastError != null) {
                Text("Last error: ${status.lastError}", color = MaterialTheme.colorScheme.error)
            }
            status.lastSyncTime?.let { ts ->
                val elapsed = (System.currentTimeMillis() - ts) / 1000
                val text = when {
                    elapsed < 60 -> "Just now"
                    elapsed < 3600 -> "${elapsed / 60}m ago"
                    else -> "${elapsed / 3600}h ago"
                }
                Text("Last synced: $text", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BatteryOptimizationCard(ctx: Context) {
    val oem = Build.MANUFACTURER.lowercase()
    val guidanceUrl = when {
        oem.contains("samsung") -> "https://dontkillmyapp.com/samsung"
        oem.contains("xiaomi") || oem.contains("redmi") || oem.contains("poco") -> "https://dontkillmyapp.com/xiaomi"
        oem.contains("oneplus") -> "https://dontkillmyapp.com/oneplus"
        oem.contains("huawei") || oem.contains("honor") -> "https://dontkillmyapp.com/huawei"
        oem.contains("oppo") || oem.contains("realme") -> "https://dontkillmyapp.com/oppo"
        oem.contains("vivo") -> "https://dontkillmyapp.com/vivo"
        oem.contains("google") || oem.contains("pixel") -> null // Stock Android, usually fine
        else -> "https://dontkillmyapp.com"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Battery Optimization",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "For reliable background operation, disable battery optimization for this app. " +
                    "Some ${Build.MANUFACTURER} devices aggressively kill background services.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${ctx.packageName}")
                    }
                    try { ctx.startActivity(intent) } catch (_: Exception) { }
                }) {
                    Text("Disable optimization")
                }
                if (guidanceUrl != null) {
                    OutlinedButton(onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(guidanceUrl))
                        try { ctx.startActivity(intent) } catch (_: Exception) { }
                    }) {
                        Text("${Build.MANUFACTURER} guide")
                    }
                }
            }
        }
    }
}
