package com.w6tech.infinassistant.core.data

import org.junit.Assert.assertNull
import com.w6tech.infinassistant.core.common.LocalNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Which hosts plain http may reach (app 2.1.113): the LAN only. The network security policy could
 * not say it, as it matches hostnames exactly: its "192.168.0.0" never covered 192.168.1.51.
 */
class LocalNetworkTest {

    @Test
    fun `private, loopback and link-local addresses and local names are local`() {
        for (host in listOf(
            "192.168.1.51", "192.168.0.1", "10.0.0.5", "10.255.255.255", "172.16.0.1", "172.31.9.9",
            "127.0.0.1", "169.254.10.20", "localhost", "homeassistant.local", "HomeAssistant.Local.",
            "ha.home.arpa", "::1", "[::1]", "fe80::1", "fe80::1%wlan0", "fd12:3456:789a::1", "fc00::5",
        )) {
            assertTrue(host, LocalNetwork.isLocalHost(host))
        }
    }

    @Test
    fun `internet addresses and other names are not`() {
        for (host in listOf(
            "203.0.113.220", "8.8.8.8", "172.32.0.1", "172.15.255.255", "192.169.1.1", "11.0.0.1",
            "100.64.0.1", "192.168.0.256", "192.168.1", "ha.example.net", "local.example.com",
            "evil.local.example.com", "homeassistant", "2001:db8::1", "fe7f::1", "", "fe80:zz::1",
            // Read as octal by the resolver (010 = 8: 8.1.2.3), and an address with a trailing dot,
            // which the resolver sends to DNS as a name.
            "010.1.2.3", "192.168.01.1", "192.168.1.1.",
        )) {
            assertFalse(host, LocalNetwork.isLocalHost(host))
        }
    }

    private fun addr(vararg bytes: Int): InetAddress = InetAddress.getByAddress(ByteArray(bytes.size) { bytes[it].toByte() })

    private fun resolving(vararg addresses: InetAddress) = LocalNetwork.Resolver { addresses.toList() }

    private val unreachable = LocalNetwork.Resolver { throw UnknownHostException("no such host") }

    @Test
    fun `a local name must also resolve to local addresses`() {
        val lan = resolving(addr(192, 168, 1, 51))
        assertTrue(LocalNetwork.isLocalHostResolved("homeassistant.local", lan))
        assertTrue(LocalNetwork.isLocalHostResolved("HomeAssistant.Local.", lan))
        assertTrue(LocalNetwork.isLocalHostResolved("ha.home.arpa", resolving(addr(10, 0, 0, 5))))
        assertTrue(LocalNetwork.isLocalHostResolved("ha.local", resolving(addr(172, 16, 0, 1))))
        assertTrue(LocalNetwork.isLocalHostResolved("ha.local", resolving(addr(169, 254, 3, 4))))
        // IPv6: loopback, link-local and unique-local.
        assertTrue(LocalNetwork.isLocalHostResolved("ha.local", resolving(addr(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1))))
        assertTrue(LocalNetwork.isLocalHostResolved("ha.local", resolving(addr(0xfe, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1))))
        assertTrue(LocalNetwork.isLocalHostResolved("ha.local", resolving(addr(0xfd, 0x12, 0x34, 0x56, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1))))
    }

    @Test
    fun `a local name that resolves anywhere else, or nowhere, is not local`() {
        // Public IPv4, public IPv6 (2001:db8::1), just outside 172.16/12, and a CGNAT address.
        assertFalse(LocalNetwork.isLocalHostResolved("ha.local", resolving(addr(203, 0, 113, 220))))
        assertFalse(LocalNetwork.isLocalHostResolved("ha.local", resolving(addr(172, 32, 0, 1))))
        assertFalse(LocalNetwork.isLocalHostResolved("ha.local", resolving(addr(100, 64, 0, 1))))
        assertFalse(LocalNetwork.isLocalHostResolved("ha.home.arpa", resolving(addr(0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1))))
        // One public address among local ones is enough to refuse: the connect may pick it.
        assertFalse(LocalNetwork.isLocalHostResolved("ha.local", resolving(addr(192, 168, 1, 51), addr(203, 0, 113, 220))))
        // A failed lookup, an empty answer and an unexpected failure are not local.
        assertFalse(LocalNetwork.isLocalHostResolved("ha.local", unreachable))
        assertFalse(LocalNetwork.isLocalHostResolved("ha.local", resolving()))
        assertFalse(LocalNetwork.isLocalHostResolved("ha.local", LocalNetwork.Resolver { throw SecurityException("no") }))
    }

    @Test
    fun `the lookup is only for local names, and an untrusted name is refused without one`() {
        var lookups = 0
        val counting = LocalNetwork.Resolver { lookups++; listOf(addr(192, 168, 1, 51)) }
        // Literals and localhost need no lookup.
        assertTrue(LocalNetwork.isLocalHostResolved("192.168.1.51", counting))
        assertTrue(LocalNetwork.isLocalHostResolved("[fd00::2]", counting))
        assertTrue(LocalNetwork.isLocalHostResolved("localhost", counting))
        // A name DNS could point anywhere is refused by its name, whatever it would resolve to.
        assertFalse(LocalNetwork.isLocalHostResolved("ha.example.net", counting))
        assertFalse(LocalNetwork.isLocalHostResolved("203.0.113.220", counting))
        assertEquals(0, lookups)
        assertTrue(LocalNetwork.isLocalHostResolved("homeassistant.local", counting))
        assertEquals(1, lookups)
    }

    @Test
    fun `only exactly localhost is trusted by name, not the absolute name with a dot`() {
        assertTrue(LocalNetwork.isLocalHost("localhost"))
        assertTrue(LocalNetwork.isLocalHost("LocalHost"))
        // "localhost." is an absolute name: OkHttp sends it to the network's DNS, which can answer anything.
        assertFalse(LocalNetwork.isLocalHost("localhost."))
        assertFalse(LocalNetwork.isLocalHost("localhost.."))
        assertFalse(LocalNetwork.isLocalHostResolved("localhost.", resolving(addr(127, 0, 0, 1))))
        assertFalse(LocalNetwork.isCleartextAllowed("http://localhost.:8123"))
        assertTrue(LocalNetwork.isCleartextAllowed("http://localhost:8123"))
    }

    @Test
    fun `a local name is looked up as given, with its trailing dot`() {
        val asked = mutableListOf<String>()
        val recording = LocalNetwork.Resolver { asked += it; listOf(addr(192, 168, 1, 51)) }
        assertTrue(LocalNetwork.isLocalHostResolved("HomeAssistant.local.", recording))
        assertTrue(LocalNetwork.isLocalHostResolved("ha.home.arpa", recording))
        assertTrue(LocalNetwork.isLocalHostResolved(" ha.home.arpa. ", recording))
        assertEquals(listOf("homeassistant.local.", "ha.home.arpa", "ha.home.arpa."), asked)
    }

    @Test
    fun `the refusal text names home arpa and IPv6 local addresses`() {
        val text = LocalNetwork.PLAIN_HTTP_PROBLEM
        assertTrue(text, text.contains(".home.arpa"))
        assertTrue(text, text.contains("IPv6"))
        assertTrue(text, text.contains(".local"))
    }

    @Test
    fun `https is always allowed, and http and ws only to the LAN`() {
        assertTrue(LocalNetwork.isCleartextAllowed("https://ha.example.net:8443"))
        assertTrue(LocalNetwork.isCleartextAllowed("wss://ha.example.net:8443/api/websocket"))
        assertTrue(LocalNetwork.isCleartextAllowed("http://192.168.1.51:8123"))
        assertTrue(LocalNetwork.isCleartextAllowed("ws://192.168.1.51:8123/api/websocket"))
        assertTrue(LocalNetwork.isCleartextAllowed("http://[fd00::2]:8123"))
        assertFalse(LocalNetwork.isCleartextAllowed("http://ha.example.net:8443"))
        assertFalse(LocalNetwork.isCleartextAllowed("ws://203.0.113.220:8123"))
        assertFalse(LocalNetwork.isCleartextAllowed("ftp://192.168.1.51"))
        assertFalse(LocalNetwork.isCleartextAllowed("not a url"))
    }

    @Test
    fun `plain http to a non-local address is a problem to show, and nothing else is`() {
        // A typo that turns a private address public (192.186 for 192.168): the app falls back to the remote URL.
        assertTrue(LocalNetwork.plainHttpProblem("http://192.186.1.51:8123") != null)
        assertTrue(LocalNetwork.plainHttpProblem(" HTTP://ha.example.net:8123 ") != null)
        assertTrue(LocalNetwork.plainHttpProblem("http://203.0.113.9") != null)
        // The message names no part of the URL.
        assertFalse(LocalNetwork.plainHttpProblem("http://192.186.1.51:8123")!!.contains("186"))
        for (ok in listOf(
            "http://192.168.1.51:8123", "http://10.0.0.5", "http://172.20.1.1:8123", "http://169.254.3.4",
            "http://homeassistant.local:8123", "https://ha.example.net", "", "   ", "homeassistant.local",
        )) {
            assertNull(ok, LocalNetwork.plainHttpProblem(ok))
        }
    }
}
