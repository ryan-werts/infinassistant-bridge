package com.w6tech.infinassistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.w6tech.infinassistant.appearance.AppearanceController
import com.w6tech.infinassistant.ui.AppearanceViewModel
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.w6tech.infinassistant.bridge.BridgeForegroundService
import com.w6tech.infinassistant.core.data.SettingsRepository
import com.w6tech.infinassistant.core.data.appearance.ThemeMode
import com.w6tech.infinassistant.core.data.appearance.isEffectivelyDark
import com.w6tech.infinassistant.core.ui.theme.AppTheme
import com.w6tech.infinassistant.ui.screens.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var appearanceController: AppearanceController
    @Inject lateinit var settingsRepo: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appearanceController.start()
        setContent {
            PineTimeHaBridgeAppUi()
        }
    }

    /**
     * The bridge service started at boot, or from a notification, cannot hold the microphone type:
     * Android allows it only for a start made while the app is visible. So each time the app comes
     * to the foreground with the microphone permission granted, the running service is asked to
     * enter it (FR-P1). It also covers the permission having just been granted.
     */
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            try {
                val settings = settingsRepo.settings.first()
                val microphoneGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (microphoneGranted && settings.connectedModeEnabled && settings.ha.hasToken &&
                    settings.associatedDeviceAddress != null
                ) {
                    ContextCompat.startForegroundService(
                        this@MainActivity,
                        Intent(this@MainActivity, BridgeForegroundService::class.java)
                            .setAction(BridgeForegroundService.ACTION_REFRESH_TYPES),
                    )
                }
            } catch (e: Exception) {
                Log.w("MainActivity", "Could not refresh the bridge service's types: ${e.message}")
            }
        }
    }
}

@Composable
fun PineTimeHaBridgeAppUi(appearanceVm: AppearanceViewModel = hiltViewModel()) {
    val storedMode by appearanceVm.mode.collectAsState()
    val dark = isEffectivelyDark(storedMode ?: ThemeMode.System, isSystemInDarkTheme())
    AppTheme(dark = dark) {
        SystemBarsFollowTheme(dark)
        Surface(modifier = Modifier.fillMaxSize()) {
            val nav = rememberNavController()
            NavHost(navController = nav, startDestination = Routes.Home) {
                composable(Routes.Home) { HomeScreen(nav, appearanceVm = appearanceVm) }
                composable(Routes.Onboarding) { OnboardingScreen(nav) }
                composable(Routes.Pairing) { PairingScreen(nav) }
                composable(Routes.ConnectedMode) { ConnectedModeScreen(nav) }
                composable(Routes.Notifications) { NotificationsScreen(nav) }
                composable(Routes.Debug) { DebugScreen(nav) }
                composable(Routes.Health) { BridgeHealthScreen(nav) }
                composable(Routes.Dfu) { DfuScreen(nav) }
                composable(Routes.InfinAssistant) { InfinassistantScreen(nav) }
                composable(Routes.WatchFaceImport) { WatchFaceImportScreen(nav) }
                composable(Routes.BatteryOptimization) { BatteryOptimizationScreen(nav) }
            }
        }
    }
}

/**
 * Keeps both system bars in step with the effective theme: the icon appearance of the status and
 * navigation bars, and the window's own background (visible behind the transparent bars). The
 * background colour comes from the active scheme.
 */
@Composable
private fun SystemBarsFollowTheme(dark: Boolean) {
    val view = LocalView.current
    val background = MaterialTheme.colorScheme.background.toArgb()
    DisposableEffect(view, dark, background) {
        val window = view.context.findActivity()?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
            window.setBackgroundDrawable(ColorDrawable(background))
        }
        onDispose { }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

object Routes {
    const val Home = "home"
    const val Onboarding = "onboarding"
    const val Pairing = "pairing"
    const val ConnectedMode = "connected_mode"
    const val Notifications = "notifications"
    const val Debug = "debug"
    const val Health = "bridge_health"
    const val Dfu = "dfu"
    const val InfinAssistant = "infinassistant"
    const val WatchFaceImport = "watchface_import"
    const val BatteryOptimization = "battery_optimization"
}
