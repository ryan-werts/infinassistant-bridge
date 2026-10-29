package com.w6tech.infinassistant.appearance

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import com.w6tech.infinassistant.core.data.DiagnosticLogger
import com.w6tech.infinassistant.core.data.appearance.PlatformNightMode
import com.w6tech.infinassistant.core.data.appearance.ThemeMode
import com.w6tech.infinassistant.core.data.appearance.ThemeModeStore
import com.w6tech.infinassistant.core.data.appearance.formatAppearanceLine
import com.w6tech.infinassistant.core.data.appearance.isEffectivelyDark
import com.w6tech.infinassistant.core.data.appearance.reconcilePlatformNightMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observes the stored theme mode for the lifetime of the process.
 *
 * It is started from the activity, never from the application class, so appearance code runs only
 * where an activity is created. [start] is idempotent: a second call after an activity
 * recreation does not begin a second collection.
 *
 * The collector owns its own scope because it is a singleton's job and must outlive any one
 * activity; it holds the application context only, so no activity is retained.
 *
 * For the first value after start and for each later change it asserts the platform's per-app
 * night setting from the stored mode. This collector is that copy's only writer and its only call
 * site; the theme on screen is driven by the store, not by this copy. Following the system clears
 * the per-app override, light and dark set one.
 */
@Singleton
class AppearanceController @Inject constructor(
    private val store: ThemeModeStore,
    private val diagnosticLogger: DiagnosticLogger,
    @ApplicationContext private val appContext: Context,
) {
    private val started = AtomicBoolean(false)
    // Last-resort handler: an error that escapes the per-value guard ends the collection but
    // never reaches the process's uncaught-exception handler.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            var firstAfterStart = true
            store.mode.collect { mode ->
                try {
                    onModeObserved(mode, firstAfterStart)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    reportFailure(e)
                }
                firstAfterStart = false
            }
        }
    }

    /**
     * Called for every value the store emits. [firstAfterStart] is true for the value read right
     * after [start] and false for each later change.
     *
     * A failure of the platform call is reported and swallowed so the collector keeps running.
     * Anything else thrown while handling one value is caught by the collector, which then moves
     * on to the next value.
     */
    private fun onModeObserved(mode: ThemeMode, firstAfterStart: Boolean) {
        val platformMode = when (reconcilePlatformNightMode(mode, null)) {
            PlatformNightMode.FollowSystem -> UiModeManager.MODE_NIGHT_AUTO
            PlatformNightMode.Light -> UiModeManager.MODE_NIGHT_NO
            PlatformNightMode.Dark -> UiModeManager.MODE_NIGHT_YES
        }
        try {
            appContext.getSystemService(UiModeManager::class.java).setApplicationNightMode(platformMode)
        } catch (e: Exception) {
            diagnosticLogger.log(
                level = "WARN",
                tag = "Appearance",
                msg = "platform night setting not applied: ${e.javaClass.simpleName}",
            )
        }
        val line = formatAppearanceLine(mode, effectiveDark(mode))
        if (firstAfterStart) {
            diagnosticLogger.log(level = "INFO", tag = "Appearance", msg = line, extra = "start")
        } else {
            diagnosticLogger.log(level = "INFO", tag = "Appearance", msg = line, extra = "change")
        }
    }

    /** Reports a failure of one observed value; the report itself must never throw. */
    private fun reportFailure(e: Exception) {
        try {
            diagnosticLogger.log(
                level = "WARN",
                tag = "Appearance",
                msg = "appearance handling failed: ${e.javaClass.simpleName}",
            )
        } catch (_: Exception) {
            // Nothing further to do: the diagnostic channel itself is failing.
        }
    }

    /**
     * Whether the theme in force for [mode] is dark. Light and dark are decided by the mode alone.
     *
     * Known limitation: for the system mode the answer comes from the application configuration's
     * night flag as it stands. No source for the system's own night state that the per-app setting
     * leaves alone was established, so a line written right after leaving a light or dark choice
     * for the system mode may still name the previous theme until the platform applies the change.
     * A line for the system mode at start is unaffected unless the platform copy was already set.
     */
    private fun effectiveDark(mode: ThemeMode): Boolean {
        val nightBits = appContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return isEffectivelyDark(mode, nightBits == Configuration.UI_MODE_NIGHT_YES)
    }
}
