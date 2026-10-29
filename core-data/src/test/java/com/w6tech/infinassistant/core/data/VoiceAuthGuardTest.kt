package com.w6tech.infinassistant.core.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** HA bans an address after ten failed logins, so a token it refused is not offered again. */
class VoiceAuthGuardTest {

    @Test
    fun `a token nobody rejected is accepted`() {
        assertFalse(VoiceAuthGuard().isRejected("token-a"))
    }

    @Test
    fun `a rejected token is refused until it changes`() {
        val guard = VoiceAuthGuard()
        guard.recordRejection("token-a")
        assertTrue(guard.isRejected("token-a"))
        assertFalse("a new token is tried", guard.isRejected("token-b"))
    }

    @Test
    fun `a later rejection replaces the earlier one`() {
        val guard = VoiceAuthGuard()
        guard.recordRejection("token-a")
        guard.recordRejection("token-b")
        assertFalse(guard.isRejected("token-a"))
        assertTrue(guard.isRejected("token-b"))
    }
}
