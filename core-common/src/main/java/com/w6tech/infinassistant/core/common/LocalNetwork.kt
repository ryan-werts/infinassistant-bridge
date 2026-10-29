package com.w6tech.infinassistant.core.common

import java.net.InetAddress
import java.net.URI

/**
 * Which hosts are on the local network, the only ones plain http may reach. The app's network
 * security policy permits cleartext (it matches hostnames exactly, so it cannot name a range such
 * as 192.168.0.0/16); the HTTP clients refuse it for any other host, so an HA token never crosses
 * the internet unencrypted.
 *
 * Local means an IP literal in a private, loopback or link-local range, `localhost`, or an mDNS
 * (`.local`) or `.home.arpa` name. Other names are not trusted to be local: DNS can point them
 * anywhere. A `.local` or `.home.arpa` name is trusted only once it resolves to such addresses too
 * ([isLocalHostResolved]): [isLocalHost] checks the name alone, so it is safe on any thread.
 */
object LocalNetwork {

    /** Looks a host name up: every address it has, or an exception when it has none. May block. */
    fun interface Resolver {
        fun lookup(host: String): List<InetAddress>
    }

    /** The system's resolver. */
    val SYSTEM_RESOLVER = Resolver { host -> InetAddress.getAllByName(host).toList() }

    /** True for a host plain http may reach by its name or literal alone: see [LocalNetwork]. No lookup. */
    fun isLocalHost(host: String): Boolean {
        val raw = host.trim().removePrefix("[").removeSuffix("]").lowercase()
        val h = raw.trimEnd('.')
        if (h.isEmpty()) return false
        // Exactly "localhost": with a trailing dot it is an absolute name the network's DNS answers.
        if (raw == "localhost" || isLocalName(h)) return true
        ipv4(h)?.let { (a, b) ->
            // "192.168.1.1." is a name to the resolver, not an address: DNS could put it anywhere.
            if (raw != h) return false
            return isLocalIpv4(a, b)
        }
        if (':' in h) return isLocalIpv6(h)
        return false
    }

    /**
     * [isLocalHost], and for a `.local` or `.home.arpa` name also that every address [resolver] gives
     * for it is local (the IP rules of [isLocalHost]): a name alone says nothing about where it points.
     * A failed lookup, or one with no address, is not local. Blocks on the lookup for those names, so
     * call it off the main thread (the HTTP interceptors do: they run on the request's thread).
     */
    fun isLocalHostResolved(host: String, resolver: Resolver = SYSTEM_RESOLVER): Boolean {
        if (!isLocalHost(host)) return false
        // Looked up as given, trailing dot and all: that is the name OkHttp will resolve.
        val name = host.trim().lowercase()
        if (!isLocalName(name.trimEnd('.'))) return true  // an IP literal or localhost: nothing to look up
        val addresses = try {
            resolver.lookup(name)
        } catch (_: Exception) {
            return false
        }
        return addresses.isNotEmpty() && addresses.all(::isLocalAddress)
    }

    /** A `.local` (mDNS) or `.home.arpa` name, trailing dot already dropped. */
    private fun isLocalName(h: String): Boolean = h.endsWith(".local") || h.endsWith(".home.arpa")

    private fun isLocalIpv4(a: Int, b: Int): Boolean =
        a == 10 || a == 127 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254)

    /** The IP rules of [isLocalHost] on a resolved address: private, loopback or link-local IPv4; ::1, fe80::/10 or fc00::/7. */
    fun isLocalAddress(address: InetAddress): Boolean {
        val bytes = address.address.map { it.toInt() and 0xff }
        return when (bytes.size) {
            4 -> isLocalIpv4(bytes[0], bytes[1])
            16 -> (bytes.take(15).all { it == 0 } && bytes[15] == 1) ||
                isLocalIpv6Word((bytes[0] shl 8) or bytes[1])
            else -> false
        }
    }

    /** True for an https or wss URL, and for an http or ws one to a [isLocalHost] host. */
    fun isCleartextAllowed(url: String): Boolean {
        val uri = try {
            URI(url.trim())
        } catch (_: Exception) {
            return false
        }
        return when (uri.scheme?.lowercase()) {
            "https", "wss" -> true
            "http", "ws" -> uri.host?.let(::isLocalHost) == true
            else -> false
        }
    }

    /**
     * Why [localUrl] cannot work as the local URL, or null. Only plain http is held to the local
     * network ([isLocalHost]): the HTTP clients refuse it for any other host, and the app then
     * falls back to the remote URL on every connect, so a typo in an address (192.186.1.5) goes
     * unseen except as dropped connections. The message carries no part of the URL.
     */
    fun plainHttpProblem(localUrl: String): String? {
        val url = localUrl.trim()
        if (!url.startsWith("http://", ignoreCase = true) || isCleartextAllowed(url)) return null
        return PLAIN_HTTP_PROBLEM
    }

    const val PLAIN_HTTP_PROBLEM =
        "Not a local address, so plain http is refused and the remote URL is used instead. " +
            "Check it for a typo: it must be 192.168.x.x, 10.x.x.x, 172.16-31.x.x, 169.254.x.x, a local IPv6 " +
            "address (fe80::, fc00::/7 or ::1), or a .local or .home.arpa name that resolves to one of those; " +
            "otherwise use https."

    /**
     * [cidr] in its canonical form, host bits cleared ("192.0.2.7/24" is "192.0.2.0/24"), or
     * null when it is not an IPv4 subnet. A bare address is a /32.
     */
    fun normalizeIpv4Subnet(cidr: String): String? {
        val t = cidr.trim()
        val prefix = if ('/' in t) t.substringAfter('/').toIntOrNull()?.takeIf { it in 0..32 } ?: return null else 32
        val address = ipv4Bits(t.substringBefore('/')) ?: return null
        val network = address and mask(prefix)
        return "${(network ushr 24) and 0xff}.${(network ushr 16) and 0xff}.${(network ushr 8) and 0xff}.${network and 0xff}/$prefix"
    }

    /** True when the IPv4 [address] is inside [cidr] (read as [normalizeIpv4Subnet] does). */
    fun isInIpv4Subnet(address: String, cidr: String): Boolean {
        val a = ipv4Bits(address.trim()) ?: return false
        val c = normalizeIpv4Subnet(cidr) ?: return false
        val prefix = c.substringAfter('/').toInt()
        val network = ipv4Bits(c.substringBefore('/')) ?: return false
        return (a and mask(prefix)) == network
    }

    private fun mask(prefix: Int): Int = if (prefix == 0) 0 else -1 shl (32 - prefix)

    /** A dotted-quad IPv4 literal as its 32 bits, or null when [h] is not one. */
    private fun ipv4Bits(h: String): Int? =
        ipv4Octets(h)?.fold(0) { bits, octet -> (bits shl 8) or octet }

    /** The first two octets of a dotted-quad IPv4 literal, or null when [h] is not one. */
    private fun ipv4(h: String): Pair<Int, Int>? = ipv4Octets(h)?.let { it[0] to it[1] }

    /**
     * The four octets of a dotted-quad IPv4 literal, or null when [h] is not one. An octet with a
     * leading zero is not taken: the resolver may read "010" as octal (8), a public address.
     */
    private fun ipv4Octets(h: String): List<Int>? {
        val parts = h.split('.')
        if (parts.size != 4) return null
        return parts.map { p ->
            if (p.isEmpty() || p.length > 3 || !p.all(Char::isDigit)) return null
            if (p.length > 1 && p[0] == '0') return null
            p.toInt().takeIf { it <= 255 } ?: return null
        }
    }

    /** Loopback (::1), link-local (fe80::/10) and unique-local (fc00::/7) IPv6 literals. */
    private fun isLocalIpv6(h: String): Boolean {
        val addr = h.substringBefore('%')  // a zone index, as in fe80::1%wlan0
        if (!addr.all { it.isDigit() || it in 'a'..'f' || it == ':' || it == '.' }) return false
        if (addr == "::1") return true
        val first = addr.substringBefore(':')
        val word = first.toIntOrNull(16) ?: return false
        if (first.length != 4) return false
        return isLocalIpv6Word(word)
    }

    /** True when the first 16 bits are link-local (fe80::/10) or unique-local (fc00::/7). */
    private fun isLocalIpv6Word(word: Int): Boolean = (word and 0xffc0) == 0xfe80 || (word and 0xfe00) == 0xfc00
}
