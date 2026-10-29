package com.w6tech.infinassistant.core.data

import java.security.MessageDigest

/**
 * Remembers that HA refused a token, so voice does not ask again with it. HA bans an address after
 * ten failed logins, and a watch button is easy to press ten times; the second trigger with a token
 * HA has already rejected is refused here, without a request. A different token (the owner entered
 * a new one) is tried again. Held in memory only, and as a hash: the token itself is not kept.
 */
class VoiceAuthGuard {
    @Volatile private var rejectedHash: String? = null

    /** HA answered `auth_invalid` to [token]. */
    fun recordRejection(token: String) {
        rejectedHash = hash(token)
    }

    /** Whether HA has refused this very token. */
    fun isRejected(token: String): Boolean = rejectedHash?.let { it == hash(token) } == true

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val REJECTED_MESSAGE = "HA rejected the token: re-enter it"
    }
}
