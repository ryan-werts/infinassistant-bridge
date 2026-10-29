package com.w6tech.infinassistant.core.data

import com.w6tech.infinassistant.core.common.LocalNetwork
import com.w6tech.infinassistant.core.ha.LanOnlyCleartext
import com.w6tech.infinassistant.core.ha.lanOnlyCleartext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * The guard on the whole OkHttp chain, without real DNS: the name is looked up by an injected
 * resolver for the application half, and OkHttp's own lookup (the second one, which connects) is a
 * [Dns] of the test's. The socket is sent to MockWebServer whatever address the route names.
 */
class LanOnlyCleartextTest {

    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
        server.enqueue(MockResponse().setBody("ok"))
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun addr(vararg bytes: Int): InetAddress = InetAddress.getByAddress(ByteArray(bytes.size) { bytes[it].toByte() })

    /** Every connection goes to [server], whatever address the route says. */
    private inner class ToServer : SocketFactory() {
        private val target = InetSocketAddress(addr(127, 0, 0, 1), server.port)

        override fun createSocket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) = super.connect(target, timeout)
        }

        override fun createSocket(host: String?, port: Int): Socket = throw UnsupportedOperationException()
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = throw UnsupportedOperationException()
        override fun createSocket(host: InetAddress?, port: Int): Socket = throw UnsupportedOperationException()
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = throw UnsupportedOperationException()
    }

    /** A client whose guard believes [lookedUp] for a name and whose connection finds [connectsTo]. */
    private fun client(lookedUp: InetAddress, connectsTo: InetAddress): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .socketFactory(ToServer())
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = listOf(connectsTo)
            })
            .lanOnlyCleartext(LocalNetwork.Resolver { listOf(lookedUp) })
            .build()

    private fun get(client: OkHttpClient, url: String): String =
        client.newCall(Request.Builder().url(url).build()).execute().use { it.body!!.string() }

    @Test
    fun `the connected address is checked, with the same rules as an address in the URL`() {
        assertNull(LanOnlyCleartext.routeRefusal(Proxy.Type.DIRECT, addr(192, 168, 1, 51)))
        assertNull(LanOnlyCleartext.routeRefusal(Proxy.Type.DIRECT, addr(10, 0, 0, 5)))
        assertNull(LanOnlyCleartext.routeRefusal(Proxy.Type.DIRECT, addr(127, 0, 0, 1)))
        assertNull(LanOnlyCleartext.routeRefusal(Proxy.Type.DIRECT, addr(169, 254, 3, 4)))
        assertNull(LanOnlyCleartext.routeRefusal(Proxy.Type.DIRECT, addr(0xfd, 0x12, 0x34, 0x56, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)))
        assertNull(LanOnlyCleartext.routeRefusal(Proxy.Type.DIRECT, addr(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)))
        for (public in listOf(addr(203, 0, 113, 9), addr(172, 32, 0, 1), addr(100, 64, 0, 1), addr(0, 0, 0, 0), addr(0x20, 1, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1))) {
            assertTrue(public.toString(), LanOnlyCleartext.routeRefusal(Proxy.Type.DIRECT, public)!!.contains("not to a local address"))
        }
        // No address to check is no reason to let it through.
        assertTrue(LanOnlyCleartext.routeRefusal(Proxy.Type.DIRECT, null) != null)
    }

    @Test
    fun `a proxy of any kind is refused for cleartext, whatever its address`() {
        for (type in listOf(Proxy.Type.HTTP, Proxy.Type.SOCKS)) {
            val refusal = LanOnlyCleartext.routeRefusal(type, addr(192, 168, 1, 51))
            assertTrue(type.toString(), refusal!!.contains("proxy"))
        }
    }

    @Test
    fun `a cleartext request to a LAN address is not sent to a system proxy`() {
        // The URL's host is a private address, which passes the name check; the request would go to the proxy.
        val proxied = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(addr(127, 0, 0, 1), server.port)))
            .lanOnlyCleartext(LocalNetwork.Resolver { emptyList() })
            .build()
        try {
            get(proxied, "http://192.168.1.50:8123/api/")
            fail("the request should be refused")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("refused") && e.message!!.contains("proxy"))
        }
        assertEquals("nothing was written to the proxy", 0, server.requestCount)
    }

    @Test
    fun `a local name that resolves and connects to the LAN is allowed`() {
        val http = client(lookedUp = addr(192, 168, 1, 51), connectsTo = addr(127, 0, 0, 1))
        assertEquals("ok", get(http, "http://homeassistant.local:${server.port}/api/"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a name that looked local but connects elsewhere is refused before anything is sent`() {
        // The guard's lookup said 192.168.1.51; OkHttp's own, a moment later, got a public address.
        val http = client(lookedUp = addr(192, 168, 1, 51), connectsTo = addr(203, 0, 113, 9))
        try {
            get(http, "http://homeassistant.local:${server.port}/api/")
            fail("the request should be refused")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("refused") && e.message!!.contains("not to a local address"))
        }
        assertEquals("nothing was written to the connection", 0, server.requestCount)
    }

    @Test
    fun `a name that does not look local is refused by the application half without a connection`() {
        val http = client(lookedUp = addr(203, 0, 113, 9), connectsTo = addr(127, 0, 0, 1))
        try {
            get(http, "http://homeassistant.local:${server.port}/api/")
            fail("the request should be refused")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("refused"))
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `https is not held to the LAN`() {
        // No TLS server here: the guard lets the request through, and the connection then fails by itself.
        val http = client(lookedUp = addr(203, 0, 113, 9), connectsTo = addr(203, 0, 113, 9))
        try {
            get(http, "https://ha.example.net:${server.port}/api/")
            fail("there is no TLS server")
        } catch (e: IOException) {
            assertFalse(e.message, e.message.orEmpty().contains("Plain http"))
        }
    }
}
