package com.w6tech.infinassistant.core.common

/**
 * Redacts secrets from logs. Catches token values, Bearer headers,
 * refresh tokens, webhook IDs, passwords and credentials in URLs.
 */
object Redactor {
    private val tokenLike = Regex("(?i)(access_token|token|refresh_token|api_password|password)\\\"?\\s*[:=]\\s*\\\"([^\\\"]+)\\\"")
    // Unquoted, as in a URL query or a form body: "access_token=abc&...". Not "code=": the HA
    // socket's own close lines ("code=1000") must stay readable.
    private val tokenParam = Regex("(?i)\\b(access_token|refresh_token|token|api_password|password|authSig)=([^&\\s\"',;)]+)")
    // The user and password of a URL: "https://user:secret@host".
    private val urlCredentials = Regex("(?i)\\b((?:https?|wss?)://)[^/\\s:@]+:[^@\\s/]+@")
    private val bearerHeader = Regex("(?i)Bearer\\s+[A-Za-z0-9._\\-]+")
    private val webhookId = Regex("(?i)/api/webhook/([A-Za-z0-9_\\-]{8,})")
    private val longLivedToken = Regex("eyJ[A-Za-z0-9_\\-]{20,}\\.[A-Za-z0-9_\\-]{20,}\\.[A-Za-z0-9_\\-]{20,}")
    // A token's payload and signature with its header cut off, as a message cut short can leave
    // them: the header of HA's tokens is the same for every one, so the rest is the token.
    private val tokenTail = Regex("eyJ[A-Za-z0-9_\\-]{20,}\\.[A-Za-z0-9_\\-]{20,}")

    fun redact(input: String): String {
        var out = input
        out = out.replace(tokenLike) { m ->
            val key = m.groupValues[1]
            "$key=\"***REDACTED***\""
        }
        out = out.replace(tokenParam) { m -> "${m.groupValues[1]}=***REDACTED***" }
        out = out.replace(urlCredentials) { m -> "${m.groupValues[1]}***REDACTED***@" }
        out = out.replace(bearerHeader, "Bearer ***REDACTED***")
        out = out.replace(webhookId, "/api/webhook/***REDACTED***")
        out = out.replace(longLivedToken, "***JWT_REDACTED***")
        out = out.replace(tokenTail, "***JWT_REDACTED***")
        return out
    }
}
