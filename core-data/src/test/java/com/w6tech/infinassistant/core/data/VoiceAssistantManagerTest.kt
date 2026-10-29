package com.w6tech.infinassistant.core.data

import com.w6tech.infinassistant.core.common.AppResult
import com.w6tech.infinassistant.core.common.Logger
import com.w6tech.infinassistant.core.ha.AssistAuthException
import com.w6tech.infinassistant.core.ha.AssistGateway
import com.w6tech.infinassistant.core.ha.AssistRun
import com.w6tech.infinassistant.core.ha.AssistSession
import com.w6tech.infinassistant.core.ha.PipelineEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

/**
 * The voice conversation's state machine with a fake microphone, detector, player and HA: what the
 * watch is told at each step, and how a session ends (exit phrase, silence, cancel, error).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceAssistantManagerTest {

    private val logLines = mutableListOf<String>()
    private val logger = object : Logger {
        override fun d(tag: String, msg: String, tr: Throwable?) { logLines += "D/$tag: $msg" }
        override fun i(tag: String, msg: String, tr: Throwable?) { logLines += "I/$tag: $msg" }
        override fun w(tag: String, msg: String, tr: Throwable?) { logLines += "W/$tag: $msg" }
        override fun e(tag: String, msg: String, tr: Throwable?) { logLines += "E/$tag: $msg" }
    }

    // A chunk is 100 ms of audio. Its first byte says what the fake detector reports for it.
    private fun chunk(kind: Byte) = ByteArray(3200).also { it[0] = kind }
    private val speech = chunk(1)
    private val endOfSpeech = chunk(2)
    private val silence = chunk(0)

    private class FakeRecorder(val scripts: MutableList<Flow<ByteArray>>) : SpeechRecorder {
        var starts = 0
        var stops = 0
        override suspend fun startRecording(): Flow<ByteArray> {
            starts++
            return scripts.removeAt(0)
        }
        override fun stopRecording() { stops++ }
    }

    private class FakeDetector : SpeechDetector {
        var resets = 0
        override fun reset() { resets++ }
        override fun analyze(audioChunk: ByteArray) = when (audioChunk[0].toInt()) {
            1 -> VoiceActivityDetector.VadResult.Speaking
            2 -> VoiceActivityDetector.VadResult.SilenceAfterSpeech
            else -> VoiceActivityDetector.VadResult.InitialSilence
        }
    }

    private class FakePlayer(var grantFocus: Boolean = true) : SpeechPlayer {
        var focusHeld = false
        val played = mutableListOf<Pair<Int, String>>()
        var gate: CompletableDeferred<Unit>? = null
        var playFailure: Exception? = null
        override fun requestFocus(): Boolean { focusHeld = grantFocus; return grantFocus }
        override fun releaseFocus() { focusHeld = false }
        override suspend fun play(audio: ByteArray, mimeType: String) {
            played += audio.size to mimeType
            playFailure?.let { throw it }
            gate?.await()
        }
    }

    private class FakeRun(private val script: List<PipelineEvent>) : AssistRun {
        var audioChunks = 0
        var ended = false
        override fun sendAudio(pcm: ByteArray) { audioChunks++ }
        override fun endAudio() { ended = true }
        override val events: Flow<PipelineEvent> get() = flowOf(*script.toTypedArray())
    }

    private class FakeSession(override val baseUrl: String, val turns: MutableList<List<PipelineEvent>>) : AssistSession {
        val runs = mutableListOf<FakeRun>()
        val started = mutableListOf<Pair<String?, String?>>()
        var closed = false
        var startFailure: Exception? = null
        override suspend fun startRun(pipelineId: String?, conversationId: String?): AssistRun {
            startFailure?.let { throw it }
            started += pipelineId to conversationId
            return FakeRun(turns.removeAt(0)).also { runs += it }
        }
        override fun close() { closed = true }
    }

    private class FakeAssist(val turns: MutableList<List<PipelineEvent>>) : AssistGateway {
        var connects = mutableListOf<Pair<String, String>>()
        var connectFailure: Exception? = null
        var startFailure: Exception? = null
        var ttsFailure: Exception? = null
        val ttsFetches = mutableListOf<Triple<String, String, String>>()
        lateinit var session: FakeSession
        override suspend fun connect(baseUrl: String, token: String): AssistSession {
            connects += baseUrl to token
            connectFailure?.let { throw it }
            session = FakeSession(baseUrl, turns).also { it.startFailure = startFailure }
            return session
        }
        override suspend fun fetchTtsAudio(baseUrl: String, audioUrl: String, token: String): ByteArray {
            ttsFetches += Triple(baseUrl, audioUrl, token)
            ttsFailure?.let { throw it }
            return ByteArray(10)
        }
    }

    private fun answer(
        said: String,
        reply: String = "Done.",
        conversationId: String? = "conv-1",
        tts: Boolean = true,
    ): List<PipelineEvent> = buildList {
        add(PipelineEvent.SttEnd(said))
        add(PipelineEvent.IntentEnd(reply, conversationId))
        if (tts) add(PipelineEvent.TtsEnd("/api/tts_proxy/r.mp3", "audio/mpeg"))
        add(PipelineEvent.RunEnd)
    }

    /** A turn the speaker answers: some speech, then the detector sees the silence after it. */
    private fun utterance(): Flow<ByteArray> = flowOf(speech, speech, endOfSpeech)

    /** A microphone that hears nothing for as long as it is asked. */
    private fun nothing(): Flow<ByteArray> = flow { while (true) emit(silence) }

    private val request = VoiceSessionRequest("http://ha.local:8123", "tok", pipelineId = "p2")

    private class Setup(
        val recorder: FakeRecorder,
        val detector: FakeDetector,
        val player: FakePlayer,
        val assist: FakeAssist,
        val manager: VoiceAssistantManager,
        val states: MutableList<VoiceState>,
    )

    private var nowMs = 0L

    private fun setup(
        mic: List<Flow<ByteArray>>,
        turns: List<List<PipelineEvent>>,
        player: FakePlayer = FakePlayer(),
        guard: VoiceAuthGuard = VoiceAuthGuard(),
    ): Setup {
        val recorder = FakeRecorder(mic.toMutableList())
        val detector = FakeDetector()
        val assist = FakeAssist(turns.toMutableList())
        val manager = VoiceAssistantManager(logger, recorder, detector, player, assist, guard, clockMs = { nowMs })
        return Setup(recorder, detector, player, assist, manager, mutableListOf())
    }

    private suspend fun Setup.run(): AppResult<VoiceAssistResult> =
        manager.startSession(request) { state -> states += state }

    @Test
    fun `an exit phrase ends the conversation after its reply is spoken`() = runTest {
        val s = setup(
            mic = listOf(utterance(), utterance()),
            turns = listOf(answer("turn on the kitchen light"), answer("okay, thanks", reply = "You're welcome.")),
        )

        val result = s.run()

        assertTrue(result is AppResult.Ok)
        val done = (result as AppResult.Ok).value
        assertEquals(VoiceEndReason.EXIT_PHRASE, done.reason)
        assertEquals(2, done.turns)
        assertEquals("okay, thanks", done.transcript)
        assertEquals("You're welcome.", done.response)
        // Each turn: listening, processing, speaking. The exit reply is still spoken.
        assertEquals(
            listOf(
                VoiceState.Listening, VoiceState.Processing, VoiceState.Speaking,
                VoiceState.Listening, VoiceState.Processing, VoiceState.Speaking,
            ),
            s.states,
        )
        assertEquals(2, s.player.played.size)
        assertEquals("audio/mpeg", s.player.played[0].second)
        assertTrue(s.assist.session.closed)
        assertFalse(s.player.focusHeld)
        assertFalse(s.manager.isRunning)
        assertEquals(VoiceState.Idle, s.manager.state.value)
    }

    @Test
    fun `the conversation id and pipeline carry over to the next turn`() = runTest {
        val s = setup(
            mic = listOf(utterance(), utterance(), utterance()),
            turns = listOf(
                answer("what time is it", conversationId = "conv-A"),
                answer("and in Paris", conversationId = "conv-A"),
                answer("goodbye", conversationId = "conv-A"),
            ),
        )

        s.run()

        assertEquals(
            listOf<Pair<String?, String?>>("p2" to null, "p2" to "conv-A", "p2" to "conv-A"),
            s.assist.session.started,
        )
        assertEquals("one socket for the whole conversation", 1, s.assist.connects.size)
    }

    @Test
    fun `the reply is fetched from the address the session used with the token`() = runTest {
        val s = setup(mic = listOf(utterance()), turns = listOf(answer("thank you")))

        s.run()

        assertEquals(listOf(Triple("http://ha.local:8123", "/api/tts_proxy/r.mp3", "tok")), s.assist.ttsFetches)
        assertEquals(listOf("http://ha.local:8123" to "tok"), s.assist.connects)
    }

    @Test
    fun `audio is streamed to the run while recording and the end is signalled once`() = runTest {
        val s = setup(mic = listOf(utterance()), turns = listOf(answer("thanks")))

        s.run()

        val run = s.assist.session.runs.single()
        assertEquals(3, run.audioChunks)
        assertTrue(run.ended)
    }

    @Test
    fun `three seconds of silence at the start ends the conversation without a failure`() = runTest {
        val s = setup(mic = listOf(nothing()), turns = listOf(answer("never used")))

        val result = s.run()

        val done = (result as AppResult.Ok).value
        assertEquals(VoiceEndReason.NO_SPEECH, done.reason)
        assertEquals(0, done.turns)
        assertEquals(listOf<VoiceState>(VoiceState.Listening), s.states)
        assertTrue("the run is told the audio ended, so HA does not wait for more", s.assist.session.runs.single().ended)
        assertTrue(s.assist.session.closed)
        assertTrue(s.recorder.stops >= 1)
    }

    @Test
    fun `silence after an answered turn ends the conversation`() = runTest {
        val s = setup(
            mic = listOf(utterance(), nothing()),
            turns = listOf(answer("how warm is it"), answer("never used")),
        )

        val done = (s.run() as AppResult.Ok).value

        assertEquals(VoiceEndReason.NO_SPEECH, done.reason)
        assertEquals(1, done.turns)
    }

    @Test
    fun `HA hearing no words ends the conversation quietly`() = runTest {
        val s = setup(
            mic = listOf(utterance()),
            turns = listOf(listOf(PipelineEvent.Error("stt-no-text-recognized", "No text recognized"))),
        )

        val done = (s.run() as AppResult.Ok).value

        assertEquals(VoiceEndReason.NO_SPEECH, done.reason)
    }

    @Test
    fun `a pipeline error fails the session with HA's message`() = runTest {
        val s = setup(
            mic = listOf(utterance()),
            turns = listOf(listOf(PipelineEvent.Error("intent-failed", "The model is unavailable"))),
        )

        val result = s.run()

        assertEquals("The model is unavailable", (result as AppResult.Err).error.message)
        assertTrue(s.assist.session.closed)
        assertFalse(s.player.focusHeld)
        assertFalse(s.manager.isRunning)
    }

    @Test
    fun `a failed reply fetch fails the session`() = runTest {
        val s = setup(mic = listOf(utterance()), turns = listOf(answer("hello")))
        s.assist.ttsFailure = IOException("Failed to fetch TTS audio: 404")

        val result = s.run()

        assertEquals("Reply audio failed", (result as AppResult.Err).error.message)
    }

    @Test
    fun `a run HA does not accept fails the session`() = runTest {
        val s = setup(mic = listOf(utterance()), turns = listOf(answer("hello")))
        s.assist.startFailure = IOException("pipeline-not-found: Pipeline not found")

        val result = s.run()

        assertTrue((result as AppResult.Err).error.message!!.contains("Pipeline not found"))
        assertEquals("the microphone never opened", 0, s.recorder.starts)
        assertTrue(s.assist.session.closed)
    }

    @Test
    fun `an HA that refuses the token fails the session as an auth failure`() = runTest {
        val s = setup(mic = emptyList(), turns = emptyList())
        s.assist.connectFailure = AssistAuthException()

        val result = s.run()

        assertEquals("HA auth failed", (result as AppResult.Err).error.message)
        assertEquals(0, s.recorder.starts)
        assertFalse(s.player.focusHeld)
    }

    @Test
    fun `an unreachable HA fails the session as offline`() = runTest {
        val s = setup(mic = emptyList(), turns = emptyList())
        s.assist.connectFailure = UnknownHostException("homeassistant.local")

        val result = s.run()

        assertEquals("HA offline", (result as AppResult.Err).error.message)
    }

    @Test
    fun `another app holding audio focus fails the session before HA is contacted`() = runTest {
        val s = setup(mic = emptyList(), turns = emptyList(), player = FakePlayer(grantFocus = false))

        val result = s.run()

        assertEquals("Audio busy", (result as AppResult.Err).error.message)
        assertTrue(s.assist.connects.isEmpty())
    }

    @Test
    fun `a microphone that cannot start fails the session`() = runTest {
        val failing = flow<ByteArray> { throw SecurityException("no microphone type") }
        val s = setup(mic = listOf(failing), turns = listOf(answer("unused")))

        val result = s.run()

        assertEquals("Microphone unavailable", (result as AppResult.Err).error.message)
        assertTrue(s.assist.session.closed)
    }

    @Test
    fun `cancelling the caller stops the recording and releases everything`() = runTest {
        val s = setup(mic = listOf(flow { emit(speech); awaitCancellation() }), turns = listOf(answer("unused")))

        val job = launch { s.run() }
        advanceUntilIdle()
        assertTrue(s.manager.isRunning)
        job.cancelAndJoin()

        assertFalse(s.manager.isRunning)
        assertTrue(s.assist.session.closed)
        assertFalse(s.player.focusHeld)
        assertTrue(s.recorder.stops >= 1)
        assertTrue(logLines.any { it.contains("session end: cancelled") })
    }

    @Test
    fun `cancelSession ends a running session`() = runTest {
        val s = setup(mic = listOf(flow { emit(speech); awaitCancellation() }), turns = listOf(answer("unused")))

        val job = launch { s.run() }
        advanceUntilIdle()
        s.manager.cancelSession()
        job.join()

        assertTrue("the session's job was cancelled", job.isCancelled)
        assertFalse(s.manager.isRunning)
        assertTrue(s.assist.session.closed)
    }

    @Test
    fun `a cancel while the reply plays stops it`() = runTest {
        val player = FakePlayer().also { it.gate = CompletableDeferred() }
        val s = setup(mic = listOf(utterance()), turns = listOf(answer("hello")), player = player)

        val job = launch { s.run() }
        advanceUntilIdle()
        assertEquals(VoiceState.Speaking, s.states.last())
        s.manager.cancelSession()
        job.join()

        assertTrue(job.isCancelled)
        assertFalse(s.player.focusHeld)
    }

    @Test
    fun `a second start while a session runs is refused and leaves the first alone`() = runTest {
        val s = setup(mic = listOf(flow { emit(speech); awaitCancellation() }), turns = listOf(answer("unused")))

        val first = async { s.run() }
        advanceUntilIdle()
        val second = s.run()

        assertEquals("Session already in progress", (second as AppResult.Err).error.message)
        assertTrue(s.manager.isRunning)
        assertEquals(1, s.assist.connects.size)
        first.cancelAndJoin()
        assertFalse(s.manager.isRunning)
    }

    @Test
    fun `a session can start again after one ended`() = runTest {
        val s = setup(
            mic = listOf(utterance(), utterance()),
            turns = listOf(answer("thanks"), answer("goodbye")),
        )

        val first = s.run()
        val second = s.run()

        assertTrue(first is AppResult.Ok)
        assertTrue(second is AppResult.Ok)
        assertEquals(2, s.assist.connects.size)
    }

    @Test
    fun `a listener that fails does not end the conversation`() = runTest {
        val s = setup(mic = listOf(utterance()), turns = listOf(answer("thanks")))

        val result = s.manager.startSession(request) { throw IOException("watch link lost") }

        assertTrue(result is AppResult.Ok)
    }

    @Test
    fun `what was said stays out of the diagnostic lines`() = runTest {
        val s = setup(
            mic = listOf(utterance(), utterance()),
            turns = listOf(answer("my secret code is 1234", reply = "Noted, 1234."), answer("thanks")),
        )

        s.run()

        assertTrue(logLines.any { it.startsWith("I/VoiceAssist: session start") })
        assertTrue(logLines.any { it.startsWith("I/VoiceAssist: turn 1") })
        assertTrue(logLines.any { it.startsWith("I/VoiceAssist: session end") })
        assertTrue(logLines.none { it.contains("1234") || it.contains("secret") })
    }

    @Test
    fun `a conversation ends as done after six turns`() = runTest {
        val s = setup(
            mic = List(7) { utterance() },
            turns = List(7) { answer("tell me more") },
        )

        val done = (s.run() as AppResult.Ok).value

        assertEquals(VoiceEndReason.LIMIT, done.reason)
        assertEquals(VoiceAssistantManager.MAX_TURNS, done.turns)
        assertEquals("the sixth reply is still spoken", 6, s.player.played.size)
        assertEquals(6, s.recorder.starts)
    }

    @Test
    fun `a conversation ends as done after three minutes`() = runTest {
        val s = setup(
            mic = List(5) { utterance() },
            turns = List(5) { answer("tell me more") },
        )
        // 100 s pass during each answered turn.
        val timed = object : AssistGateway by s.assist {
            override suspend fun fetchTtsAudio(baseUrl: String, audioUrl: String, token: String): ByteArray {
                nowMs += 100_000
                return s.assist.fetchTtsAudio(baseUrl, audioUrl, token)
            }
        }
        val manager = VoiceAssistantManager(logger, s.recorder, s.detector, s.player, timed, clockMs = { nowMs })

        val done = (manager.startSession(request) { } as AppResult.Ok).value

        assertEquals(VoiceEndReason.LIMIT, done.reason)
        assertEquals("100 s, then 200 s is past the limit", 2, done.turns)
    }

    @Test
    fun `a token HA refused is not tried again until it changes`() = runTest {
        val guard = VoiceAuthGuard()
        val s = setup(mic = listOf(utterance()), turns = listOf(answer("thanks")), guard = guard)
        s.assist.connectFailure = AssistAuthException()

        val first = s.run()
        val second = s.run()
        val third = s.run()

        assertEquals("HA auth failed", (first as AppResult.Err).error.message)
        assertEquals(VoiceAuthGuard.REJECTED_MESSAGE, (second as AppResult.Err).error.message)
        assertEquals(VoiceAuthGuard.REJECTED_MESSAGE, (third as AppResult.Err).error.message)
        assertEquals("only the first reached HA", 1, s.assist.connects.size)

        // The owner enters a new token: it is tried.
        s.assist.connectFailure = null
        val fresh = s.manager.startSession(request.copy(token = "new-token")) { }
        assertTrue(fresh is AppResult.Ok)
        assertEquals(2, s.assist.connects.size)
    }

    @Test
    fun `a reply cut short by another app's audio fails the turn`() = runTest {
        val player = FakePlayer().also { it.playFailure = SpeechInterruptedException() }
        val s = setup(mic = listOf(utterance()), turns = listOf(answer("hello")), player = player)

        val result = s.run()

        assertEquals("Audio interrupted", (result as AppResult.Err).error.message)
    }

    @Test
    fun `a reply that fails to play fails the turn`() = runTest {
        val player = FakePlayer().also { it.playFailure = IOException("MediaPlayer error 1/-1004") }
        val s = setup(mic = listOf(utterance()), turns = listOf(answer("hello")), player = player)

        val result = s.run()

        assertEquals("Reply audio failed", (result as AppResult.Err).error.message)
    }

    @Test
    fun `a pipeline error logs only its code above debug`() = runTest {
        val s = setup(
            mic = listOf(utterance()),
            turns = listOf(listOf(PipelineEvent.Error("intent-failed", "Could not turn on 192.168.1.9 for Alice"))),
        )

        s.run()

        val teed = logLines.filter { !it.startsWith("D/") }
        assertTrue(teed.any { it.contains("intent-failed") })
        assertTrue("HA's wording stays at debug: $teed", teed.none { it.contains("Alice") || it.contains("192.168") })
        assertTrue(logLines.any { it.startsWith("D/") && it.contains("Alice") })
    }

    @Test
    fun `exit phrases match whole words only`() {
        assertTrue(isExitPhrase("thanks"))
        assertTrue(isExitPhrase("Okay, thank you!"))
        assertTrue(isExitPhrase("that’s all"))
        assertTrue(isExitPhrase("never mind"))
        assertTrue(isExitPhrase("I'm done"))
        assertFalse(isExitPhrase("turn on the kitchen light"))
        assertFalse("done inside another word", isExitPhrase("the abandoned house"))
        assertFalse(isExitPhrase(""))
    }
}
