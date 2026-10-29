package com.w6tech.infinassistant.bridge

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which foreground-service types the bridge service starts with: the microphone only when the
 * permission is granted and the start is not a background start, so the boot start (which Android
 * 15 refuses a microphone type) keeps working without it.
 */
class ForegroundTypesTest {

    @Test
    fun `permission granted and an app start gives connectedDevice and microphone`() {
        val types = ForegroundTypes.select(recordAudioGranted = true, backgroundStart = false)
        assertEquals(ForegroundTypes.CONNECTED_DEVICE or ForegroundTypes.MICROPHONE, types)
        assertTrue(ForegroundTypes.hasMicrophone(types))
    }

    @Test
    fun `a boot start gets connectedDevice only even with the permission`() {
        val types = ForegroundTypes.select(recordAudioGranted = true, backgroundStart = true)
        assertEquals(ForegroundTypes.CONNECTED_DEVICE, types)
        assertFalse(ForegroundTypes.hasMicrophone(types))
    }

    @Test
    fun `no permission gets connectedDevice only`() {
        assertEquals(
            ForegroundTypes.CONNECTED_DEVICE,
            ForegroundTypes.select(recordAudioGranted = false, backgroundStart = false),
        )
        assertEquals(
            ForegroundTypes.CONNECTED_DEVICE,
            ForegroundTypes.select(recordAudioGranted = false, backgroundStart = true),
        )
    }

    @Test
    fun `connectedDevice is in every choice`() {
        for (granted in listOf(true, false)) for (background in listOf(true, false)) {
            val types = ForegroundTypes.select(granted, background)
            assertTrue("granted=$granted background=$background", (types and ForegroundTypes.CONNECTED_DEVICE) != 0)
        }
    }

    @Test
    fun `a security exception is a type refusal and a plain bug is not`() {
        assertTrue(ForegroundTypes.isTypeRefusal(SecurityException("microphone"), Build.VERSION_CODES.VANILLA_ICE_CREAM))
        assertFalse(ForegroundTypes.isTypeRefusal(IllegalArgumentException("bad"), Build.VERSION_CODES.VANILLA_ICE_CREAM))
        assertFalse(ForegroundTypes.isTypeRefusal(NullPointerException(), Build.VERSION_CODES.S))
    }
}
