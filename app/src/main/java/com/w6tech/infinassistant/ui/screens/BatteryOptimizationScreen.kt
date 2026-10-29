package com.w6tech.infinassistant.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.w6tech.infinassistant.core.ui.theme.AppColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryOptimizationScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val manufacturer = Build.MANUFACTURER.lowercase()
    val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as? PowerManager
    var isIgnoring by remember {
        mutableStateOf(pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Battery Optimization") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "Background Reliability",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "InfinAssistant Bridge needs to run in the background to keep the BLE and HA WebSocket connections alive. " +
                    "Some manufacturers aggressively kill background apps. Follow the steps below to ensure reliable operation.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(16.dp))

            // Step 1: Android battery optimization
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isIgnoring) AppColors.semantic.success.tint else MaterialTheme.colorScheme.surfaceVariant
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "1. Disable Battery Optimization",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    if (isIgnoring) {
                        Text("Battery optimization is already disabled for this app.", color = AppColors.semantic.success.foreground)
                    } else {
                        Text("Allow this app to run unrestricted in the background.")
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${ctx.packageName}")
                                }
                                ctx.startActivity(intent)
                            } catch (_: Exception) {
                                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                try { ctx.startActivity(intent) } catch (_: Exception) {}
                            }
                            // Re-check after returning
                            isIgnoring = pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true
                        }) {
                            Text("Disable Battery Optimization")
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Step 2: OEM-specific guidance
            val (oemName, oemGuidance) = getOemGuidance(manufacturer)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "2. $oemName Settings",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(oemGuidance, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val dkmaUrl = "https://dontkillmyapp.com/${manufacturer.replace(" ", "-")}"
                            try {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dkmaUrl)))
                            } catch (_: Exception) {}
                        }) {
                            Text("Open Guide")
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Step 3: Lock in recents
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "3. Lock App in Recent Apps",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Open Recent Apps, long-press InfinAssistant Bridge, and select \"Lock\" or \"Pin\". " +
                            "This prevents the system from killing the app when clearing recent apps.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { nav.popBackStack() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Done")
            }
        }
    }
}

private fun getOemGuidance(manufacturer: String): Pair<String, String> {
    return when {
        manufacturer.contains("samsung") -> "Samsung" to
            "Go to Settings > Battery > Background usage limits. " +
            "Make sure InfinAssistant Bridge is NOT in the 'Sleeping apps' or 'Deep sleeping apps' lists. " +
            "Also disable 'Adaptive battery' if the app is being killed."

        manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> "Xiaomi/MIUI" to
            "Go to Settings > Apps > Manage apps > InfinAssistant Bridge > Battery saver > No restrictions. " +
            "Also enable 'Autostart' for the app. MIUI is one of the most aggressive at killing background apps."

        manufacturer.contains("huawei") || manufacturer.contains("honor") -> "Huawei/EMUI" to
            "Go to Settings > Battery > App launch. Set InfinAssistant Bridge to 'Manage manually' and enable all three toggles " +
            "(Auto-launch, Secondary launch, Run in background)."

        manufacturer.contains("oneplus") || manufacturer.contains("oppo") || manufacturer.contains("realme") -> "OnePlus/OPPO" to
            "Go to Settings > Battery > Battery optimization > InfinAssistant Bridge > Don't optimize. " +
            "Also check Settings > Apps > App Management > InfinAssistant Bridge and disable 'Auto-close'."

        manufacturer.contains("vivo") -> "Vivo" to
            "Go to Settings > Battery > High background power consumption > enable InfinAssistant Bridge. " +
            "Also go to i Manager > App Manager > Autostart Manager and enable autostart."

        manufacturer.contains("asus") -> "ASUS" to
            "Go to Settings > Power Management > Auto-start Manager. Allow InfinAssistant Bridge to auto-start."

        manufacturer.contains("nokia") || manufacturer.contains("hmd") -> "Nokia/HMD" to
            "Go to Settings > Apps > InfinAssistant Bridge > Battery > Background activity: Allowed. " +
            "Nokia uses stock Android but with Evenwell's DuraSpeed which kills background apps."

        manufacturer.contains("google") || manufacturer.contains("pixel") -> "Google Pixel" to
            "Pixel uses near-stock Android. The battery optimization exemption above should be sufficient. " +
            "If you still have issues, go to Settings > Battery > Battery usage and check for anomalies."

        else -> "Your Device ($manufacturer)" to
            "Check your device's battery or power management settings. Look for options like " +
            "'Background restrictions', 'App optimization', or 'Auto-start management' and ensure " +
            "InfinAssistant Bridge is allowed to run in the background."
    }
}
