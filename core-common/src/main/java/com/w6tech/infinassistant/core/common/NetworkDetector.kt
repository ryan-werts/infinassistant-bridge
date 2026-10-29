package com.w6tech.infinassistant.core.common

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.annotation.RequiresPermission
import java.net.Inet4Address

/**
 * Interface for detecting the current network.
 */
interface NetworkDetector {
    /**
     * The phone's IPv4 addresses on the network its traffic takes, each with its prefix length
     * ("192.0.2.40/24"), when that network is Wi-Fi or Ethernet: the home-subnet check of an
     * http local URL. Empty on mobile data, behind a VPN, or with no network.
     */
    suspend fun getLocalIpv4Networks(): List<String> = emptyList()
}

/**
 * Android implementation of [NetworkDetector] using ConnectivityManager.
 */
class AndroidNetworkDetector(
    private val context: Context
) : NetworkDetector {

    private val connectivityManager: ConnectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    /**
     * The default network only: a request goes out on it, so behind a VPN (the default network
     * then) an http local URL would leave through the tunnel, and the answer is none.
     */
    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    override suspend fun getLocalIpv4Networks(): List<String> = try {
        val network = connectivityManager.activeNetwork
        val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }
        val onLan = capabilities != null &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        if (!onLan) {
            emptyList()
        } else {
            connectivityManager.getLinkProperties(network)?.linkAddresses.orEmpty()
                .filter { it.address is Inet4Address }
                .mapNotNull { la -> la.address.hostAddress?.let { "$it/${la.prefixLength}" } }
        }
    } catch (_: SecurityException) {
        emptyList()
    }
}
