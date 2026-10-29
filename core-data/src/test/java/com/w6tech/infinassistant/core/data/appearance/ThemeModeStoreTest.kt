package com.w6tech.infinassistant.core.data.appearance

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ThemeModeStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var ioScope: CoroutineScope
    private lateinit var file: File
    private val logged = mutableListOf<String>()

    @Before
    fun setUp() {
        ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        file = File(folder.newFolder(), "appearance.preferences_pb")
    }

    @After
    fun tearDown() {
        ioScope.cancel()
    }

    private fun fileStore() = createThemeModeStoreForFile(file, { logged += it }, ioScope)

    /**
     * Hand-written in-memory DataStore. Every collection of [data] counts as one read attempt;
     * the first [failingReads] attempts fail with an I/O error. Past [RUNAWAY_LIMIT] attempts the
     * read parks forever so a store that retries without pause fails its bound instead of hanging.
     */
    private class FakeDataStore(
        private val failingReads: Int = 0,
        var failWrites: Boolean = false,
    ) : DataStore<Preferences> {
        var readAttempts = 0
            private set
        private val state = MutableStateFlow(emptyPreferences())

        override val data: Flow<Preferences> = flow {
            readAttempts++
            if (readAttempts > RUNAWAY_LIMIT) awaitCancellation()
            if (readAttempts <= failingReads) throw IOException("injected read failure")
            emitAll(state)
        }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            if (failWrites) throw IOException("injected write failure")
            val updated = transform(state.value)
            state.value = updated
            return updated
        }

        companion object {
            const val RUNAWAY_LIMIT = 1_000
        }
    }

    @Test
    fun freshStore_readsSystem() = runTest {
        assertEquals(ThemeMode.System, fileStore().mode.first())
    }

    @Test
    fun roundTrip_eachModeOverATemporaryFile() = runTest {
        val store = fileStore()
        for (mode in ThemeMode.entries) {
            store.setMode(mode)
            assertEquals(mode, store.mode.first())
        }
        assertTrue(logged.isEmpty())
    }

    @Test
    fun severalWritesInSuccession_leaveTheLast() = runTest {
        val store = fileStore()
        store.setMode(ThemeMode.Dark)
        store.setMode(ThemeMode.Light)
        store.setMode(ThemeMode.Dark)
        store.setMode(ThemeMode.Light)
        assertEquals(ThemeMode.Light, store.mode.first())
    }

    @Test
    fun a_fileOfInvalidBytes_yieldsSystemThenAWriteOfDarkReadsBack() = runTest {
        file.writeBytes(ByteArray(12) { 0xFF.toByte() })
        val store = fileStore()

        assertEquals(ThemeMode.System, store.mode.first())
        store.setMode(ThemeMode.Dark)
        assertEquals(ThemeMode.Dark, store.mode.first())
    }

    @Test
    fun b1_readFailsOnce_sameCollectorGetsSystemThenDarkAfterAWrite() = runTest {
        val fake = FakeDataStore(failingReads = 1)
        val store = ThemeModeStore(fake) { logged += it }
        val seen = mutableListOf<ThemeMode>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { store.mode.toList(seen) }

        runCurrent()
        assertEquals(listOf(ThemeMode.System), seen)
        assertTrue(collector.isActive)

        store.setMode(ThemeMode.Dark)
        runCurrent()

        assertEquals(listOf(ThemeMode.System, ThemeMode.Dark), seen)
        assertTrue(collector.isActive)
        collector.cancel()
    }

    @Test
    fun b2_readFailsOnEveryAttempt_staysWithinTheRetryBoundAndEnds() = runTest {
        val fake = FakeDataStore(failingReads = Int.MAX_VALUE)
        val store = ThemeModeStore(fake) { logged += it }
        val seen = mutableListOf<ThemeMode>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { store.mode.toList(seen) }

        runCurrent()
        advanceTimeBy(60_000L)
        runCurrent()

        val bound = 60_000L / READ_RETRY_DELAY_MS + 1
        assertEquals(listOf(ThemeMode.System), seen)
        assertTrue("read attempts ${fake.readAttempts} exceed bound $bound", fake.readAttempts <= bound)
        assertTrue("retries never happened", fake.readAttempts > 1)
        collector.cancel()
    }

    @Test
    fun c_failedWrite_doesNotThrowLogsOnceAndLeavesTheValue() = runTest {
        val fake = FakeDataStore()
        val store = ThemeModeStore(fake) { logged += it }
        store.setMode(ThemeMode.Light)

        fake.failWrites = true
        store.setMode(ThemeMode.Dark)

        assertEquals(1, logged.size)
        assertFalse(logged.single().isEmpty())
        assertEquals(ThemeMode.Light, store.mode.first())
    }
}
