package com.w6tech.infinassistant.core.ha

import com.w6tech.infinassistant.core.common.LocalNetwork
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException

/**
 * Refuses plain http to a host off the local network ([LocalNetwork.isLocalHostResolved]: a `.local`
 * or `.home.arpa` name must also resolve to a local address). The network security policy
 * permits cleartext everywhere, since it cannot name an address range; this is what keeps it to the
 * LAN.
 *
 * As an application interceptor it stops the request before a connection is made, by the host's
 * name (and a lookup for a `.local` or `.home.arpa` one). As a network interceptor it runs once the
 * connection exists, before anything is written to it, and checks what the name cannot: the
 * address the socket is really connected to ([routeRefusal]; OkHttp resolves the name again to
 * connect, and DNS can answer differently the second time) and that it does not go through a proxy,
 * which would receive the request whatever its host. It also stops a redirect from https to an
 * internet host's http. OkHttp adds
 * network interceptors only for calls that are not WebSockets, so a WebSocket gets the application
 * half alone, and [forCleartextWebSocket] stands in for the rest: [HaWebSocketClient] and
 * [AssistPipelineClient] use it for a `ws://` connection, and neither follows redirects.
 *
 * @param resolver Looks up `.local` and `.home.arpa` names for the application half.
 */
class LanOnlyCleartext(private val resolver: LocalNetwork.Resolver = LocalNetwork.SYSTEM_RESOLVER) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.isHttps) return chain.proceed(request)
        // The connection is null for an application interceptor, and set for a network interceptor.
        val route = chain.connection()?.route()
        if (route == null) {
            // A .local or .home.arpa name is looked up here, on the request's own thread, and must resolve to a local address.
            if (!LocalNetwork.isLocalHostResolved(url.host, resolver)) throw refused(url.host, NOT_LOCAL)
        } else {
            // The name alone (DNS could point any other name at a LAN address), then where the socket went.
            if (!LocalNetwork.isLocalHost(url.host)) throw refused(url.host, NOT_LOCAL)
            routeRefusal(route.proxy.type(), route.socketAddress.address)?.let { throw refused(url.host, it) }
        }
        return chain.proceed(request)
    }

    private fun refused(host: String, why: String) =
        IOException("Plain http to $host refused: $why, use https")

    companion object {
        const val NOT_LOCAL = "only a local-network address may use http"

        /**
         * Why plain http may not go over a connection made to [address] by way of [proxyType], or null.
         * Through a proxy (a Wi-Fi network can set one) the request, Bearer header and all, goes to
         * the proxy whatever the URL's host is.
         */
        fun routeRefusal(proxyType: Proxy.Type, address: InetAddress?): String? = when {
            proxyType != Proxy.Type.DIRECT -> "the request would go through a proxy"
            address == null || !LocalNetwork.isLocalAddress(address) -> "the connection is not to a local address"
            else -> null
        }
    }
}

/**
 * A [Dns] that answers only with the local addresses ([LocalNetwork.isLocalAddress]) of [delegate]'s
 * answer, and fails when it has none. It is the connect-time check that a WebSocket cannot get from a
 * network interceptor: the socket can only be opened to a local address.
 */
class LocalOnlyDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val local = delegate.lookup(hostname).filter(LocalNetwork::isLocalAddress)
        if (local.isEmpty()) throw UnknownHostException("$hostname has no local address")
        return local
    }
}

/**
 * This client for a cleartext `ws://` connection, which gets no network interceptor from OkHttp: no
 * proxy (the auth message would go to it, whatever the host), the client's own [Dns] narrowed to local
 * addresses, and no redirects. A name is resolved by the narrowed Dns; an IP literal bypasses Dns and
 * is checked by [LanOnlyCleartext] by its text. The connection pool is shared with the original.
 */
fun OkHttpClient.forCleartextWebSocket(): OkHttpClient =
    newBuilder()
        .proxy(Proxy.NO_PROXY)
        .dns(LocalOnlyDns(dns))
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

/** Adds [LanOnlyCleartext] at both ends of the chain: see there. */
fun OkHttpClient.Builder.lanOnlyCleartext(resolver: LocalNetwork.Resolver = LocalNetwork.SYSTEM_RESOLVER): OkHttpClient.Builder {
    val guard = LanOnlyCleartext(resolver)
    return addInterceptor(guard).addNetworkInterceptor(guard)
}
