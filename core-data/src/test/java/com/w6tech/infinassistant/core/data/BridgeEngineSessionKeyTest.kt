package com.w6tech.infinassistant.core.data

import com.w6tech.infinassistant.core.domain.BridgeSettings
import com.w6tech.infinassistant.core.domain.VoiceAssistSettings
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The connected-mode session restarts on a settings change, and a layout push writes two
 * settings itself (the saved copies of the layout). Those two must not restart the session:
 * the restart cancelled the push's own command before it could report success, so the screen
 * said "Watch did not respond" after the watch had applied the layout (seen on hardware with
 * firmware 2.4.63 and app 2.1.69).
 */
class BridgeEngineSessionKeyTest {

    private val base = BridgeSettings(connectedModeEnabled = true)

    @Test
    fun `saving the resolved layout after a push does not change the session key`() {
        val after = base.copy(lastResolvedWatchFaceLayoutJson = """{"version":3}""")
        assertEquals(BridgeEngine.sessionKey(base), BridgeEngine.sessionKey(after))
    }

    @Test
    fun `saving the pushed layout JSON does not change the session key`() {
        val after = base.copy(lastPushedWatchFaceJson = """{"screens":[]}""")
        assertEquals(BridgeEngine.sessionKey(base), BridgeEngine.sessionKey(after))
    }

    @Test
    fun `a replaced saved layout does not change the session key either`() {
        val first = base.copy(lastResolvedWatchFaceLayoutJson = "a", lastPushedWatchFaceJson = "b")
        val second = base.copy(lastResolvedWatchFaceLayoutJson = "c", lastPushedWatchFaceJson = "d")
        assertEquals(BridgeEngine.sessionKey(first), BridgeEngine.sessionKey(second))
    }

    @Test
    fun `switching voice assist on or changing its pipeline does not change the session key`() {
        val on = base.copy(voiceAssist = VoiceAssistSettings(enabled = true))
        val other = on.copy(voiceAssist = VoiceAssistSettings(enabled = true, pipelineId = "p2"))
        assertEquals(BridgeEngine.sessionKey(base), BridgeEngine.sessionKey(on))
        assertEquals(BridgeEngine.sessionKey(base), BridgeEngine.sessionKey(other))
    }

    @Test
    fun `a setting the session depends on still changes the key`() {
        assertNotEquals(BridgeEngine.sessionKey(base), BridgeEngine.sessionKey(base.copy(connectedModeEnabled = false)))
        assertNotEquals(
            BridgeEngine.sessionKey(base),
            BridgeEngine.sessionKey(base.copy(notificationEventType = "other_event")),
        )
        assertNotEquals(
            BridgeEngine.sessionKey(base),
            BridgeEngine.sessionKey(base.copy(associatedDeviceAddress = "AA:BB:CC:DD:EE:FF")),
        )
    }

    @Test
    fun `the engine's filter lets through only what restarts a session`() = runTest {
        val pushed = base.copy(lastResolvedWatchFaceLayoutJson = """{"version":3}""")
        val pushedAgain = pushed.copy(lastPushedWatchFaceJson = """{"screens":[]}""")
        val switchedOff = pushedAgain.copy(connectedModeEnabled = false)

        // The same operator BridgeEngine.start() applies before collectLatest.
        val seen = flowOf(base, pushed, pushedAgain, switchedOff)
            .distinctUntilChanged { old, new -> BridgeEngine.sessionKey(old) == BridgeEngine.sessionKey(new) }
            .toList()

        assertEquals(listOf(base, switchedOff), seen)
        // The session that starts next still gets the latest saved layout.
        assertEquals("""{"version":3}""", seen.last().lastResolvedWatchFaceLayoutJson)
    }

    @Test
    fun `each report interval is a different session, so the settings screen saves it once`() {
        // The slider passed through 27 values and saved each: 28 sessions in 22 s (bundle 2026-10-27).
        // The screen now saves on release; this pins why a save per position was a problem.
        val a = base.copy(infinassistant = base.infinassistant.copy(sensorReportIntervalSeconds = 60))
        val b = base.copy(infinassistant = base.infinassistant.copy(sensorReportIntervalSeconds = 70))
        assertNotEquals(BridgeEngine.sessionKey(a), BridgeEngine.sessionKey(b))
    }
}
