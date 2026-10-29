package com.w6tech.infinassistant.core.data

import com.w6tech.infinassistant.core.common.LocalNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The home networks an http local URL may be used on (app 2.1.114), as stored and as typed. */
class HomeSubnetsTest {

    @Test
    fun `subnets are read in their canonical form, a bare address as a slash 32`() {
        assertEquals("203.0.113.0/24", LocalNetwork.normalizeIpv4Subnet(" 203.0.113.51/24 "))
        assertEquals("10.0.0.0/8", LocalNetwork.normalizeIpv4Subnet("10.1.2.3/8"))
        assertEquals("192.0.2.7/32", LocalNetwork.normalizeIpv4Subnet("192.0.2.7"))
        assertEquals("0.0.0.0/0", LocalNetwork.normalizeIpv4Subnet("8.8.8.8/0"))
        for (bad in listOf("", "192.0.2.0/33", "192.0.2.0/-1", "192.0.2/24", "192.0.02.0/24", "ha.local/24", "fd00::/8", "192.0.2.0/x")) {
            assertNull(bad, LocalNetwork.normalizeIpv4Subnet(bad))
        }
    }

    @Test
    fun `an address is inside a subnet by its network bits`() {
        assertTrue(LocalNetwork.isInIpv4Subnet("203.0.113.40", "203.0.113.0/24"))
        assertTrue(LocalNetwork.isInIpv4Subnet("198.51.100.255", "198.51.100.0/24"))
        assertFalse(LocalNetwork.isInIpv4Subnet("198.51.100.1", "203.0.113.0/24"))
        assertTrue(LocalNetwork.isInIpv4Subnet("172.20.10.4", "172.16.0.0/12"))
        assertFalse(LocalNetwork.isInIpv4Subnet("172.32.0.1", "172.16.0.0/12"))
        assertTrue(LocalNetwork.isInIpv4Subnet("203.0.113.9", "0.0.0.0/0"))
        assertFalse(LocalNetwork.isInIpv4Subnet("203.0.113.41", "203.0.113.40/32"))
        assertFalse(LocalNetwork.isInIpv4Subnet("not an address", "203.0.113.0/24"))
    }

    @Test
    fun `nothing stored is the default networks, and an empty value no network`() {
        val default = listOf("192.0.2.0/24", "198.51.100.0/24")
        assertEquals(default, HomeSubnets.parse(null, default))
        assertEquals(emptyList<String>(), HomeSubnets.parse("", default))
        assertEquals(listOf("192.168.1.0/24"), HomeSubnets.parse("192.168.1.9/24,junk,192.168.1.0/24", default))
        assertEquals("192.168.1.0/24,10.0.0.0/8", HomeSubnets.format(listOf("192.168.1.0/24", "nope", "10.9.9.9/8", "192.168.1.1/24")))
    }

    @Test
    fun `the built-in defaults name no home network and no share`() {
        val defaults = SettingsDefaults()
        assertEquals(emptyList<String>(), defaults.homeSubnets)
        assertEquals("", defaults.debugSharePath)
        assertEquals(emptyList<String>(), HomeSubnets.parse(null, defaults.homeSubnets))
    }

    @Test
    fun `typed entries are split on commas, spaces and lines, and the bad ones named`() {
        val (subnets, bad) = HomeSubnets.parseInput("192.0.2.0/24, 198.51.100.0/24\n203.0.113.1/24;  oops ,192.0.300.0/24")
        assertEquals(listOf("192.0.2.0/24", "198.51.100.0/24", "203.0.113.0/24"), subnets)
        assertEquals(listOf("oops", "192.0.300.0/24"), bad)
        assertEquals(emptyList<String>() to emptyList<String>(), HomeSubnets.parseInput("  "))
    }

    @Test
    fun `only an http local URL off the home networks is refused`() {
        val home = listOf("192.0.2.0/24", "198.51.100.0/24", "203.0.113.0/24")
        assertNull(HomeSubnets.localUrlRefusal("http://203.0.113.51:8123", home, listOf("203.0.113.40/24")))
        assertNull(HomeSubnets.localUrlRefusal("http://203.0.113.51:8123", home, listOf("10.0.0.2/8", "203.0.113.10/24")))
        assertEquals(
            "not on a home network (phone: 192.168.1.23)",
            HomeSubnets.localUrlRefusal("http://203.0.113.51:8123", home, listOf("192.168.1.23/24")),
        )
        assertEquals(
            "not on a home network (phone: no Wi-Fi address)",
            HomeSubnets.localUrlRefusal("HTTP://203.0.113.51:8123", home, emptyList()),
        )
        assertNull(HomeSubnets.localUrlRefusal("https://ha.example.com", home, emptyList()))
    }

    @Test
    fun `an http local URL with no home networks set is refused, whatever network the phone is on`() {
        val why = "set your home networks to use a local http address"
        assertEquals(why, HomeSubnets.NO_HOME_NETWORKS)
        assertEquals(why, HomeSubnets.localUrlRefusal("http://203.0.113.51:8123", emptyList(), emptyList()))
        assertEquals(why, HomeSubnets.localUrlRefusal("http://203.0.113.51:8123", emptyList(), listOf("203.0.113.40/24")))
        assertEquals(why, HomeSubnets.localUrlRefusal(" HTTP://homeassistant.local:8123", emptyList(), emptyList()))
        // Not held to the networks: https, and no URL at all.
        assertNull(HomeSubnets.localUrlRefusal("https://ha.lan.example:8123", emptyList(), emptyList()))
        assertNull(HomeSubnets.localUrlRefusal("", emptyList(), emptyList()))
    }
}
