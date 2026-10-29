package com.w6tech.infinassistant.core.data

import com.w6tech.infinassistant.core.common.NetworkDetector
import com.w6tech.infinassistant.core.domain.HaConnectionSettings
import com.w6tech.infinassistant.core.ha.lanOnlyCleartext
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UrlSelectorTest {

    /** An http local URL is used only on a home network: these put the phone on one (MockWebServer is on 127.0.0.1). */
    private val home = listOf("127.0.0.0/8")
    private val atHome = listOf("127.0.0.5/8")

    private lateinit var mockServer: MockWebServer
    private lateinit var httpClient: OkHttpClient

    @Before
    fun setup() {
        mockServer = MockWebServer()
        mockServer.start()
        httpClient = OkHttpClient.Builder()
            .connectTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    @Test
    fun `selectUrl returns error when no URLs configured`() = runTest {
        val networkDetector = FakeNetworkDetector()
        val selector = UrlSelector(networkDetector, httpClient)

        val settings = HaConnectionSettings(
            localUrl = "",
            remoteUrl = ""
        )

        val result = selector.selectUrl(settings)

        assertTrue(result is UrlSelectionResult.Error)
        assertTrue((result as UrlSelectionResult.Error).reason.contains("No URL configured"))
    }

    @Test
    fun `selectUrl uses the local URL when its connection test succeeds`() = runTest {
        val networkDetector = FakeNetworkDetector(atHome)
        val selector = UrlSelector(networkDetector, httpClient)

        // Mock successful connection test
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("OK"))

        val localUrl = mockServer.url("/").toString().trimEnd('/')
        val settings = HaConnectionSettings(
            localUrl = localUrl,
            remoteUrl = "http://remote.example.com",
            homeSubnets = home
        )

        val result = selector.selectUrl(settings)

        // Should attempt connection test and succeed
        assertTrue(result is UrlSelectionResult.Selected)
        val selected = result as UrlSelectionResult.Selected
        assertEquals(localUrl, selected.url)
        assertEquals(SelectionSource.CONNECTION_TEST, selected.source)
    }

    @Test
    fun `selectUrl falls back to remote when connection test fails`() = runTest {
        val networkDetector = FakeNetworkDetector(atHome)
        val selector = UrlSelector(networkDetector, httpClient)

        // Mock failed connection test
        mockServer.enqueue(MockResponse().setResponseCode(500))

        val localUrl = mockServer.url("/").toString().trimEnd('/')
        val settings = HaConnectionSettings(
            localUrl = localUrl,
            remoteUrl = "http://remote.example.com",
            homeSubnets = home
        )

        val result = selector.selectUrl(settings)

        assertTrue(result is UrlSelectionResult.Selected)
        val selected = result as UrlSelectionResult.Selected
        assertEquals("http://remote.example.com", selected.url)
        assertEquals(SelectionSource.FALLBACK, selected.source)
        // A debug bundle says why the local URL was passed over.
        assertEquals("Server error: HTTP 500", selected.localTestError)
    }

    @Test
    fun `plain http to the LAN passes the test, and to the internet is refused and said why`() = runTest {
        val guarded = httpClient.newBuilder().lanOnlyCleartext().build()
        val selector = UrlSelector(FakeNetworkDetector(atHome), guarded)

        // MockWebServer listens on 127.0.0.1: a local-network address, so http is allowed. (Its
        // url() names the host by its canonical name, which on some machines is not a local one.)
        mockServer.enqueue(MockResponse().setResponseCode(401))
        val lan = selector.selectUrl(
            HaConnectionSettings(
                localUrl = "http://127.0.0.1:${mockServer.port}",
                remoteUrl = "https://ha.example.com:8443",
                homeSubnets = home
            ),
        ) as UrlSelectionResult.Selected
        assertEquals(lan.localTestError.orEmpty(), SelectionSource.CONNECTION_TEST, lan.source)
        assertEquals(null, lan.localTestError)

        // An internet address over http (TEST-NET-3): refused before any connection is made.
        val internet = selector.selectUrl(
            HaConnectionSettings(
                localUrl = "http://203.0.113.7:8123",
                remoteUrl = "https://ha.example.com:8443",
                homeSubnets = home
            ),
        ) as UrlSelectionResult.Selected
        assertEquals("https://ha.example.com:8443", internet.url)
        assertEquals(SelectionSource.FALLBACK, internet.source)
        assertTrue(internet.localTestError.orEmpty(), internet.localTestError.orEmpty().contains("refused"))
    }

    @Test
    fun `selectUrl uses remoteUrl when on mobile data`() = runTest {
        val networkDetector = FakeNetworkDetector()
        val selector = UrlSelector(networkDetector, httpClient)

        val settings = HaConnectionSettings(
            localUrl = "http://local.example.com",
            remoteUrl = "http://remote.example.com"
        )

        val result = selector.selectUrl(settings)

        assertTrue(result is UrlSelectionResult.Selected)
        val selected = result as UrlSelectionResult.Selected
        assertEquals("http://remote.example.com", selected.url)
        assertEquals(SelectionSource.FALLBACK, selected.source)
    }

    @Test
    fun `selectUrl uses localUrl when only local is configured`() = runTest {
        val networkDetector = FakeNetworkDetector(atHome)
        val selector = UrlSelector(networkDetector, httpClient)

        val settings = HaConnectionSettings(
            localUrl = "http://local.example.com",
            remoteUrl = "",
            homeSubnets = home
        )

        val result = selector.selectUrl(settings)

        assertTrue(result is UrlSelectionResult.Selected)
        val selected = result as UrlSelectionResult.Selected
        assertEquals("http://local.example.com", selected.url)
        assertEquals(SelectionSource.FALLBACK, selected.source)
    }

    @Test
    fun `an http local URL is tested only on a home network`() = runTest {
        val home = listOf("192.0.2.0/24", "203.0.113.0/24")
        val local = "http://127.0.0.1:${mockServer.port}"
        val settings = HaConnectionSettings(
            localUrl = local,
            remoteUrl = "https://ha.example.com:8443",
            homeSubnets = home
        )

        // On the home Wi-Fi: the local URL is tested, and taken. The client's own 1 s timeouts ran
        // out under a full test run's load, which read as a failed test: this one waits longer.
        val patient = httpClient.newBuilder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        mockServer.enqueue(MockResponse().setResponseCode(401))
        val atHome = UrlSelector(FakeNetworkDetector(listOf("203.0.113.40/24")), patient)
            .selectUrl(settings) as UrlSelectionResult.Selected
        assertEquals(atHome.localTestError.orEmpty(), local, atHome.url)
        assertEquals(SelectionSource.CONNECTION_TEST, atHome.source)
        assertEquals(1, mockServer.requestCount)
        // Not /api/: HA counts an unauthenticated request there as a failed login, and bans the
        // address after ten (app 2.1.113 and 2.1.114 did that to the phone).
        val probe = mockServer.takeRequest()
        assertEquals("/manifest.json", probe.path)
        assertEquals(null, probe.getHeader("Authorization"))

        // Another Wi-Fi: not tested (nothing goes to it), the remote URL, and why.
        val away = UrlSelector(FakeNetworkDetector(listOf("192.168.1.23/24")), httpClient)
            .selectUrl(settings) as UrlSelectionResult.Selected
        assertEquals("https://ha.example.com:8443", away.url)
        assertEquals(SelectionSource.FALLBACK, away.source)
        assertEquals("not on a home network (phone: 192.168.1.23)", away.localTestError)
        assertEquals(1, mockServer.requestCount)

        // Mobile data with only the local URL: refused, not sent.
        val cellular = FakeNetworkDetector(emptyList())
        val onlyLocal = UrlSelector(cellular, httpClient).selectUrl(settings.copy(remoteUrl = ""))
        assertEquals("Local URL not used: not on a home network (phone: no Wi-Fi address)", (onlyLocal as UrlSelectionResult.Error).reason)
        assertEquals(1, mockServer.requestCount)
    }

    @Test
    fun `no home networks set refuses an http local URL, and an https one is not held to them`() = runTest {
        val why = "set your home networks to use a local http address"
        // Fails closed: even with the phone on the very network the server is on, nothing is sent.
        val onTheLan = UrlSelector(FakeNetworkDetector(atHome), httpClient)
        val onlyLocal = onTheLan.selectUrl(
            HaConnectionSettings(localUrl = "http://127.0.0.1:${mockServer.port}"),
        )
        assertEquals("Local URL not used: $why", (onlyLocal as UrlSelectionResult.Error).reason)
        val withRemote = onTheLan.selectUrl(
            HaConnectionSettings(localUrl = "http://127.0.0.1:${mockServer.port}", remoteUrl = "https://ha.example.com:8443"),
        ) as UrlSelectionResult.Selected
        assertEquals("https://ha.example.com:8443", withRemote.url)
        assertEquals(why, withRemote.localTestError)
        assertEquals(0, mockServer.requestCount)

        val cellular = FakeNetworkDetector(emptyList())
        val tls = UrlSelector(cellular, httpClient).selectUrl(
            HaConnectionSettings(
                localUrl = "https://ha.lan.example:8123",
                homeSubnets = listOf("203.0.113.0/24"),
            ),
        ) as UrlSelectionResult.Selected
        assertEquals("https://ha.lan.example:8123", tls.url)
        assertTrue(tls.localTestError.orEmpty(), !tls.localTestError.orEmpty().contains("home network"))
    }

    private class FakeNetworkDetector(
        private val networks: List<String> = emptyList(),
    ) : NetworkDetector {
        override suspend fun getLocalIpv4Networks(): List<String> = networks
    }
}
