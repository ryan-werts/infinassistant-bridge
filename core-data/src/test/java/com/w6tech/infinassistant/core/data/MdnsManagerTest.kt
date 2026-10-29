package com.w6tech.infinassistant.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** The port the mDNS advertisement carries: Android refuses a service without one ("The port is missing"). */
class MdnsManagerTest {

    @Test
    fun `the port is the URL's own, else 8123`() {
        assertEquals(8123, MdnsManager.portOf("http://homeassistant.local:8123/api/webhook/abc"))
        assertEquals(8443, MdnsManager.portOf("https://ha.example.net:8443/api/webhook/abc"))
        assertEquals(MdnsManager.DEFAULT_PORT, MdnsManager.portOf("http://homeassistant.local/api/webhook/abc"))
        assertEquals(MdnsManager.DEFAULT_PORT, MdnsManager.portOf("not a url"))
        assertEquals(MdnsManager.DEFAULT_PORT, MdnsManager.portOf(""))
    }

    @Test
    fun `an https URL with no port advertises 443, and an explicit port still wins`() {
        assertEquals(443, MdnsManager.portOf("https://ha.example.net/api/webhook/abc"))
        assertEquals(443, MdnsManager.portOf("  HTTPS://ha.example.net  "))
        assertEquals(8443, MdnsManager.portOf("https://ha.example.net:8443/api/webhook/abc"))
        assertEquals(8123, MdnsManager.portOf("https://ha.example.net:8123/api/webhook/abc"))
    }
}
