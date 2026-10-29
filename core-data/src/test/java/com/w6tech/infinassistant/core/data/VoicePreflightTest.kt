package com.w6tech.infinassistant.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A voice trigger that cannot work is answered with its reason before the watch is told "listening". */
class VoicePreflightTest {

    private fun failure(
        enabled: Boolean = true,
        microphonePermission: Boolean = true,
        serviceMicrophone: Boolean = true,
        hasToken: Boolean = true,
        tokenRejected: Boolean = false,
    ) = VoicePreflight.failure(enabled, microphonePermission, serviceMicrophone, hasToken, tokenRejected)

    @Test
    fun `everything in place lets the trigger through`() {
        assertNull(failure())
    }

    @Test
    fun `voice switched off is refused`() {
        assertEquals(VoicePreflight.OFF, failure(enabled = false))
    }

    @Test
    fun `no microphone permission is refused`() {
        assertEquals(VoicePreflight.NO_PERMISSION, failure(microphonePermission = false))
    }

    @Test
    fun `a service without the microphone type asks for the app to be opened`() {
        assertEquals("Open the app once to enable voice", failure(serviceMicrophone = false))
    }

    @Test
    fun `a missing token is refused`() {
        assertEquals(VoicePreflight.NO_TOKEN, failure(hasToken = false))
    }

    @Test
    fun `a token HA rejected is refused with a message to re-enter it`() {
        assertEquals("HA rejected the token: re-enter it", failure(tokenRejected = true))
    }

    @Test
    fun `the first failing check is the one reported`() {
        assertEquals(
            VoicePreflight.OFF,
            failure(enabled = false, microphonePermission = false, serviceMicrophone = false, hasToken = false),
        )
        assertEquals(
            VoicePreflight.NO_PERMISSION,
            failure(microphonePermission = false, serviceMicrophone = false, hasToken = false),
        )
    }
}
