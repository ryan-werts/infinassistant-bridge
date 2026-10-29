package com.w6tech.infinassistant.ui

import com.w6tech.infinassistant.core.ha.HaDeviceRegistryEntry
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InfinAssistantDeviceFilterTest {

    private fun device(identifiersJson: String) = HaDeviceRegistryEntry(
        id = "device-1",
        identifiersRaw = Json.parseToJsonElement(identifiersJson),
    )

    @Test
    fun deviceFromTheInfinAssistantIntegration_matches() {
        assertTrue(isInfinAssistantDevice(device("""[["infinassistant","aa:bb:cc"]]""")))
    }

    @Test
    fun deviceFromTheOldDomain_doesNotMatch() {
        assertFalse(isInfinAssistantDevice(device("""[["infinisstant","aa:bb:cc"]]""")))
    }

    @Test
    fun deviceFromAnotherIntegration_doesNotMatch() {
        assertFalse(isInfinAssistantDevice(device("""[["hue","aa:bb:cc"]]""")))
    }

    @Test
    fun deviceWithoutIdentifiers_doesNotMatch() {
        assertFalse(isInfinAssistantDevice(HaDeviceRegistryEntry(id = "device-2")))
    }
}
