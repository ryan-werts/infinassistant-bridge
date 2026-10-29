package com.w6tech.infinassistant.core.data.appearance

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/** Name of the preferences file that holds only the appearance mode. */
internal const val THEME_MODE_STORE_NAME = "appearance"

/**
 * Upper bound on how soon a failed read is attempted again when no write has succeeded in
 * between. A persistent read error therefore costs at most one attempt per this many
 * milliseconds.
 */
internal const val READ_RETRY_DELAY_MS = 5_000L

private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")

/**
 * Persists the user's [ThemeMode] in a preferences file of its own.
 *
 * The constructor is internal so that no public signature names a DataStore type; use
 * [createThemeModeStore] (production) or [createThemeModeStoreForFile] (tests).
 */
class ThemeModeStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val log: (String) -> Unit,
) {
    // Wakes a collector that is waiting to retry a failed read as soon as a write succeeds.
    private val writeSucceeded = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * The stored mode. A read error is reported to the collector as [ThemeMode.System]; the
     * flow stays subscribed and reads again after a successful write or after
     * [READ_RETRY_DELAY_MS], whichever comes first. Reading never writes.
     */
    val mode: Flow<ThemeMode> = flow {
        while (true) {
            var readFailed = false
            readOnce().collect { value ->
                if (value == null) {
                    readFailed = true
                    emit(ThemeMode.System)
                } else {
                    emit(value)
                }
            }
            if (!readFailed) return@flow
            withTimeoutOrNull(READ_RETRY_DELAY_MS) { writeSucceeded.first() }
        }
    }.distinctUntilChanged()

    // A failed read ends this flow with a single null instead of an exception.
    private fun readOnce(): Flow<ThemeMode?> = dataStore.data
        .map<Preferences, ThemeMode?> { parseThemeMode(it[THEME_MODE_KEY]) }
        .catch { emit(null) }

    /** Stores [mode]. Never throws; a failed write is logged once and leaves the stored value as it was. */
    suspend fun setMode(mode: ThemeMode) {
        try {
            dataStore.edit { it[THEME_MODE_KEY] = encodeThemeMode(mode) }
            writeSucceeded.tryEmit(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("appearance mode write failed: ${e.javaClass.simpleName}")
        }
    }
}

/** Production factory. The file is private to the app and a corrupt file is replaced by empty preferences. */
fun createThemeModeStore(context: Context, log: (String) -> Unit): ThemeModeStore =
    createThemeModeStoreForFile(
        file = context.applicationContext.preferencesDataStoreFile(THEME_MODE_STORE_NAME),
        log = log,
    )

/** Factory over a plain file, for tests. A corrupt file is replaced by empty preferences. */
fun createThemeModeStoreForFile(
    file: File,
    log: (String) -> Unit,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
): ThemeModeStore = ThemeModeStore(
    dataStore = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = scope,
        produceFile = { file },
    ),
    log = log,
)
