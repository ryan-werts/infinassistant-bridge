package com.w6tech.infinassistant.core.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import com.w6tech.infinassistant.core.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Plays the assistant's spoken reply through the phone and holds audio focus around a voice
 * session. The audio is tagged as assistant speech, so the system routes and ducks it as such
 * (a call or a navigation prompt is not talked over, a Bluetooth headset gets it).
 *
 * The framework classes this builds (AudioFocusRequest, MediaPlayer) cannot be created in JVM unit
 * tests, so this class has no unit test: the manager's tests use a fake [SpeechPlayer], and the
 * behaviour here is checked on the phone. One rule was learned from that: an AudioFocusRequest
 * built with setWillPauseWhenDucked needs a listener, or build() throws and no session starts.
 */
class MediaSpeechPlayer(
    private val context: Context,
    private val audioManager: AudioManager,
    private val logger: Logger,
) : SpeechPlayer {
    private val tag = "VoiceAssist"

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var focusRequest: AudioFocusRequest? = null

    /** Ends the reply now, called by the focus listener; set while a reply plays. */
    @Volatile private var interrupt: (() -> Unit)? = null

    init {
        // A reply file a killed process left behind.
        context.cacheDir.listFiles { f -> f.name.startsWith("tts_") }?.forEach { it.delete() }
    }

    // Another app taking focus for good (a call, music the owner started) or for a moment ends the
    // reply: it is not spoken over them, and the turn fails rather than carrying on.
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            logger.d(tag, "Audio focus lost ($change)")
            interrupt?.invoke()
        }
    }

    /**
     * Requests exclusive transient audio focus. It pauses other audio apps (music players,
     * podcasts) for the session, and keeps their sound out of the recording.
     */
    override fun requestFocus(): Boolean {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        val granted = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        focusRequest = if (granted) request else null
        logger.d(tag, "Audio focus request: ${if (granted) "granted" else "denied"}")
        return granted
    }

    /** Gives audio focus back, so the audio that was paused resumes. */
    override fun releaseFocus() {
        focusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
            logger.d(tag, "Audio focus released")
        }
        focusRequest = null
    }

    override suspend fun play(audio: ByteArray, mimeType: String) {
        // MediaPlayer plays from a file or a data source; the file's extension helps it pick the decoder.
        val file = withContext(Dispatchers.IO) {
            File.createTempFile("tts_", extensionFor(mimeType), context.cacheDir).also { it.writeBytes(audio) }
        }
        try {
            withContext(Dispatchers.Main) { playFile(file) }
        } finally {
            file.delete()
        }
    }

    private suspend fun playFile(file: File) {
        var player: MediaPlayer? = null
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(attributes)
                setDataSource(file.absolutePath)
                prepare()
            }
            val playing = player
            suspendCancellableCoroutine<Unit> { cont ->
                playing.setOnCompletionListener { if (cont.isActive) cont.resume(Unit) }
                playing.setOnErrorListener { _, what, extra ->
                    logger.e(tag, "MediaPlayer error: what=$what, extra=$extra")
                    if (cont.isActive) cont.resumeWithException(IOException("MediaPlayer error $what/$extra"))
                    true
                }
                interrupt = { if (cont.isActive) cont.resumeWithException(SpeechInterruptedException()) }
                playing.start()
                cont.invokeOnCancellation {
                    try { playing.stop() } catch (_: Exception) {}
                }
            }
        } finally {
            interrupt = null
            player?.release()
        }
    }

    private fun extensionFor(mimeType: String): String = when (mimeType.substringBefore(';').trim().lowercase()) {
        "audio/wav", "audio/x-wav", "audio/wave" -> ".wav"
        "audio/ogg", "audio/opus" -> ".ogg"
        "audio/flac" -> ".flac"
        "audio/aac" -> ".aac"
        else -> ".mp3"
    }
}
