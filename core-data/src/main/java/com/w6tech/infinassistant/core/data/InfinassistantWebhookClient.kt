package com.w6tech.infinassistant.core.data

import com.w6tech.infinassistant.core.common.Logger
import com.w6tech.infinassistant.core.domain.SensorReportPayload
import com.w6tech.infinassistant.core.ha.lanOnlyCleartext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Request-signing headers expected by the InfinAssistant HA integration. */
private const val HEADER_DEVICE_ID = "X-InfinAssistant-Device-Id"
private const val HEADER_TIMESTAMP = "X-InfinAssistant-Timestamp"
private const val HEADER_SIGNATURE = "X-InfinAssistant-Signature"

/** Matches the strict urlsafe-base64 alphabet the integration validates against. */
private val URLSAFE_B64_RE = Regex("^[A-Za-z0-9_\\-]+={0,2}$")

/**
 * Return the lower-case hex HMAC-SHA256 of `deviceId:timestamp:rawBody`, or
 * null when [secretB64] is not decodable urlsafe-base64.
 *
 * The canonical payload and digest encoding must stay byte-identical to
 * `WebhookAuthenticator.verify` in the integration's security.py.
 */
internal fun signRequest(
    secretB64: String,
    deviceId: String,
    timestamp: String,
    rawBody: ByteArray,
): String? {
    if (!URLSAFE_B64_RE.matches(secretB64)) return null
    val key = try {
        Base64.getUrlDecoder().decode(secretB64)
    } catch (_: IllegalArgumentException) {
        return null
    }
    if (key.isEmpty()) return null

    val canonical = deviceId.toByteArray(Charsets.UTF_8) +
        ":".toByteArray(Charsets.UTF_8) +
        timestamp.toByteArray(Charsets.UTF_8) +
        ":".toByteArray(Charsets.UTF_8) +
        rawBody

    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(canonical).joinToString("") { "%02x".format(it) }
}

/**
 * HTTP client for posting sensor data to Home Assistant InfinAssistant webhook.
 *
 * Uses UrlSelector to automatically choose between local/remote HA URLs.
 */
class InfinassistantWebhookClient(
    private val logger: Logger,
    private val settingsRepo: SettingsRepository,
    private val urlSelector: UrlSelector,
    okHttp: OkHttpClient? = null,
) {
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val tag = "InfinassistantWebhook"

    private val client = okHttp ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .lanOnlyCleartext()
        .build()

    /**
     * Webhook payload structure expected by Home Assistant InfinAssistant integration.
     *
     * @property type Always "sensor_update" for sensor data
     * @property deviceId BLE MAC address of the PineTime watch
     * @property timestamp Unix timestamp in milliseconds
     * @property sensors Sensor data from the watch
     */
    @Serializable
    private data class WebhookPayload(
        val type: String = "sensor_update",
        @SerialName("device_id")
        val deviceId: String,
        val timestamp: Long,
        val sensors: SensorData,
    )

    /**
     * Sensor data structure matching the webhook API format.
     * Field names use snake_case to match Python coordinator expectations.
     */
    @Serializable
    private data class SensorData(
        val steps: Long,
        @SerialName("heart_rate")
        val heartRate: Int,
        @SerialName("heart_rate_state")
        val heartRateState: String,  // "stopped", "not_enough_data", "no_touch", "running"
        @SerialName("battery_percent")
        val batteryPercent: Int,
        @SerialName("battery_charging")
        val batteryCharging: Boolean,
        @SerialName("motion_state")
        val motionState: String,  // "active" or "inactive" based on accelerometer data
    )

    /**
     * Posts sensor report to Home Assistant webhook.
     *
     * Automatically selects local or remote URL based on network connectivity.
     *
     * @param webhookId Webhook ID from HA integration (e.g., "abc123def456")
     * @param deviceId BLE MAC address of the watch
     * @param payload Sensor data from the watch
     * @return Result.success(Unit) on success, Result.failure(Exception) on error
     */
    suspend fun postSensorReport(
        webhookId: String,
        deviceId: String,
        payload: SensorReportPayload
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (webhookId.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Webhook ID is blank"))
            }

            // Build webhook path
            val webhookPath = "/api/webhook/$webhookId"

            // Get settings and select base URL (local or remote)
            val settings = settingsRepo.settings.first()
            val urlResult = urlSelector.selectUrl(settings.ha)

            val baseUrl = when (urlResult) {
                is UrlSelectionResult.Selected -> urlResult.url
                is UrlSelectionResult.Error -> {
                    return@withContext Result.failure(IOException("URL selection failed: ${urlResult.reason}"))
                }
            }

            val webhookUrl = "$baseUrl$webhookPath"

            // Convert heart rate state enum to string
            val hrStateString = when (payload.heartRateState) {
                0 -> "stopped"
                1 -> "not_enough_data"
                2 -> "no_touch"
                3 -> "running"
                else -> "unknown"
            }

            // Calculate motion state from accelerometer data
            // Compute magnitude: sqrt(x^2 + y^2 + z^2)
            val motionMagnitude = kotlin.math.sqrt(
                (payload.motionX * payload.motionX +
                 payload.motionY * payload.motionY +
                 payload.motionZ * payload.motionZ).toDouble()
            )
            // Threshold: if magnitude > 100, consider as "active"
            val motionState = if (motionMagnitude > 100.0) "active" else "inactive"

            // Build webhook payload
            val webhookPayload = WebhookPayload(
                deviceId = deviceId,
                timestamp = payload.timestamp,
                sensors = SensorData(
                    steps = payload.steps,
                    heartRate = payload.heartRate,
                    heartRateState = hrStateString,
                    batteryPercent = payload.batteryPercent,
                    batteryCharging = payload.batteryCharging,
                    motionState = motionState,
                )
            )

            // Serialize to JSON
            val jsonBody = json.encodeToString(webhookPayload)
            logger.d(tag, "POST $webhookUrl (${jsonBody.length} bytes)")

            // Build HTTP request
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val rawBody = jsonBody.toByteArray(Charsets.UTF_8)
            val requestBody = rawBody.toRequestBody(mediaType)
            val builder = Request.Builder()
                .url(webhookUrl)
                .post(requestBody)

            // HMAC-SHA256 request signing. The integration rejects unsigned
            // posts with HTTP 401 whenever it holds a secret, so skipping this
            // silently drops every sensor report.
            val secret = settings.infinassistant.hmacSecret
            if (secret.isNotBlank()) {
                val timestamp = (System.currentTimeMillis() / 1000L).toString()
                val signature = signRequest(secret, deviceId, timestamp, rawBody)
                if (signature == null) {
                    logger.e(tag, "HMAC secret is not valid urlsafe-base64; request will be rejected")
                } else {
                    builder.header(HEADER_DEVICE_ID, deviceId)
                        .header(HEADER_TIMESTAMP, timestamp)
                        .header(HEADER_SIGNATURE, signature)
                }
            } else {
                logger.w(tag, "No HMAC secret configured — Home Assistant will reject this report")
            }

            val request = builder.build()

            // Execute request
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    logger.d(tag, "Webhook POST successful: ${response.code}")
                    Result.success(Unit)
                } else {
                    val errorBody = response.body?.string() ?: ""
                    val error = "Webhook POST failed: ${response.code} - $errorBody"
                    logger.e(tag, error)
                    Result.failure(IOException(error))
                }
            }
        } catch (e: IOException) {
            logger.e(tag, "Webhook POST network error: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            logger.e(tag, "Webhook POST unexpected error: ${e.message}")
            Result.failure(e)
        }
    }
}
