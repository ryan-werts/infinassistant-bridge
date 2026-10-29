package com.w6tech.infinassistant.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import com.w6tech.infinassistant.ui.BundledFirmware
import com.w6tech.infinassistant.ui.DfuViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DfuScreen(
    nav: NavHostController,
    vm: DfuViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val embeddedVersion by vm.embeddedFirmwareVersion.collectAsState()
    val bundledFirmware by vm.bundledFirmware.collectAsState()
    val selectedFirmware by vm.selectedFirmware.collectAsState()

    // The bundled packages are offered as a choice only when there is more than one
    val showFirmwarePicker = bundledFirmware.size >= 2

    // The first package is the default: get it ready as soon as any package is bundled, even a
    // single one with no choice to show, so Start Update flashes whichever package is selected
    val hasBundledFirmware = bundledFirmware.isNotEmpty()
    LaunchedEffect(hasBundledFirmware) {
        if (hasBundledFirmware) vm.selectDefaultFirmware()
    }

    // An update takes minutes: keep the phone's screen on while one runs and this
    // screen is showing. The transfer itself runs in a foreground service and goes on with the
    // screen off; this only spares the user from keeping the phone awake by hand.
    val view = LocalView.current
    DisposableEffect(state.isInProgress) {
        view.keepScreenOn = state.isInProgress
        onDispose { view.keepScreenOn = false }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { vm.selectFile(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Firmware Update") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Up to four bundled packages can push Start Update below the fold
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Device Info Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Device Information",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (state.deviceAddress != null) {
                            "Address: ${state.deviceAddress}"
                        } else {
                            "No device associated"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Status: ${state.status}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // File Selection Section
            Text(
                text = "Firmware File",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (showFirmwarePicker) {
                // Bundled firmware picker: selecting a package gets it ready to flash
                BundledFirmwarePicker(
                    firmware = bundledFirmware,
                    selected = selectedFirmware,
                    enabled = !state.isInProgress,
                    onSelect = { vm.selectBundledFirmware(it) }
                )
            } else {
                // Embedded firmware button
                Button(
                    onClick = { vm.useEmbeddedFirmware() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isInProgress
                ) {
                    val versionText = embeddedVersion?.let { "InfiniTime $it" } ?: "Embedded Firmware"
                    Text("Use $versionText")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Or divider
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f))
                Text(
                    text = "OR",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider(modifier = Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Custom file picker button
            OutlinedButton(
                onClick = { filePicker.launch("application/zip") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isInProgress
            ) {
                Text("Select Custom Firmware File (.zip)")
            }

            if (state.fileUri != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Selected file:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        val uri = state.fileUri
                        Text(
                            text = uri?.lastPathSegment ?: uri.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Progress Section
            if (state.isInProgress) {
                Text(
                    text = "Update Progress",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                LinearProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
                ) {
                    Text(
                        text = state.status,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "${state.progress}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // Error Display
            if (state.error != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Error",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        val errorMsg = state.error ?: ""
                        Text(
                            text = errorMsg,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { vm.clearError() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Clear")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Action Buttons
            if (state.isInProgress) {
                OutlinedButton(
                    onClick = { vm.cancelDfu() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cancel (use notification)")
                }
            } else if (state.progress == 100 && state.error == null) {
                // Update complete
                Button(
                    onClick = { vm.reset() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Reset")
                }
            } else {
                // Ready to start
                Button(
                    onClick = { vm.startDfu() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.fileUri != null && !state.isInProgress
                ) {
                    Text("Start Update")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Information text
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Important:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "• Update takes a few minutes\n" +
                                "• Keep phone near watch\n" +
                                "• Don't leave this screen during update\n" +
                                "• Watch will reboot after update\n" +
                                "• Connected Mode will be disabled automatically",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }
    }
}

/**
 * The bundled firmware packages as a single choice: each row shows the package's title, with its
 * detail in smaller text below.
 */
@Composable
private fun BundledFirmwarePicker(
    firmware: List<BundledFirmware>,
    selected: Int?,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    Column(modifier = Modifier.selectableGroup()) {
        firmware.forEachIndexed { index, entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = index == selected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(index) },
                    )
                    .padding(vertical = 8.dp),
                // The radio button lines up with the title, not the middle of a long detail
                verticalAlignment = Alignment.Top,
            ) {
                RadioButton(selected = index == selected, onClick = null, enabled = enabled)
                Column(
                    modifier = Modifier
                        .padding(start = 16.dp)
                        // Dimmed like the radio button while the choice is disabled
                        .alpha(if (enabled) 1f else 0.38f)
                ) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = entry.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
