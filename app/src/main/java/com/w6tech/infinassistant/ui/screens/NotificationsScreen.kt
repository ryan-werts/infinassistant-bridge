package com.w6tech.infinassistant.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import com.w6tech.infinassistant.ForwardTestNotification
import com.w6tech.infinassistant.core.data.bridge.WatchLinkState
import com.w6tech.infinassistant.core.domain.NotificationDeliveryMode
import com.w6tech.infinassistant.core.domain.NotificationPrefs
import com.w6tech.infinassistant.core.domain.QuietHours
import com.w6tech.infinassistant.ui.BridgeViewModel
import com.w6tech.infinassistant.ui.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    nav: NavHostController,
    vm: SettingsViewModel = hiltViewModel(),
    bridgeVm: BridgeViewModel = hiltViewModel(),
) {
    val settings by vm.settings.collectAsState()
    val watchLink by bridgeVm.watchLink.collectAsState()
    val prefs = settings.notificationPrefs
    val context = LocalContext.current
    // The form's saved values apart from the forwarding switch, which saves itself: toggling it
    // must not reset what is typed into the rest.
    val formKey = prefs.copy(forwardSystemNotifications = false)

    var deliverToWatch by remember(formKey) { mutableStateOf(prefs.deliverToWatch) }
    var mode by remember(formKey) { mutableStateOf(prefs.deliveryMode) }
    var dedupe by remember(formKey) { mutableStateOf(prefs.dedupeByTag) }
    var dndStart by remember(formKey) { mutableStateOf(prefs.quietHours?.startHour?.toString() ?: "") }
    var dndEnd by remember(formKey) { mutableStateOf(prefs.quietHours?.endHour?.toString() ?: "") }
    var batteryAlert by remember(formKey) { mutableStateOf(prefs.watchBatteryAlert) }
    var batteryAlertPercent by remember(formKey) { mutableStateOf(prefs.watchBatteryAlertPercent.toString()) }
    var disconnectAlert by remember(formKey) { mutableStateOf(prefs.watchDisconnectAlert) }
    var disconnectAlertMinutes by remember(formKey) { mutableStateOf(prefs.watchDisconnectAlertMinutes.toString()) }
    var modeDropdownExpanded by remember { mutableStateOf(false) }

    // Access is granted or taken away in the system settings, so it is read again on resume.
    var accessVersion by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) accessVersion++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val notificationListenerEnabled = remember(accessVersion) {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }

    var testMessage by remember { mutableStateOf<String?>(null) }
    var pairMessage by remember { mutableStateOf<String?>(null) }
    fun sendTest() {
        testMessage = if (ForwardTestNotification.post(context)) null else "Notifications are off for this app in the system settings."
    }
    val postPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { sendTest() }

    // Helper to get user-friendly label for delivery mode
    fun deliveryModeLabel(m: NotificationDeliveryMode): String = when (m) {
        NotificationDeliveryMode.WATCH -> "Watch Only"
        NotificationDeliveryMode.HA_INBOX -> "HA Inbox Only"
        NotificationDeliveryMode.BOTH -> "Both Watch & HA Inbox"
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Notifications") }) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            // Phone notification forwarding: the switch saves at once, apart from the Save button below.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    // Neutral until the switch is on; then a problem with access or the link shows.
                    containerColor = when {
                        !prefs.forwardSystemNotifications -> MaterialTheme.colorScheme.surfaceVariant
                        notificationListenerEnabled && watchLink.allowsPhoneNotifications -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.errorContainer
                    }
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Forward phone notifications",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Switch(
                            checked = prefs.forwardSystemNotifications,
                            onCheckedChange = { on ->
                                vm.setNotificationPrefs(prefs.copy(forwardSystemNotifications = on))
                                if (on && !notificationListenerEnabled) {
                                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                }
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Texts and other alerts from your phone show on the watch. Clearing one on either device clears it on the other. " +
                            "Ongoing, silent and group-summary notifications, and ones Do Not Disturb hides, are not sent. " +
                            "They are sent only while the watch is paired with this phone.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (notificationListenerEnabled) {
                            "✓ Notification access granted"
                        } else {
                            "⚠ Notification access required"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (!notificationListenerEnabled) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Grant Notification Access")
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            // A sideloaded APK has the access switch greyed out on Android 13 and later.
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "If the access switch is greyed out (\"Restricted setting\"): open App info, " +
                                    "tap the three dots at the top right, then Allow restricted settings.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    context.startActivity(
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("App info")
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = when (watchLink) {
                            WatchLinkState.ENCRYPTED -> "✓ Watch link: paired and encrypted"
                            WatchLinkState.PAIRED -> "✓ Watch link: paired"
                            WatchLinkState.CHECKING -> "Watch link: paired, checking with the watch"
                            WatchLinkState.PAIRED_NOT_ENCRYPTED -> "⚠ Watch link: paired, not encrypted yet"
                            WatchLinkState.MISMATCH -> "⚠ Watch link: the phone and the watch disagree about the pairing"
                            WatchLinkState.NOT_PAIRED -> "⚠ Watch link: not paired"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (watchLink == WatchLinkState.MISMATCH) {
                        // The phone holds a bond the watch does not: the connection stays unencrypted. Forgetting
                        // the watch here makes the app offer "Pair with watch", and pairing again replaces the
                        // watch's side.
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Forget the watch in Android's Bluetooth settings (tap its name, then Forget), " +
                                "then come back and tap Pair with watch. The watch shows a 6-digit code to type in.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Open Bluetooth settings")
                        }
                    }
                    if (watchLink == WatchLinkState.NOT_PAIRED) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Phone notifications carry private text, so they go only to a paired watch. " +
                                "Android will ask for the 6-digit code shown on the watch.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                pairMessage = null
                                bridgeVm.pairWithWatch { started ->
                                    if (!started) pairMessage = "Pairing could not start. Is the watch set up and Bluetooth on?"
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Pair with watch")
                        }
                        pairMessage?.let {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                                PackageManager.PERMISSION_GRANTED
                            if (needsPermission) postPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else sendTest()
                        },
                        enabled = prefs.forwardSystemNotifications && notificationListenerEnabled && watchLink.allowsPhoneNotifications,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Send a test")
                    }
                    testMessage?.let {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // HA notification preferences section
            Text(
                text = "Home Assistant Notification Preferences",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Deliver to watch")
                Switch(checked = deliverToWatch, onCheckedChange = { deliverToWatch = it })
            }

            Spacer(Modifier.height(12.dp))

            Text("Delivery Mode", modifier = Modifier.padding(bottom = 4.dp))
            ExposedDropdownMenuBox(
                expanded = modeDropdownExpanded,
                onExpandedChange = { modeDropdownExpanded = it },
            ) {
                OutlinedTextField(
                    value = deliveryModeLabel(mode),
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modeDropdownExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(
                    expanded = modeDropdownExpanded,
                    onDismissRequest = { modeDropdownExpanded = false },
                ) {
                    NotificationDeliveryMode.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(deliveryModeLabel(option)) },
                            onClick = {
                                mode = option
                                modeDropdownExpanded = false
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Dedupe by tag")
                Switch(checked = dedupe, onCheckedChange = { dedupe = it })
            }

            Spacer(Modifier.height(12.dp))

            Text("Quiet hours (DND). Leave blank to disable.")
            OutlinedTextField(
                value = dndStart,
                onValueChange = { dndStart = it },
                label = { Text("Start hour (0-23)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = dndEnd,
                onValueChange = { dndEnd = it },
                label = { Text("End hour (0-23)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(Modifier.height(16.dp))

            // FR-AF-1/2 — alerts on the phone about the watch.
            Text(
                text = "Phone alerts",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Watch battery low")
                Switch(checked = batteryAlert, onCheckedChange = { batteryAlert = it })
            }
            OutlinedTextField(
                value = batteryAlertPercent,
                onValueChange = { batteryAlertPercent = it.filter(Char::isDigit).take(2) },
                label = { Text("Alert at or below (%)") },
                enabled = batteryAlert,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Watch disconnected")
                Switch(checked = disconnectAlert, onCheckedChange = { disconnectAlert = it })
            }
            OutlinedTextField(
                value = disconnectAlertMinutes,
                onValueChange = { disconnectAlertMinutes = it.filter(Char::isDigit).take(3) },
                label = { Text("Alert after (minutes)") },
                enabled = disconnectAlert,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(Modifier.height(16.dp))

            Button(onClick = {
                val quiet = if (dndStart.isBlank() || dndEnd.isBlank()) null else QuietHours(dndStart.toIntOrNull() ?: 22, dndEnd.toIntOrNull() ?: 7)
                vm.setNotificationPrefs(
                    prefs.copy(
                        deliverToWatch = deliverToWatch,
                        deliveryMode = mode,
                        dedupeByTag = dedupe,
                        quietHours = quiet,
                        watchBatteryAlert = batteryAlert,
                        watchBatteryAlertPercent = (batteryAlertPercent.toIntOrNull() ?: prefs.watchBatteryAlertPercent).coerceIn(1, 99),
                        watchDisconnectAlert = disconnectAlert,
                        watchDisconnectAlertMinutes = (disconnectAlertMinutes.toIntOrNull() ?: prefs.watchDisconnectAlertMinutes).coerceIn(1, 999),
                    )
                )
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Save")
            }

            Spacer(Modifier.height(12.dp))
            Text("HA notifications for MVP: fire a custom HA event. Configure the event type in Debug.")
        }
    }
}
