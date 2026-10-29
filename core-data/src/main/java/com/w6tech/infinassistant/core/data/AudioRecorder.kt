package com.w6tech.infinassistant.core.data

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Records audio from microphone at 16kHz mono PCM.
 * Supports BT SCO routing for earbud microphone when available.
 *
 * This recorder is optimized for voice recognition use cases, particularly
 * for Home Assistant Assist which requires 16kHz mono PCM audio input.
 */
class AudioRecorder(
    private val audioManager: AudioManager,
) : SpeechRecorder {
    companion object {
        /** Sample rate in Hz - 16kHz is required by Home Assistant Assist */
        const val SAMPLE_RATE = 16000

        /** Mono channel configuration for voice input */
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO

        /** 16-bit PCM encoding */
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        /** Duration of each audio chunk in milliseconds */
        const val CHUNK_DURATION_MS = 100

        /** Size of each audio chunk in bytes: 16kHz * 2 bytes * 100ms / 1000 = 3200 bytes */
        const val CHUNK_SIZE_BYTES = SAMPLE_RATE * 2 * CHUNK_DURATION_MS / 1000
    }

    @Volatile
    private var audioRecord: AudioRecord? = null
    @Volatile
    private var communicationDeviceSet = false
    private val lock = Any()

    /**
     * Starts recording and returns a Flow of audio chunks.
     *
     * Each chunk contains 100ms of 16kHz mono PCM audio (3200 bytes).
     * The flow will continue emitting chunks until [stopRecording] is called.
     *
     * Attempts to route microphone through Bluetooth SCO (earbud mic) via
     * [AudioManager.setCommunicationDevice] if available.
     * If SCO is not available, falls back to device microphone.
     *
     * @return Flow of audio chunks as ByteArray
     * @throws IllegalStateException if AudioRecord fails to initialize
     */
    override suspend fun startRecording(): Flow<ByteArray> {
        // Try to route audio through Bluetooth SCO device if available
        val scoDevice = audioManager.availableCommunicationDevices
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        if (scoDevice != null) {
            communicationDeviceSet = audioManager.setCommunicationDevice(scoDevice)
        }

        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT),
            CHUNK_SIZE_BYTES * 2
        )

        return flow {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("Failed to initialize AudioRecord")
            }

            audioRecord?.startRecording()

            try {
                val buffer = ByteArray(CHUNK_SIZE_BYTES)
                while (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val bytesRead = audioRecord?.read(buffer, 0, CHUNK_SIZE_BYTES) ?: -1
                    if (bytesRead > 0) {
                        emit(buffer.copyOf(bytesRead))
                    } else if (bytesRead < 0) {
                        break
                    }
                }
            } finally {
                stopRecording()  // Ensure cleanup on cancellation
            }
        }.flowOn(Dispatchers.IO)
    }

    /**
     * Stops recording and releases all resources.
     *
     * This method is safe to call even if recording was never started.
     * After calling this method, [startRecording] can be called again.
     */
    override fun stopRecording() {
        synchronized(lock) {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            if (communicationDeviceSet) {
                audioManager.clearCommunicationDevice()
                communicationDeviceSet = false
            }
        }
    }

    /**
     * Returns true if currently recording audio.
     */
    val isRecording: Boolean
        get() = audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING

}
