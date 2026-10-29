package com.w6tech.infinassistant.core.data.appearance

import android.content.Context
import com.w6tech.infinassistant.core.data.DataStoreSettingsRepository
import com.w6tech.infinassistant.core.data.HaTokenStore
import com.w6tech.infinassistant.core.domain.BridgeSettings
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Writing the theme mode must not make the bridge's settings stream emit. The production
 * settings repository and the mode store are both live, each over its own file.
 *
 * This class holds exactly one test method that builds the production repository: the repository
 * reaches its store through a file-level delegate on `Context`, which keeps one store for the life
 * of the process, so a second method could be handed a store bound to the first method's directory.
 */
class ThemeModeIsolationTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var ioScope: CoroutineScope

    @Before
    fun setUp() {
        ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        ioScope.cancel()
    }

    @Test
    fun modeWrites_doNotEmitOnTheSettingsStream() = runTest {
        val filesDir = folder.newFolder("files")
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.filesDir } returns filesDir
        val tokenStore = mockk<HaTokenStore>()
        every { tokenStore.getToken() } returns null

        val repository = DataStoreSettingsRepository(context, tokenStore)
        val modeStore = createThemeModeStoreForFile(
            File(folder.newFolder("appearance"), "appearance.preferences_pb"),
            { },
            ioScope,
        )

        val seen = CopyOnWriteArrayList<BridgeSettings>()
        ioScope.launch { repository.settings.toList(seen) }

        awaitCount(seen, 1)
        val before = seen.size

        for (mode in listOf(ThemeMode.Dark, ThemeMode.Light, ThemeMode.System)) {
            modeStore.setMode(mode)
            assertEquals(mode, modeStore.mode.first())
        }

        assertEquals(before, seen.size)

        // Control: a real settings write must reach the same collector, and it must be the very
        // next value. The stream is ordered, so an emission caused by a mode write would have
        // arrived ahead of it and shifted the count.
        repository.setConnectedModeEnabled(true)
        awaitCount(seen, before + 1)
        assertEquals(before + 1, seen.size)
        assertTrue(seen.last().connectedModeEnabled)
    }

    private suspend fun awaitCount(seen: List<BridgeSettings>, count: Int) {
        withContext(Dispatchers.Default) {
            withTimeout(WAIT_MS) {
                while (seen.size < count) delay(POLL_MS)
            }
        }
    }

    private companion object {
        const val WAIT_MS = 15_000L
        const val POLL_MS = 10L
    }
}
