package com.w6tech.infinassistant.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import com.w6tech.infinassistant.core.ui.theme.AppColors
import com.w6tech.infinassistant.core.ui.theme.StatusRole
import com.w6tech.infinassistant.core.ui.theme.heapPoolStatusRole
import com.w6tech.infinassistant.ui.WatchFaceImportUiState
import com.w6tech.infinassistant.ui.WatchFaceImportViewModel
import com.w6tech.infinassistant.ui.WatchFacePreview
import com.w6tech.infinassistant.ui.previewsDashboard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchFaceImportScreen(
    nav: NavHostController,
    vm: WatchFaceImportViewModel = hiltViewModel(),
) {
    val uiState by vm.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import Watch Face") },
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
                .verticalScroll(rememberScrollState())
        ) {
            // Tab Row
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Paste JSON") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Fetch from HA") }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Tab Content
            when (selectedTab) {
                0 -> PasteJsonTab(vm = vm, uiState = uiState)
                1 -> FetchFromHATab(vm = vm, uiState = uiState)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Preview Section (shown when Parsed)
            if (uiState is WatchFaceImportUiState.Parsed) {
                PreviewSection(
                    uiState = uiState as WatchFaceImportUiState.Parsed,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Error Section
            if (uiState is WatchFaceImportUiState.Error) {
                ErrorSection(
                    error = (uiState as WatchFaceImportUiState.Error).message,
                    onRetry = { vm.clearError() },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Apply Button / Status
            when (uiState) {
                is WatchFaceImportUiState.Parsed -> {
                    Button(
                        onClick = { vm.applyConfiguration(nav) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        enabled = true
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Apply Watch Face")
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
                is WatchFaceImportUiState.Applying -> {
                    val applyingState = uiState as WatchFaceImportUiState.Applying
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val progress = if (applyingState.total > 0) {
                                applyingState.current.toFloat() / applyingState.total
                            } else 0f
                            val pct = (progress * 100).toInt()

                            Text(
                                text = if (applyingState.total > 0) {
                                    "Sending watch face... $pct%"
                                } else {
                                    "Sending to watch..."
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            if (applyingState.total > 0) {
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f),
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f),
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
                is WatchFaceImportUiState.Applied -> {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Watch face applied successfully!",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            (uiState as WatchFaceImportUiState.Applied).warning?.let { warning ->
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = warning,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
                else -> { /* No button for other states */ }
            }
        }
    }
}

@Composable
private fun PasteJsonTab(
    vm: WatchFaceImportViewModel,
    uiState: WatchFaceImportUiState,
) {
    var jsonInput by remember { mutableStateOf("") }
    val lastPushedJson by vm.lastPushedJson.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        // Info Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Paste Watch Face JSON",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "In your Home Assistant Lovelace card, click \"Copy Watch Layout JSON\" then paste it here. Alternatively, the card's \"Push to Watch\" button sends directly to your watch.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Last Pushed JSON Section
        val currentLastPushedJson = lastPushedJson
        if (currentLastPushedJson != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Last Pushed to Watch",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                        OutlinedButton(
                            onClick = { jsonInput = currentLastPushedJson },
                            enabled = uiState !is WatchFaceImportUiState.Loading
                        ) {
                            Text("Load")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = currentLastPushedJson.take(150) + if (currentLastPushedJson.length > 150) "..." else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 3
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // JSON Input Field
        OutlinedTextField(
            value = jsonInput,
            onValueChange = { jsonInput = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            label = { Text("Watch Face JSON") },
            placeholder = { Text("{\n  \"version\": 1,\n  \"theme\": { ... },\n  ...\n}") },
            enabled = uiState !is WatchFaceImportUiState.Loading,
            maxLines = 10
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Parse Button
        Button(
            onClick = { vm.importFromJsonString(jsonInput) },
            modifier = Modifier.fillMaxWidth(),
            enabled = jsonInput.isNotBlank() && uiState !is WatchFaceImportUiState.Loading
        ) {
            if (uiState is WatchFaceImportUiState.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.width(20.dp).height(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Parsing...")
            } else {
                Text("Parse JSON")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FetchFromHATab(
    vm: WatchFaceImportViewModel,
    uiState: WatchFaceImportUiState,
) {
    var deviceId by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    val devices by vm.devices.collectAsState()
    val deviceFetchError by vm.deviceFetchError.collectAsState()

    // Helper to get a useful display name for a device
    fun getDeviceDisplayName(device: com.w6tech.infinassistant.core.ha.HaDeviceRegistryEntry): String {
        return device.nameByUser?.takeIf { it.isNotBlank() }
            ?: device.name?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(device.manufacturer, device.model)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .takeIf { it.isNotBlank() }
            ?: device.id
    }

    // Filter devices based on search text
    val filteredDevices = devices.filter {
        val displayName = getDeviceDisplayName(it)
        displayName.contains(deviceName, ignoreCase = true) ||
        it.id.contains(deviceName, ignoreCase = true)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        // Info Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Fetch from Home Assistant",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Select your PineTime watch from the dropdown below to fetch its watch face configuration from Home Assistant. Only devices registered with the InfinAssistant integration will appear.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Device Dropdown
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded && uiState !is WatchFaceImportUiState.Loading }
        ) {
            OutlinedTextField(
                value = deviceName,
                onValueChange = {
                    deviceName = it
                    deviceId = ""  // Clear selection when typing
                    expanded = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryEditable),
                label = { Text("PineTime Watch") },
                placeholder = { Text("Select your watch...") },
                enabled = uiState !is WatchFaceImportUiState.Loading,
                singleLine = true,
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Dropdown"
                    )
                }
            )

            ExposedDropdownMenu(
                expanded = expanded && filteredDevices.isNotEmpty(),
                onDismissRequest = { expanded = false }
            ) {
                filteredDevices.take(10).forEach { device ->
                    val displayName = getDeviceDisplayName(device)
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(displayName)
                                // Show ID in small text if name came from manufacturer/model
                                if (device.nameByUser.isNullOrBlank() && device.name.isNullOrBlank()) {
                                    Text(
                                        text = device.id.take(20) + if (device.id.length > 20) "..." else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        onClick = {
                            deviceId = device.id
                            deviceName = displayName
                            expanded = false
                        }
                    )
                }
            }
        }

        // Device fetch status
        val fetchError = deviceFetchError
        Spacer(modifier = Modifier.height(8.dp))
        when {
            fetchError != null -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = fetchError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { vm.retryFetchDevices() }
                        ) {
                            Text("Retry")
                        }
                    }
                }
            }
            devices.isEmpty() -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(16.dp).height(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Loading PineTime devices from Home Assistant...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                Text(
                    text = "${devices.size} PineTime device(s) found",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Fetch Button
        Button(
            onClick = { vm.fetchFromHA(deviceId) },
            modifier = Modifier.fillMaxWidth(),
            enabled = deviceId.isNotBlank() && uiState !is WatchFaceImportUiState.Loading
        ) {
            if (uiState is WatchFaceImportUiState.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.width(20.dp).height(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Fetching...")
            } else {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Fetch from HA")
            }
        }
    }
}

@Composable
private fun PreviewSection(
    uiState: WatchFaceImportUiState.Parsed,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = "Preview",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Screens",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = "${uiState.summary.screenCount}/${uiState.summary.screenCap}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column {
                        Text(
                            text = "Tiles",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = "${uiState.summary.tileCount}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Theme Accent",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = uiState.summary.themeAccent,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    // Color preview box could be added here
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Configuration is valid and ready to apply.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        // REQ-074 — Real-time layout score: object count, style count, CBOR bytes,
        // and projected LVGL pool occupancy with Green/Yellow/Red indicator.
        Spacer(modifier = Modifier.height(12.dp))
        LayoutScoreCard(summary = uiState.summary)

        // Visual watch face preview
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Watch Preview",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            WatchFacePreview(
                payload = uiState.payload,
                modifier = Modifier.padding(16.dp),
            )
        }
        if (previewsDashboard(uiState.payload)) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Sample values: the time is now, the rest is made up. " +
                    "The red freezer bar shows only when a freezer is warm or offline.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * REQ-074 — Layout score card surfacing all four real-time editor metrics:
 *   objectCount   — total LVGL objects (glyphs + sensors)
 *   styleCount    — total style slots (one per group)
 *   cborBytes     — encoded CBOR payload size
 *   lvglPoolPct   — projected LVGL pool occupancy (Green <50%, Yellow 50-80%, Red >80%)
 */
@Composable
private fun LayoutScoreCard(
    summary: com.w6tech.infinassistant.ui.ConfigSummary,
    modifier: Modifier = Modifier,
) {
    val poolRole = heapPoolStatusRole(summary.lvglPoolPct)
    val scoreLabel = when (poolRole) {
        StatusRole.Success -> "Green"
        StatusRole.Warning -> "Yellow"
        StatusRole.Danger -> "Red"
    }
    val poolColors = AppColors.status(poolRole)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Layout Score",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Color badge
                Box(
                    modifier = Modifier
                        .background(color = poolColors.container, shape = RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "$scoreLabel  ${summary.lvglPoolPct}%",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = poolColors.onContainer,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Object count
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Objects",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${summary.objectCount}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Style count
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Styles",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${summary.styleCount}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // CBOR bytes
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "CBOR Bytes",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${summary.cborBytes}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Pool %
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "LVGL Pool",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${summary.lvglPoolPct}%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = poolColors.foreground,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // LVGL pool progress bar
            LinearProgressIndicator(
                progress = { (summary.lvglPoolPct / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = poolColors.container,
                trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f),
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Thresholds: Green <50% pool · Yellow 50–80% · Red >80%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorSection(
    error: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Error",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onRetry
            ) {
                Text("Dismiss")
            }
        }
    }
}
