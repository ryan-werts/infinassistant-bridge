package com.w6tech.infinassistant.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.w6tech.infinassistant.core.data.BridgeStatus
import com.w6tech.infinassistant.core.domain.BridgeSettings
import com.w6tech.infinassistant.core.ui.theme.AppColors
import com.w6tech.infinassistant.ui.PipelineChoices
import com.w6tech.infinassistant.ui.VoiceAssistViewModel

/**
 * The Voice assist section of Connected mode (FR-P2): the on/off switch, the microphone
 * permission, which Assist pipeline to use, and whether the watch and the bridge service can do
 * voice. Voice is off until the owner turns it on, since a double-click then records.
 */
@Composable
fun VoiceAssistSection(
    settings: BridgeSettings,
    status: BridgeStatus,
    vm: VoiceAssistViewModel = hiltViewModel(),
) {
    val ctx = LocalContext.current
    val pipelines by vm.pipelines.collectAsState()
    val voice = settings.voiceAssist

    // The permission can change outside the app (system settings), so it is read again on resume.
    var permissionVersion by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionVersion++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    @Suppress("UNUSED_EXPRESSION")
    permissionVersion
    val micGranted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    // The dialog's return resumes MainActivity, which asks the running service to enter the
    // microphone type (MainActivity.onResume), so the result needs no request of its own here.
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionVersion++
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Voice assist", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "Double-click the watch's side button to talk to Home Assistant. The phone listens, " +
                    "speaks the answer, and listens again until you say thanks or stay quiet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Voice assist")
                Switch(
                    checked = voice.enabled,
                    onCheckedChange = { on ->
                        vm.setEnabled(on)
                        if (on && !micGranted) micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                )
            }

            Spacer(Modifier.height(8.dp))
            StatusLine("Microphone permission", if (micGranted) "Granted" else "Not granted", ok = micGranted)
            if (!micGranted) {
                OutlinedButton(onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                    Text("Grant microphone")
                }
            }
            StatusLine(
                "Watch supports voice",
                when (status.watchSupportsVoice) {
                    true -> "Yes"
                    false -> "No (needs firmware 2.4.99 or later)"
                    null -> "Not known yet (connect the watch)"
                },
                ok = status.watchSupportsVoice == true,
            )
            StatusLine(
                "Microphone in the background service",
                if (status.serviceMicrophone) "Available" else "Not available",
                ok = status.serviceMicrophone,
            )
            if (voice.enabled && !status.serviceMicrophone) {
                Text(
                    if (!micGranted) {
                        "Grant the microphone permission first."
                    } else {
                        "Open the app once to enable voice: Android only lets the service use the " +
                            "microphone after the app has been opened with Connected Mode on."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.semantic.warning.foreground,
                )
            }

            Spacer(Modifier.height(12.dp))
            PipelinePicker(
                selectedId = voice.pipelineId,
                choices = pipelines,
                onLoad = vm::loadPipelines,
                onSelect = vm::setPipeline,
            )
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String, ok: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = if (ok) AppColors.semantic.success.foreground else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PipelinePicker(
    selectedId: String?,
    choices: PipelineChoices,
    onLoad: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val list = (choices as? PipelineChoices.Loaded)?.list
    val preferredName = list?.pipelines?.firstOrNull { it.id == list.preferredId }?.name
    val preferredLabel = "HA's preferred" + (preferredName?.let { " ($it)" } ?: "")
    val selectedLabel = if (selectedId == null) {
        preferredLabel
    } else {
        list?.pipelines?.firstOrNull { it.id == selectedId }?.name ?: selectedId
    }

    Text("Assist pipeline", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            OutlinedButton(onClick = {
                if (choices is PipelineChoices.NotLoaded || choices is PipelineChoices.Failed) onLoad()
                open = true
            }) {
                Text(selectedLabel)
            }
            PipelineMenu(open, { open = false }, preferredLabel, choices, onSelect)
        }
        if (choices is PipelineChoices.Loaded || choices is PipelineChoices.Failed) {
            OutlinedButton(onClick = onLoad) { Text("Refresh") }
        }
    }
}

@Composable
private fun PipelineMenu(
    open: Boolean,
    onDismiss: () -> Unit,
    preferredLabel: String,
    choices: PipelineChoices,
    onSelect: (String?) -> Unit,
) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(preferredLabel) },
            onClick = {
                onSelect(null)
                onDismiss()
            },
        )
        when (choices) {
            is PipelineChoices.Loaded -> choices.list.pipelines.forEach { pipeline ->
                DropdownMenuItem(
                    text = { Text(pipeline.name) },
                    onClick = {
                        onSelect(pipeline.id)
                        onDismiss()
                    },
                )
            }
            PipelineChoices.Loading -> DropdownMenuItem(text = { Text("Loading...") }, onClick = {}, enabled = false)
            is PipelineChoices.Failed -> DropdownMenuItem(
                text = { Text("Could not load: ${choices.reason}") },
                onClick = {},
                enabled = false,
            )
            PipelineChoices.NotLoaded -> Unit
        }
    }
}
