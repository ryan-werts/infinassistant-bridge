package com.w6tech.infinassistant.core.data

import com.w6tech.infinassistant.core.common.AppResult
import com.w6tech.infinassistant.core.common.Logger
import com.w6tech.infinassistant.core.ha.AssistAuthException
import com.w6tech.infinassistant.core.ha.AssistGateway
import com.w6tech.infinassistant.core.ha.AssistSession
import com.w6tech.infinassistant.core.ha.PipelineEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Current state of the voice assistant session.
 */
sealed interface VoiceState {
    /** No session is active. */
    data object Idle : VoiceState

    /** Recording user's voice input. */
    data object Listening : VoiceState

    /** Processing the recorded audio through Home Assistant Assist pipeline. */
    data object Processing : VoiceState

    /** Playing TTS response. */
    data object Speaking : VoiceState

    /**
     * An error occurred during the session.
     * @param message Human-readable error description.
     * @param isNetworkError True if the error is network-related.
     */
    data class Error(val message: String, val isNetworkError: Boolean) : VoiceState
}

/** Why a voice session ended without a failure. */
enum class VoiceEndReason(val logText: String) {
    /** The owner said an exit phrase. */
    EXIT_PHRASE("exit phrase"),

    /** Nothing was said for [VoiceAssistantManager.INITIAL_SILENCE_TIMEOUT_MS] at a turn's start, or HA heard no words. */
    NO_SPEECH("no speech"),

    /** The conversation reached [VoiceAssistantManager.MAX_TURNS] turns or [VoiceAssistantManager.MAX_SESSION_MS]. */
    LIMIT("limit"),
}

/**
 * Result of a voice assistant session that ended without a failure.
 *
 * @param reason What ended it.
 * @param turns How many turns got an answer.
 * @param transcript The last recognized speech text (if any).
 * @param response The assistant's last response text (if any).
 */
data class VoiceAssistResult(
    val reason: VoiceEndReason,
    val turns: Int,
    val transcript: String? = null,
    val response: String? = null,
)

/** What a voice session needs to start. */
data class VoiceSessionRequest(
    /** The HA base URL URL selection chose: the session's websocket and its TTS fetch both use it. */
    val baseUrl: String,
    val token: String,
    /** The Assist pipeline to run; null for HA's preferred one. */
    val pipelineId: String? = null,
)

/** Told, in order, as a session moves between its phases. */
fun interface VoiceSessionListener {
    /** Called with [VoiceState.Listening], [VoiceState.Processing] and [VoiceState.Speaking], each turn. */
    suspend fun onState(state: VoiceState)
}

/** The microphone as a stream of 16 kHz mono PCM16 chunks; [AudioRecorder] is the real one. */
interface SpeechRecorder {
    suspend fun startRecording(): Flow<ByteArray>
    fun stopRecording()
}

/** Decides from audio chunks when the speaker has started and finished; [VoiceActivityDetector] is the real one. */
interface SpeechDetector {
    fun reset()
    fun analyze(audioChunk: ByteArray): VoiceActivityDetector.VadResult
}

/** Plays the assistant's reply and holds audio focus around a session; [MediaSpeechPlayer] is the real one. */
interface SpeechPlayer {
    /** Takes audio focus, which pauses other audio. False when another app holds it. */
    fun requestFocus(): Boolean
    fun releaseFocus()

    /** Plays [audio] to its end. [mimeType] is the type HA gave. */
    suspend fun play(audio: ByteArray, mimeType: String)
}

/** The reply was cut short because another app took audio focus (a call, other audio). */
class SpeechInterruptedException : IOException("Audio focus lost")

/**
 * Exit phrases that end the conversation.
 */
private val exitPhrases = listOf(
    "thanks", "thank you", "goodbye", "that's all", "done", "never mind"
)

/** One exit-phrase pattern: a phrase counts as whole words, so "abandoned" is not "done". */
private val exitPattern = Regex(
    exitPhrases.joinToString("|", prefix = "\\b(?:", postfix = ")\\b") { Regex.escape(it) },
)

/**
 * Whether [transcript] ends the conversation: it holds one of the exit phrases as whole words.
 * A typographic apostrophe is read as a plain one, since speech-to-text writes "that's all" both ways.
 */
internal fun isExitPhrase(transcript: String): Boolean =
    exitPattern.containsMatchIn(transcript.lowercase().replace('’', '\''))

/**
 * Runs a voice conversation with Home Assistant Assist, one session per double-click on the watch:
 * 1. Take audio focus (pauses other audio like podcasts)
 * 2. Open one websocket to HA for the whole conversation
 * 3. Each turn: start a run, record until the speaker stops while streaming the audio to the run,
 *    read the run's events, play the spoken reply
 * 4. Stop on an exit phrase, on [INITIAL_SILENCE_TIMEOUT_MS] of silence at a turn's start, on a
 *    cancel, or on an error
 * 5. Close the socket and release audio focus
 *
 * HA keeps the context between turns through the conversation id its first answer carries.
 * One session runs at a time; a second [startSession] is refused.
 *
 * Session steps reach the debug bundle through the INFO events of [DiagnosticTeeLogger] (tag
 * [TAG]): what was said stays out of them, only its length goes in.
 */
class VoiceAssistantManager(
    private val logger: Logger,
    private val recorder: SpeechRecorder,
    private val detector: SpeechDetector,
    private val player: SpeechPlayer,
    private val assist: AssistGateway,
    /** Tells that HA refused the token, so voice stops asking with it; shared with the pipeline picker. */
    val authGuard: VoiceAuthGuard = VoiceAuthGuard(),
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val TAG = "VoiceAssist"

        /** Timeout for initial silence before ending conversation (milliseconds). */
        const val INITIAL_SILENCE_TIMEOUT_MS = 3000L

        /** A conversation ends, as done, after this many answered turns... */
        const val MAX_TURNS = 6

        /** ...or this long, so a phone left talking to itself or a stuck loop stops. */
        const val MAX_SESSION_MS = 3 * 60_000L

        /** HA's code for audio it found no words in: the speaker said nothing, which is not a failure. */
        private const val NO_TEXT_CODE = "stt-no-text-recognized"

        /** 16 kHz mono PCM16 is 32 bytes per millisecond. */
        private const val PCM_BYTES_PER_MS = 32L
    }

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)

    /**
     * Current state of the voice assistant session.
     * Observe this flow to update UI based on session progress.
     */
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val running = AtomicBoolean(false)
    @Volatile private var sessionJob: Job? = null

    /** True from a session's start until it has cleaned up. */
    val isRunning: Boolean get() = running.get()

    private sealed interface TurnOutcome {
        data class Answered(val transcript: String?, val response: String?, val exit: Boolean) : TurnOutcome
        data object Silent : TurnOutcome
        /** [logReason] is what the diagnostic log gets: never HA's own wording. */
        data class Failed(val message: String, val network: Boolean, val logReason: String = message) : TurnOutcome
    }

    /**
     * Runs a conversation until it ends. Suspends for its whole length; cancel the calling
     * coroutine, or call [cancelSession], to stop it from outside.
     *
     * @return [AppResult.Ok] when the conversation ended by itself (an exit phrase, or silence),
     *   [AppResult.Err] with a short reason when it failed or another session is running.
     * @throws CancellationException When cancelled.
     */
    suspend fun startSession(request: VoiceSessionRequest, listener: VoiceSessionListener): AppResult<VoiceAssistResult> {
        if (!running.compareAndSet(false, true)) {
            return AppResult.Err(IllegalStateException("Session already in progress"))
        }
        sessionJob = currentCoroutineContext()[Job]
        _state.value = VoiceState.Idle

        var session: AssistSession? = null
        var turns = 0
        var lastTranscript: String? = null
        var lastResponse: String? = null
        var conversationId: String? = null
        val startedAtMs = clockMs()
        try {
            logger.i(TAG, "session start: pipeline=${request.pipelineId ?: "preferred"}")

            // HA already refused this token: asking again only moves the address toward a ban.
            if (authGuard.isRejected(request.token)) {
                return failed(VoiceAuthGuard.REJECTED_MESSAGE, network = false, turns = turns)
            }

            if (!player.requestFocus()) return failed("Audio busy", network = false, turns = turns)

            session = try {
                assist.connect(request.baseUrl, request.token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is AssistAuthException) authGuard.recordRejection(request.token)
                return failed(categorizeError(e), isNetworkError(e), turns, e)
            }

            while (true) {
                val turn = turns + 1
                when (val outcome = runTurn(session, request, conversationId, turn, listener) { conversationId = it }) {
                    is TurnOutcome.Failed -> return failed(outcome.message, outcome.network, turns, logReason = outcome.logReason)
                    TurnOutcome.Silent -> {
                        logger.i(TAG, "session end: no speech after $turns turns")
                        return AppResult.Ok(VoiceAssistResult(VoiceEndReason.NO_SPEECH, turns, lastTranscript, lastResponse))
                    }
                    is TurnOutcome.Answered -> {
                        turns = turn
                        lastTranscript = outcome.transcript
                        lastResponse = outcome.response
                        if (outcome.exit) {
                            logger.i(TAG, "session end: exit phrase after $turns turns")
                            return AppResult.Ok(VoiceAssistResult(VoiceEndReason.EXIT_PHRASE, turns, lastTranscript, lastResponse))
                        }
                        if (turns >= MAX_TURNS || clockMs() - startedAtMs >= MAX_SESSION_MS) {
                            logger.i(TAG, "session end: limit after $turns turns")
                            return AppResult.Ok(VoiceAssistResult(VoiceEndReason.LIMIT, turns, lastTranscript, lastResponse))
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            logger.i(TAG, "session end: cancelled after $turns turns")
            throw e
        } catch (e: Exception) {
            return failed(e.message?.take(50) ?: "Session error", network = false, turns = turns, cause = e)
        } finally {
            session?.close()
            recorder.stopRecording()
            player.releaseFocus()
            sessionJob = null
            _state.value = VoiceState.Idle
            running.set(false)
        }
    }

    /** Ends the running session, if any: its [startSession] throws a cancellation. */
    fun cancelSession() {
        logger.d(TAG, "Cancelling session")
        sessionJob?.cancel()
    }

    private fun failed(
        message: String,
        network: Boolean,
        turns: Int,
        cause: Throwable? = null,
        logReason: String = message,
    ): AppResult.Err {
        logger.w(TAG, "session end: failed after $turns turns: $logReason", cause)
        _state.value = VoiceState.Error(message, network)
        return AppResult.Err(cause?.let { IOException(message, it) } ?: IOException(message))
    }

    /**
     * One turn: a run on [session], the speaker's utterance streamed to it as it is recorded, then
     * the run's events and the spoken reply.
     */
    private suspend fun runTurn(
        session: AssistSession,
        request: VoiceSessionRequest,
        conversationId: String?,
        turn: Int,
        listener: VoiceSessionListener,
        onConversationId: (String) -> Unit,
    ): TurnOutcome {
        val run = try {
            session.startRun(request.pipelineId, conversationId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "turn $turn: run did not start: ${e.javaClass.simpleName}")
            logger.d(TAG, "turn $turn: run did not start: ${e.message}")
            return TurnOutcome.Failed(categorizeError(e), isNetworkError(e), logReason = "run did not start")
        }

        // The microphone opens once HA can take audio, and "listening" goes out then: it is the
        // watch's cue to speak.
        enter(VoiceState.Listening, listener)
        detector.reset()
        var heardSpeech = false
        var audioMs = 0L
        try {
            recorder.startRecording().takeWhile { chunk ->
                run.sendAudio(chunk)
                audioMs += chunk.size / PCM_BYTES_PER_MS
                when (detector.analyze(chunk)) {
                    VoiceActivityDetector.VadResult.Speaking -> {
                        heardSpeech = true
                        true
                    }
                    VoiceActivityDetector.VadResult.InitialSilence -> audioMs < INITIAL_SILENCE_TIMEOUT_MS
                    VoiceActivityDetector.VadResult.SilenceAfterSpeech,
                    VoiceActivityDetector.VadResult.MaxDurationReached -> false
                }
            }.collect { /* takeWhile handles termination */ }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A missing permission or a foreground service without the microphone type ends up here.
            logger.w(TAG, "turn $turn: recording failed: ${e.message}")
            return TurnOutcome.Failed("Microphone unavailable", network = false)
        } finally {
            recorder.stopRecording()
        }

        if (!heardSpeech) {
            logger.i(TAG, "turn $turn: no speech in ${audioMs / 1000} s")
            run.endAudio()
            return TurnOutcome.Silent
        }

        run.endAudio()
        enter(VoiceState.Processing, listener)

        var transcript: String? = null
        var response: String? = null
        var tts: PipelineEvent.TtsEnd? = null
        var error: PipelineEvent.Error? = null
        run.events.collect { event ->
            when (event) {
                is PipelineEvent.SttEnd -> transcript = event.transcript
                is PipelineEvent.IntentEnd -> {
                    response = event.response
                    event.conversationId?.let(onConversationId)
                }
                is PipelineEvent.TtsEnd -> tts = event
                is PipelineEvent.Error -> error = event
                PipelineEvent.RunEnd -> Unit
            }
        }

        error?.let { e ->
            if (e.code == NO_TEXT_CODE) {
                logger.i(TAG, "turn $turn: HA heard no words")
                return TurnOutcome.Silent
            }
            // HA's wording can carry what was said or its own addresses: the code goes to the log, the text only to debug.
            logger.w(TAG, "turn $turn: pipeline error ${e.code}")
            logger.d(TAG, "turn $turn: pipeline error text: ${e.message}")
            return TurnOutcome.Failed(
                e.message.take(50).ifBlank { e.code },
                network = e.code == "timeout" || e.code == "connection_lost",
                logReason = "pipeline error ${e.code}",
            )
        }

        logger.i(TAG, "turn $turn: heard ${transcript?.length ?: 0} chars, reply ${response?.length ?: 0} chars, tts=${tts != null}")

        tts?.takeIf { it.audioUrl.isNotBlank() }?.let { reply ->
            enter(VoiceState.Speaking, listener)
            try {
                val audio = assist.fetchTtsAudio(session.baseUrl, reply.audioUrl, request.token)
                player.play(audio, reply.mimeType)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(TAG, "turn $turn: reply audio failed: ${e.javaClass.simpleName}")
                logger.d(TAG, "turn $turn: reply audio failed: ${e.message}")
                val message = if (e is SpeechInterruptedException) "Audio interrupted" else "Reply audio failed"
                return TurnOutcome.Failed(message, isNetworkError(e), logReason = message)
            }
        }

        return TurnOutcome.Answered(transcript, response, exit = transcript?.let(::isExitPhrase) == true)
    }

    private suspend fun enter(state: VoiceState, listener: VoiceSessionListener) {
        _state.value = state
        try {
            listener.onState(state)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A watch that cannot be told must not end the conversation.
            logger.w(TAG, "listener failed for $state: ${e.message}")
        }
    }

    /**
     * Determines if an exception is network-related.
     */
    private fun isNetworkError(e: Throwable): Boolean {
        return when (e) {
            is SocketTimeoutException -> true
            is UnknownHostException -> true
            is IOException -> e.message?.contains("timeout", ignoreCase = true) == true ||
                    e.message?.contains("connect", ignoreCase = true) == true ||
                    e.message?.contains("network", ignoreCase = true) == true ||
                    e.message?.contains("lost", ignoreCase = true) == true
            else -> {
                val cause = e.cause
                if (cause != null) isNetworkError(cause) else false
            }
        }
    }

    /**
     * Provides user-friendly error messages based on exception type.
     */
    private fun categorizeError(e: Throwable): String {
        return when {
            e is AssistAuthException -> "HA auth failed"
            e is SocketTimeoutException -> "Request timed out"
            e is UnknownHostException -> "HA offline"
            e.message?.contains("authentication", ignoreCase = true) == true -> "HA auth failed"
            e.message?.contains("connection", ignoreCase = true) == true -> "HA offline"
            e.message?.contains("timeout", ignoreCase = true) == true -> "Request timed out"
            e.message?.contains("timed out", ignoreCase = true) == true -> "Request timed out"
            isNetworkError(e) -> "Network error"
            else -> e.message?.take(50) ?: "Unknown error"
        }
    }
}
