package com.w6tech.infinassistant.ui.screens

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanResult
import android.companion.AssociationRequest
import android.companion.CompanionDeviceManager
import android.companion.BluetoothLeDeviceFilter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Parcelable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import com.w6tech.infinassistant.ui.SettingsViewModel
import java.util.concurrent.Executor
import java.util.regex.Pattern

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(nav: NavHostController, vm: SettingsViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val settings by vm.settings.collectAsState()
    var statusText by remember { mutableStateOf<String?>(null) }

    // Track bond state changes via BroadcastReceiver
    var bondStateVersion by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    val device = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    // Increment version to trigger recomposition
                    bondStateVersion++

                    // Update status text based on bond state
                    when (state) {
                        BluetoothDevice.BOND_BONDING -> statusText = "Bonding in progress..."
                        BluetoothDevice.BOND_BONDED -> statusText = "Successfully bonded with ${device?.address ?: "device"}"
                        BluetoothDevice.BOND_NONE -> {
                            // Only show "Bond removed" if we were previously bonding
                            if (statusText?.contains("Bonding") == true) {
                                statusText = "Bonding failed or cancelled"
                            }
                        }
                    }
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

    // Check if BLUETOOTH_CONNECT permission is needed (Android 12+)
    fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not needed on older Android versions
        }
    }

    // Permission request launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            statusText = "Permission granted. Tap the button again to search."
        } else {
            statusText = "Bluetooth permission denied. Cannot pair devices."
        }
    }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            statusText = "Canceled"
            return@rememberLauncherForActivityResult
        }
        val data = result.data
        // For BLE associations, EXTRA_DEVICE can be a ScanResult.
        val scanResult = data?.getParcelableExtraCompat<ScanResult>(CompanionDeviceManager.EXTRA_DEVICE)
        val device = scanResult?.device ?: data?.getParcelableExtraCompat<BluetoothDevice>(CompanionDeviceManager.EXTRA_DEVICE)

        if (device == null) {
            statusText = "No device returned"
            return@rememberLauncherForActivityResult
        }

        try {
            val name = try { device.name } catch (_: SecurityException) { null }
            statusText = "Selected ${name ?: "(unknown)"} (${device.address}). Initiating bonding..."
            vm.addWatchDevice(device.address, name ?: "")
            device.createBond()
        } catch (e: SecurityException) {
            statusText = "Permission denied: ${e.message}"
        } catch (e: Exception) {
            statusText = "Error: ${e.message}"
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Device pairing") }) }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            val devices = settings.associatedDevices
            // Use bondStateVersion to ensure recomposition when bond state changes
            @Suppress("UNUSED_EXPRESSION")
            bondStateVersion // Read to create dependency

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Text("Bluetooth permission: ${if (hasBluetoothConnectPermission()) "Granted" else "Not granted"}")
            }

            if (devices.isEmpty()) {
                Text("No paired watches")
            } else {
                Text("Paired Watches (${devices.size})", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                devices.forEach { device ->
                    val bonded = try {
                        val bluetoothManager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                        bluetoothManager.adapter?.getRemoteDevice(device.address)?.bondState == BluetoothDevice.BOND_BONDED
                    } catch (_: Throwable) { false }

                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (device.isPrimary) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    device.name.ifBlank { device.address },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    buildString {
                                        append(device.address)
                                        if (device.isPrimary) append(" (Primary)")
                                        append(if (bonded) " - Bonded" else " - Not bonded")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Row {
                                if (!device.isPrimary) {
                                    TextButton(onClick = { vm.setPrimaryWatch(device.address) }) {
                                        Text("Primary")
                                    }
                                }
                                TextButton(onClick = { vm.removeWatchDevice(device.address) }) {
                                    Text("Remove")
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    // Check permission first on Android 12+
                    if (!hasBluetoothConnectPermission()) {
                        statusText = "Requesting Bluetooth permission..."
                        permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    } else {
                        startAssociation(ctx) { req -> pickerLauncher.launch(req) ; statusText = "Searching..." }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Add PineTime Watch")
            }

            if (statusText != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(statusText!!)
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("Tip: If bonding doesn't start automatically, open Bluetooth settings and pair there. Connected Mode requires a bonded device.")
        }
    }
}

private fun startAssociation(context: Context, launch: (IntentSenderRequest) -> Unit) {
    val cdm = context.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager

    val deviceFilter = BluetoothLeDeviceFilter.Builder()
        .setNamePattern(Pattern.compile("(PineTime|InfiniTime).*", Pattern.CASE_INSENSITIVE))
        .build()

    val req = AssociationRequest.Builder()
        .addDeviceFilter(deviceFilter)
        .setSingleDevice(false)
        .build()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // API 33+: Executor-based associate with onAssociationPending
        val executor: Executor = ContextCompat.getMainExecutor(context)
        cdm.associate(req, executor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: android.content.IntentSender) {
                launch(IntentSenderRequest.Builder(intentSender).build())
            }

            override fun onFailure(errorMessage: CharSequence?) {
                // UI will show failure via statusText if desired.
            }

            override fun onAssociationCreated(associationInfo: android.companion.AssociationInfo) {
                // No-op: we prefer the device returned in the activity result.
            }
        })
    } else {
        // API 31-32: Handler-based associate with onDeviceFound
        @Suppress("DEPRECATION")
        cdm.associate(req, object : CompanionDeviceManager.Callback() {
            @Deprecated("Deprecated in API 33, replaced by onAssociationPending")
            override fun onDeviceFound(chooserLauncher: android.content.IntentSender) {
                launch(IntentSenderRequest.Builder(chooserLauncher).build())
            }

            override fun onFailure(errorMessage: CharSequence?) {
                // UI will show failure via statusText if desired.
            }
        }, null)
    }
}

private inline fun <reified T : Parcelable> Intent.getParcelableExtraCompat(key: String): T? {
    return if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java) else @Suppress("DEPRECATION") getParcelableExtra(key)
}
