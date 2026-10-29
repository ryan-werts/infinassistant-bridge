package com.w6tech.infinassistant.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import com.w6tech.infinassistant.ui.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfinassistantScreen(
    nav: NavHostController,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val settings by vm.settings.collectAsState()

    var webhookId by remember(settings.infinassistant.webhookId) {
        mutableStateOf(settings.infinassistant.webhookId)
    }
    var hmacSecret by remember(settings.infinassistant.hmacSecret) {
        mutableStateOf(settings.infinassistant.hmacSecret)
    }

    // The slider moves through 27 positions; saving each one restarted the bridge session (the
    // interval is part of the session's settings): 28 sessions in 22 s in one bundle. The value
    // is held here while the thumb moves and saved when it is let go.
    var reportInterval by remember(settings.infinassistant.sensorReportIntervalSeconds) {
        mutableFloatStateOf(settings.infinassistant.sensorReportIntervalSeconds.toFloat())
    }

    Scaffold(topBar = { TopAppBar(title = { Text("InfinAssistant (HA Sensors)") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Enable/Disable toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Enable InfinAssistant", modifier = Modifier.weight(1f))
                Switch(
                    checked = settings.infinassistant.enabled,
                    onCheckedChange = { vm.setInfinassistantEnabled(it) }
                )
            }

            Spacer(Modifier.height(16.dp))

            // Webhook ID field
            OutlinedTextField(
                value = webhookId,
                onValueChange = { webhookId = it },
                label = { Text("Webhook ID") },
                supportingText = {
                    Text(
                        "Paste the webhook ID from Home Assistant (find it in sensor attributes: webhook_url attribute)",
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = settings.infinassistant.enabled
            )

            Spacer(Modifier.height(8.dp))

            // Save indicator
            if (webhookId != settings.infinassistant.webhookId) {
                Text(
                    "Tap outside to save ID",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(16.dp))

            // HMAC secret field. Without this the integration rejects every
            // sensor report with HTTP 401 — it always holds a secret, and an
            // unsigned POST cannot satisfy it.
            OutlinedTextField(
                value = hmacSecret,
                onValueChange = { hmacSecret = it },
                label = { Text("HMAC Secret") },
                supportingText = {
                    Text(
                        "Paste the hmac_secret from the integration's setup QR payload. " +
                            "Reports are rejected without it.",
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = settings.infinassistant.enabled
            )

            Spacer(Modifier.height(8.dp))

            if (hmacSecret != settings.infinassistant.hmacSecret) {
                Text(
                    "Tap outside to save secret",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(16.dp))

            // Report interval slider
            Text("Sensor Report Interval")
            Text(
                "${reportInterval.toInt()} seconds",
                style = MaterialTheme.typography.bodySmall
            )
            Slider(
                value = reportInterval,
                onValueChange = { reportInterval = it },
                onValueChangeFinished = { vm.setInfinassistantReportInterval(reportInterval.toInt()) },
                valueRange = 30f..300f,
                steps = 26,
                modifier = Modifier.fillMaxWidth(),
                enabled = settings.infinassistant.enabled
            )
            Text(
                "How often to push sensor data to Home Assistant",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(24.dp))

            // Information section
            Text(
                "How to Setup:",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                """
                1. Install the InfinAssistant custom integration in Home Assistant
                2. Add a new device (Settings → Devices & Services → InfinAssistant → Add Entry)
                3. Enter your watch MAC address when prompted
                4. After setup, find any InfinAssistant sensor (e.g., battery sensor)
                5. Click on the sensor → Attributes tab → copy the webhook ID
                6. Paste the webhook ID above
                7. Enable InfinAssistant toggle
                8. Make sure Connected Mode is running
                
                The app will automatically use your local/remote HA URLs and send sensor data periodically.
                """.trimIndent(),
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(16.dp))

            // Status indicator
            val statusText = when {
                !settings.infinassistant.enabled -> "Status: Disabled"
                settings.infinassistant.webhookId.isEmpty() -> "Status: No webhook ID configured"
                settings.infinassistant.hmacSecret.isEmpty() ->
                    "Status: No HMAC secret - Home Assistant will reject reports (401)"
                settings.associatedDeviceAddress == null -> "Status: No watch paired"
                !settings.connectedModeEnabled -> "Status: Connected Mode not running"
                settings.ha.localUrl.isBlank() && settings.ha.remoteUrl.isBlank() -> "Status: No HA URL configured"
                else -> "Status: Active - sensor reports will be sent every ${settings.infinassistant.sensorReportIntervalSeconds}s"
            }

            Text(
                statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (settings.infinassistant.enabled && settings.infinassistant.webhookId.isNotEmpty()) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }

    // Save webhook ID when value changes
    if (webhookId != settings.infinassistant.webhookId && webhookId.isNotBlank()) {
        vm.setInfinassistantWebhookId(webhookId.trim())
    }
    if (hmacSecret != settings.infinassistant.hmacSecret && hmacSecret.isNotBlank()) {
        vm.setInfinassistantHmacSecret(hmacSecret.trim())
    }
}
