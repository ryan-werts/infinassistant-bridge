package com.w6tech.infinassistant.core.data

import com.w6tech.infinassistant.core.common.Redactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * What the redactor takes out of the diagnostic log, and so out of debug bundles: from app 2.1.110
 * every core-module warning and error goes there, exception messages and URLs included.
 */
class RedactorTest {

    // A JWT-shaped string (three base64url segments) built at runtime from its parts.
    private val token = listOf(
        """{"alg":"HS256","typ":"JWT"}""",
        """{"iss":"abcdef0123456789","iat":1700000000}""",
        "signature-signature-signature",
    ).joinToString(".") { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray()) }

    @Test
    fun `tokens go, quoted, unquoted and bare`() {
        assertFalse(Redactor.redact("""{"type":"auth","access_token":"$token"}""").contains(token))
        assertEquals("wss://ha/api?access_token=***REDACTED***&x=1", Redactor.redact("wss://ha/api?access_token=abc123&x=1"))
        assertEquals("auth with ***JWT_REDACTED***", Redactor.redact("auth with $token"))
        assertEquals("Bearer ***REDACTED***", Redactor.redact("Bearer abc.def-ghi"))
    }

    @Test
    fun `a token cut short loses its tail too`() {
        // The header gone, as when a message is cut in the middle of it.
        val cut = token.substring(20)
        val out = Redactor.redact("Unexpected JSON token at offset 40: $cut")
        assertFalse(out.contains("c2lnbmF0dXJl"))
        assertTrue(out.contains("***JWT_REDACTED***"))
    }

    @Test
    fun `passwords and URL credentials go`() {
        assertEquals("\"password=\"***REDACTED***\"", Redactor.redact("\"password\": \"hunter2\""))
        assertEquals("api_password=***REDACTED***&x", Redactor.redact("api_password=hunter2&x"))
        assertEquals("authSig=***REDACTED***", Redactor.redact("authSig=QWxhZGRpbjpvcGVu"))
        assertEquals(
            "failed to connect to https://***REDACTED***@ha.example:8123/api",
            Redactor.redact("failed to connect to https://admin:hunter2@ha.example:8123/api"),
        )
    }

    @Test
    fun `webhook IDs go, and the socket's close codes stay`() {
        assertEquals("POST /api/webhook/***REDACTED***", Redactor.redact("POST /api/webhook/0123456789abcdef"))
        assertEquals("onClosing code=1000 reason=", Redactor.redact("onClosing code=1000 reason="))
        assertEquals("https://ha.example:8123/api/states", Redactor.redact("https://ha.example:8123/api/states"))
    }
}
