package com.w6tech.infinassistant.core.data

import com.w6tech.infinassistant.core.common.NetworkDetector
import com.w6tech.infinassistant.core.domain.HaConnectionSettings
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Result of URL selection process.
 */
sealed interface UrlSelectionResult {
    /**
     * URL successfully selected.
     * @param url The selected Home Assistant URL
     * @param source How the URL was selected (connection test, fallback)
     * @param localTestError Why the local URL's connection test failed, when it was tried and did
     */
    data class Selected(
        val url: String,
        val source: SelectionSource,
        val localTestError: String? = null,
    ) : UrlSelectionResult

    /**
     * Failed to select a valid URL.
     * @param reason Human-readable error message
     */
    data class Error(val reason: String) : UrlSelectionResult
}

/**
 * How the URL was selected.
 */
enum class SelectionSource {
    CONNECTION_TEST, // Connection test succeeded
    FALLBACK        // Last resort fallback
}

/**
 * Selects the appropriate Home Assistant URL based on network conditions and settings.
 * Implements the decision tree:
 * 1. Perform connection test of the local URL with timeout
 * 2. Fall back to remote or local URL
 * An http local URL is used, or tested, only on a home network ([HomeSubnets]): it sends the token
 * unencrypted, and another Wi-Fi can have a host at the same address.
 */
class UrlSelector(
    private val networkDetector: NetworkDetector,
    private val httpClient: OkHttpClient
) {
    /**
     * Selects the appropriate URL based on current settings and network state.
     *
     * @param settings Current HA connection settings
     * @return Selected URL with source, or error if no valid URL available
     */
    suspend fun selectUrl(settings: HaConnectionSettings): UrlSelectionResult {
        // Validate at least one URL is configured
        if (settings.localUrl.isEmpty() && settings.remoteUrl.isEmpty()) {
            return UrlSelectionResult.Error("No URL configured")
        }

        // Why the local URL may not be used here (plain http off the home networks), or null.
        val localRefusal = if (settings.localUrl.isNotEmpty()) {
            HomeSubnets.localUrlRefusal(settings.localUrl, settings.homeSubnets, networkDetector.getLocalIpv4Networks())
        } else {
            null
        }

        // Step 1: Connection Test
        var localTestError: String? = localRefusal
        if (settings.localUrl.isNotEmpty() && localRefusal == null) {
            val testResult = testConnection(settings.localUrl, timeoutMs = 3000)
            testResult.exceptionOrNull()?.let { e -> localTestError = e.message ?: e.javaClass.simpleName }
            if (testResult.isSuccess) {
                return UrlSelectionResult.Selected(
                    url = settings.localUrl,
                    source = SelectionSource.CONNECTION_TEST,
                )
            }
        }

        // Step 2: Final Fallback
        return when {
            settings.remoteUrl.isNotEmpty() -> {
                UrlSelectionResult.Selected(settings.remoteUrl, SelectionSource.FALLBACK, localTestError = localTestError)
            }
            localRefusal != null -> UrlSelectionResult.Error("Local URL not used: $localRefusal")
            settings.localUrl.isNotEmpty() -> {
                UrlSelectionResult.Selected(settings.localUrl, SelectionSource.FALLBACK, localTestError = localTestError)
            }
            else -> {
                UrlSelectionResult.Error("No URL configured")
            }
        }
    }

    /**
     * Tests if a URL is reachable by performing a GET request with timeout.
     * Returns success for 2xx/3xx/4xx responses (server reachable).
     * Returns failure for 5xx errors (server error) or connection failures.
     *
     * The request is for [PROBE_PATH], which HA serves without authentication. An unauthenticated
     * request to /api/, as until app 2.1.115, is a failed login to HA: with ip_ban_enabled, ten of
     * them banned the phone's address, and every request from it was then refused (403).
     *
     * @param url The URL to test
     * @param timeoutMs Timeout in milliseconds
     * @return Result indicating success or failure
     */
    private suspend fun testConnection(url: String, timeoutMs: Long): Result<Unit> {
        return try {
            withTimeout(timeoutMs) {
                val request = Request.Builder()
                    .url(url.trimEnd('/') + PROBE_PATH)
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    // Accept 2xx (success), 3xx (redirect), 4xx (auth required but server reachable)
                    // Reject 5xx (server error - not a reliable server)
                    if (response.code < 500) {
                        Result.success(Unit)
                    } else {
                        Result.failure(IOException("Server error: HTTP ${response.code}"))
                    }
                }
            }
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        /** HA's web app manifest: served without authentication, so the probe is never a failed login. */
        const val PROBE_PATH = "/manifest.json"
    }
}
