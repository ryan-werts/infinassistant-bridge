package com.w6tech.infinassistant.core.data

import kotlin.math.sqrt

/**
 * Detects voice activity in audio streams using RMS-based silence detection.
 *
 * Used to automatically stop recording when the user stops speaking.
 *
 * Note: This class is designed as a singleton but DI annotations are applied
 * in the app module's Hilt configuration (AppModule.kt) rather than here,
 * as core-data does not have javax.inject as a dependency.
 */
class VoiceActivityDetector : SpeechDetector {

    companion object {
        /** RMS threshold below which audio is considered silence (0-32767 range for 16-bit PCM) */
        const val SILENCE_THRESHOLD_RMS = 500

        /** Duration of silence required to trigger end of speech (milliseconds) */
        const val SILENCE_DURATION_MS = 1500L

        /** Maximum recording duration (milliseconds) */
        const val MAX_RECORDING_DURATION_MS = 15_000L

        /** Minimum speech duration required before silence detection kicks in (milliseconds) */
        const val MIN_SPEECH_DURATION_MS = 500L
    }

    /**
     * Result from voice activity detection.
     */
    sealed interface VadResult {
        /** Speech is ongoing - continue recording */
        data object Speaking : VadResult

        /** Silence detected after speech - stop recording */
        data object SilenceAfterSpeech : VadResult

        /** Max duration reached - stop recording */
        data object MaxDurationReached : VadResult

        /** Initial silence (no speech detected yet) */
        data object InitialSilence : VadResult
    }

    @Volatile
    private var speechDetected = false
    @Volatile
    private var silenceStartTime: Long? = null
    @Volatile
    private var recordingStartTime: Long? = null
    private val lock = Any()

    /**
     * Resets the detector state for a new recording session.
     */
    override fun reset() {
        synchronized(lock) {
            speechDetected = false
            silenceStartTime = null
            recordingStartTime = null
        }
    }

    /**
     * Analyzes an audio chunk and returns the voice activity result.
     *
     * @param audioChunk 16-bit PCM audio data (little-endian)
     * @return VadResult indicating current voice activity state
     */
    override fun analyze(audioChunk: ByteArray): VadResult {
        val currentTime = System.currentTimeMillis()

        // Calculate RMS of audio chunk (pure function, no state access needed)
        val rms = calculateRms(audioChunk)
        val isSpeech = rms >= SILENCE_THRESHOLD_RMS

        return synchronized(lock) {
            // Initialize recording start time
            if (recordingStartTime == null) {
                recordingStartTime = currentTime
            }

            // Check max duration
            val elapsedTime = currentTime - (recordingStartTime ?: currentTime)
            if (elapsedTime >= MAX_RECORDING_DURATION_MS) {
                return@synchronized VadResult.MaxDurationReached
            }

            when {
                isSpeech -> {
                    // Speech detected
                    speechDetected = true
                    silenceStartTime = null
                    VadResult.Speaking
                }
                !speechDetected -> {
                    // Haven't detected speech yet
                    VadResult.InitialSilence
                }
                else -> {
                    // Silence after speech was detected
                    if (silenceStartTime == null) {
                        silenceStartTime = currentTime
                    }

                    val silenceDuration = currentTime - (silenceStartTime ?: currentTime)
                    if (silenceDuration >= SILENCE_DURATION_MS) {
                        VadResult.SilenceAfterSpeech
                    } else {
                        VadResult.Speaking // Still within grace period
                    }
                }
            }
        }
    }

    /**
     * Calculates the Root Mean Square (RMS) of 16-bit PCM audio data.
     *
     * @param audioData Raw 16-bit PCM audio (little-endian byte order)
     * @return RMS value (0-32767 range)
     */
    private fun calculateRms(audioData: ByteArray): Double {
        if (audioData.size < 2) return 0.0

        var sumSquares = 0.0
        val sampleCount = audioData.size / 2

        for (i in 0 until sampleCount) {
            // Convert little-endian bytes to 16-bit signed sample
            val low = audioData[i * 2].toInt() and 0xFF
            val high = audioData[i * 2 + 1].toInt()
            val sample = (high shl 8) or low

            sumSquares += (sample * sample).toDouble()
        }

        return sqrt(sumSquares / sampleCount)
    }

    /**
     * Returns whether speech has been detected since the last reset.
     */
    val hasSpeechBeenDetected: Boolean
        get() = speechDetected
}
