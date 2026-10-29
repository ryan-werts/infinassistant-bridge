package com.w6tech.infinassistant.ui.screens

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import com.w6tech.infinassistant.Routes
import com.w6tech.infinassistant.core.common.AppResult
import com.w6tech.infinassistant.core.common.LocalNetwork
import com.w6tech.infinassistant.core.data.HomeSubnets
import com.w6tech.infinassistant.core.ui.theme.AppColors
import com.w6tech.infinassistant.ui.BridgeViewModel
import com.w6tech.infinassistant.ui.SettingsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    nav: NavHostController,
    settingsVm: SettingsViewModel = hiltViewModel(),
    bridgeVm: BridgeViewModel = hiltViewModel(),
) {
    val ctx = LocalContext.current
    val settings by settingsVm.settings.collectAsState()
    val haTest by bridgeVm.haTest.collectAsState()

    var localUrl by remember(settings.ha.localUrl) { mutableStateOf(settings.ha.localUrl) }
    var remoteUrl by remember(settings.ha.remoteUrl) { mutableStateOf(settings.ha.remoteUrl) }
    var subnetsText by remember(settings.ha.homeSubnets) { mutableStateOf(settings.ha.homeSubnets.joinToString(", ")) }
    val (homeSubnets, badSubnets) = HomeSubnets.parseInput(subnetsText)
    // Plain http to an address that is not local is refused at every connect: say so while it is typed.
    val localUrlProblem = LocalNetwork.plainHttpProblem(localUrl)
    var token by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // Compute setup progress
    val haConfigured = (settings.ha.localUrl.isNotBlank() || settings.ha.remoteUrl.isNotBlank()) && settings.ha.hasToken
    val haConnected = haTest is AppResult.Ok
    val deviceAssociated = settings.associatedDeviceAddress != null
    val deviceBonded = settings.associatedDeviceAddress?.let {
        try {
            val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bm.adapter?.getRemoteDevice(it)?.bondState == BluetoothDevice.BOND_BONDED
        } catch (_: Throwable) { false }
    } ?: false

    val hasBlePerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    } else true

    val hasNotifPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else true

    val permsOk = hasBlePerm && hasNotifPerm

    val permsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { /* UI re-renders */ }
    )

    val completedSteps = listOf(haConfigured, permsOk, deviceAssociated && deviceBonded).count { it }
    val allDone = completedSteps == 3

    Scaffold(
        topBar = { TopAppBar(title = { Text("Setup") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Progress indicator
            SetupProgressRow(
                steps = listOf(
                    "HA" to haConfigured,
                    "Permissions" to permsOk,
                    "Watch" to (deviceAssociated && deviceBonded),
                ),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Step 1: Home Assistant
            StepCard(
                stepNumber = 1,
                title = "Home Assistant Connection",
                complete = haConfigured,
            ) {
                OutlinedTextField(
                    value = localUrl,
                    onValueChange = { localUrl = it },
                    label = { Text("Local URL") },
                    isError = localUrlProblem != null,
                    supportingText = { Text(localUrlProblem ?: "e.g. http://homeassistant.local:8123") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                )
                // An http local URL sends the token unencrypted: only on these networks.
                OutlinedTextField(
                    value = subnetsText,
                    onValueChange = { subnetsText = it },
                    label = { Text("Home networks") },
                    isError = badSubnets.isNotEmpty(),
                    supportingText = {
                        Text(
                            if (badSubnets.isNotEmpty()) "Not a subnet: ${badSubnets.joinToString()}"
                            else "An http local URL is used only on these networks, e.g. 192.168.1.0/24, " +
                                "separated by commas. Empty: no http local URL is used."
                        )
                    },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = {
                        scope.launch {
                            val here = settingsVm.currentSubnets()
                            if (here.isEmpty()) {
                                Toast.makeText(ctx, "Not on Wi-Fi or Ethernet", Toast.LENGTH_SHORT).show()
                            } else {
                                subnetsText = (homeSubnets + here).distinct().joinToString(", ")
                            }
                        }
                    },
                    modifier = Modifier.padding(bottom = 4.dp),
                ) {
                    Text("Add this network")
                }
                OutlinedTextField(
                    value = remoteUrl,
                    onValueChange = { remoteUrl = it },
                    label = { Text("Remote URL (optional)") },
                    supportingText = { Text("e.g. https://ha.example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(if (settings.ha.hasToken) "Token (saved)" else "Long-lived access token") },
                    supportingText = {
                        Text(
                            if (settings.ha.hasToken) "Token saved. Enter new value to replace."
                            else "Go to HA -> Profile -> Long-lived access tokens -> Create Token"
                        )
                    },
                    placeholder = { if (settings.ha.hasToken) Text("\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            settingsVm.setHaLocalUrl(localUrl)
                            settingsVm.setHaRemoteUrl(remoteUrl)
                            settingsVm.setHomeSubnets(homeSubnets)
                            if (token.isNotBlank()) settingsVm.setHaToken(token.trim())
                        },
                        enabled = (localUrl.isNotBlank() || remoteUrl.isNotBlank()) && badSubnets.isEmpty(),
                    ) {
                        Text("Save")
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                settingsVm.setHaLocalUrl(localUrl)
                                settingsVm.setHaRemoteUrl(remoteUrl)
                                settingsVm.setHomeSubnets(homeSubnets)
                                if (token.isNotBlank()) settingsVm.setHaToken(token.trim())
                                delay(200)
                                bridgeVm.testHaConnection()
                            }
                        },
                        enabled = (localUrl.isNotBlank() || remoteUrl.isNotBlank()) && badSubnets.isEmpty(),
                    ) {
                        Text("Test")
                    }
                    OutlinedButton(
                        onClick = {
                            val haUrl = localUrl.ifBlank { remoteUrl }.trimEnd('/')
                            if (haUrl.isNotBlank()) {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$haUrl/profile"))
                                try { ctx.startActivity(intent) } catch (_: Exception) { }
                            }
                        },
                        enabled = localUrl.isNotBlank() || remoteUrl.isNotBlank(),
                    ) {
                        Text("Open HA")
                    }
                }

                when (val r = haTest) {
                    null -> {}
                    is AppResult.Ok -> Text(
                        "Connection OK",
                        color = AppColors.semantic.success.foreground,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    is AppResult.Err -> Text(
                        "Failed: ${r.error.message}",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Step 2: Permissions
            StepCard(
                stepNumber = 2,
                title = "Permissions",
                complete = permsOk,
            ) {
                Text(
                    "This app needs Bluetooth (scan + connect) and Notification permissions.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text("Bluetooth: ${if (hasBlePerm) "Granted" else "Not granted"}")
                Text("Notifications: ${if (hasNotifPerm) "Granted" else "Not granted"}")
                Spacer(modifier = Modifier.height(8.dp))
                if (!permsOk) {
                    Button(
                        onClick = {
                            val perms = mutableListOf<String>()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                perms.add(Manifest.permission.BLUETOOTH_SCAN)
                                perms.add(Manifest.permission.BLUETOOTH_CONNECT)
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                perms.add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            permsLauncher.launch(perms.toTypedArray())
                        },
                    ) {
                        Text("Grant permissions")
                    }
                } else {
                    Text("All permissions granted.", color = AppColors.semantic.success.foreground)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Step 3: Watch Pairing
            StepCard(
                stepNumber = 3,
                title = "Watch Pairing",
                complete = deviceAssociated && deviceBonded,
            ) {
                if (deviceAssociated) {
                    Text("Device: ${settings.associatedDeviceAddress}")
                    Text("Bonded: ${if (deviceBonded) "Yes" else "No"}")
                } else {
                    Text("No watch paired yet.", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { nav.navigate(Routes.Pairing) }) {
                    Text(if (deviceAssociated) "Change watch" else "Pair watch")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Completion / next step
            if (allDone) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = AppColors.semantic.success.tint),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Setup complete!",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = AppColors.semantic.success.foreground,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Go to Connected Mode to start the bridge. " +
                                "The foreground service keeps BLE and HA WebSocket alive in the background.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                if (!settings.connectedModeEnabled) {
                                    settingsVm.setConnectedModeEnabled(true)
                                    val intent = Intent(ctx, com.w6tech.infinassistant.bridge.BridgeForegroundService::class.java).apply {
                                        action = com.w6tech.infinassistant.bridge.BridgeForegroundService.ACTION_START
                                    }
                                    ContextCompat.startForegroundService(ctx, intent)
                                }
                                nav.navigate(Routes.ConnectedMode) { popUpTo(Routes.Home) }
                            }) {
                                Text(if (settings.connectedModeEnabled) "Go to Connected Mode" else "Enable Connected Mode")
                            }
                            OutlinedButton(onClick = { nav.popBackStack() }) {
                                Text("Back to Home")
                            }
                        }
                    }
                }
            } else {
                val nextStep = when {
                    !haConfigured -> "Enter your Home Assistant URL and token above."
                    !permsOk -> "Grant the required permissions."
                    else -> "Pair your PineTime watch."
                }
                Text(
                    "Next: $nextStep",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SetupProgressRow(steps: List<Pair<String, Boolean>>) {
    val success = AppColors.semantic.success
    val notDone = AppColors.progressStepNotDone
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        steps.forEachIndexed { index, (label, done) ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .height(2.dp)
                        .width(32.dp)
                        .background(if (done) success.foreground else MaterialTheme.colorScheme.outlineVariant)
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(if (done) success.container else notDone.circle),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (done) "\u2713" else "${index + 1}",
                        color = if (done) success.onContainer else notDone.glyph,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun StepCard(
    stepNumber: Int,
    title: String,
    complete: Boolean,
    content: @Composable () -> Unit,
) {
    val success = AppColors.semantic.success
    val notDone = AppColors.cardStepNotDone
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (complete)
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            else
                MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(if (complete) success.container else notDone.circle),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (complete) "\u2713" else "$stepNumber",
                        color = if (complete) success.onContainer else notDone.glyph,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (complete) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Done", color = success.foreground, style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}
