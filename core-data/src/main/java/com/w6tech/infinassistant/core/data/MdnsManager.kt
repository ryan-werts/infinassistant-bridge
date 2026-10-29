package com.w6tech.infinassistant.core.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.w6tech.infinassistant.core.common.Logger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.InetAddress

/**
 * Manages mDNS service registration for Home Assistant auto-discovery.
 *
 * Broadcasts an _infinassistant._tcp service with:
 * - webhook_url: Full webhook URL for sensor data push
 * - device_id: BLE MAC address of the paired watch
 *
 * Home Assistant's InfinAssistant integration listens for these broadcasts
 * and can automatically configure the integration.
 */
class MdnsManager(
    private val context: Context,
    private val logger: Logger,
) {
    private val tag = "MdnsManager"
    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private var currentServiceInfo: NsdServiceInfo? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    /**
     * Registers the InfinAssistant mDNS service.
     *
     * @param webhookUrl Full webhook URL (e.g., "http://homeassistant.local:8123/api/webhook/abc123")
     * @param deviceId BLE MAC address of the paired watch
     * @return Flow that emits registration state changes
     */
    fun registerService(
        webhookUrl: String,
        deviceId: String,
    ): Flow<RegistrationState> = callbackFlow {
        if (webhookUrl.isBlank() || deviceId.isBlank()) {
            logger.w(tag, "Cannot register mDNS: empty webhookUrl or deviceId")
            trySend(RegistrationState.Failed("Empty webhook URL or device ID"))
            close()
            return@callbackFlow
        }

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "PineTime-$deviceId"
            serviceType = "_infinassistant._tcp"
            // The stack refuses a service without a port ("The port is missing", Android 14+): the
            // Home Assistant port from the URL stands in. Nothing listens on it for this service.
            port = portOf(webhookUrl)

            // TXT records for Home Assistant discovery
            setAttribute("webhook_url", webhookUrl)
            setAttribute("device_id", deviceId)
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                logger.e(tag, "mDNS registration failed: code=$errorCode")
                registrationListener = null   // nothing registered: the close must not unregister it
                trySend(RegistrationState.Failed("Error code: $errorCode"))
                close()
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                logger.e(tag, "mDNS unregistration failed: code=$errorCode")
            }

            override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {
                logger.i(tag, "mDNS service registered: ${serviceInfo?.serviceName}")
                currentServiceInfo = serviceInfo
                trySend(RegistrationState.Registered(serviceInfo?.serviceName ?: "Unknown"))
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {
                logger.i(tag, "mDNS service unregistered: ${serviceInfo?.serviceName}")
                currentServiceInfo = null
                trySend(RegistrationState.Unregistered)
            }
        }

        try {
            logger.i(tag, "Registering mDNS service: ${serviceInfo.serviceName}")
            registrationListener = listener
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
            trySend(RegistrationState.Registering)
        } catch (e: Exception) {
            logger.e(tag, "Failed to register mDNS service: ${e.message}")
            registrationListener = null   // nothing registered: the close must not unregister it
            trySend(RegistrationState.Failed(e.message ?: "Unknown error"))
            close()
        }

        awaitClose {
            unregisterService()
        }
    }

    /**
     * Unregisters the current mDNS service.
     */
    fun unregisterService() {
        registrationListener?.let { listener ->
            try {
                logger.i(tag, "Unregistering mDNS service")
                nsdManager.unregisterService(listener)
            } catch (e: Exception) {
                logger.w(tag, "Failed to unregister mDNS: ${e.message}")
            } finally {
                registrationListener = null
                currentServiceInfo = null
            }
        }
    }

    companion object {
        /** The HA port the advertisement carries: the URL's own, else 443 for https, else 8123. */
        const val DEFAULT_PORT = 8123

        /** The port an https URL with no port of its own reaches. */
        const val HTTPS_PORT = 443

        /** [webhookUrl]'s port; with none, [HTTPS_PORT] for an https URL, else [DEFAULT_PORT] (also for a non-URL). */
        fun portOf(webhookUrl: String): Int {
            var scheme: String? = null
            val port = try {
                val uri = java.net.URI(webhookUrl.trim())
                scheme = uri.scheme
                uri.port
            } catch (_: Exception) {
                -1
            }
            return when {
                port in 1..65535 -> port
                scheme.equals("https", ignoreCase = true) -> HTTPS_PORT
                else -> DEFAULT_PORT
            }
        }
    }

    sealed class RegistrationState {
        data object Registering : RegistrationState()
        data class Registered(val serviceName: String) : RegistrationState()
        data object Unregistered : RegistrationState()
        data class Failed(val reason: String) : RegistrationState()
    }
}
