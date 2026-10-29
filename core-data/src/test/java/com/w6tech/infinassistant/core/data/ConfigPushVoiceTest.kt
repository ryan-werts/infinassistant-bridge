package com.w6tech.infinassistant.core.data

import com.w6tech.infinassistant.core.domain.BridgeSettings
import com.w6tech.infinassistant.core.domain.ConfigPushPayload
import com.w6tech.infinassistant.core.domain.ProtocolCbor
import com.w6tech.infinassistant.core.domain.VoiceAssistSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watch takes a button double-click for voice only when its last CONFIG_PUSH said voice assist
 * is on, so the payload the engine builds carries the app's switch.
 */
class ConfigPushVoiceTest {

    private fun wire(settings: BridgeSettings): String =
        String(
            ProtocolCbor.encode(ConfigPushPayload.serializer(), BridgeEngine.configPushPayload(3, settings)),
            Charsets.ISO_8859_1,
        )

    @Test
    fun `the payload carries the voice switch`() {
        val on = BridgeSettings(voiceAssist = VoiceAssistSettings(enabled = true))
        val off = BridgeSettings()
        assertTrue(BridgeEngine.configPushPayload(3, on).voiceAssist)
        assertFalse(BridgeEngine.configPushPayload(3, off).voiceAssist)
        assertEquals(3, BridgeEngine.configPushPayload(3, on).configVersion)
    }

    @Test
    fun `voice on is written as a top-level voiceAssist key and off is omitted`() {
        assertTrue(wire(BridgeSettings(voiceAssist = VoiceAssistSettings(enabled = true))).contains("voiceAssist"))
        assertFalse(wire(BridgeSettings()).contains("voiceAssist"))
    }

    @Test
    fun `the payload decodes back with the switch`() {
        val bytes = ProtocolCbor.encode(
            ConfigPushPayload.serializer(),
            BridgeEngine.configPushPayload(4, BridgeSettings(voiceAssist = VoiceAssistSettings(enabled = true))),
        )
        assertTrue(ProtocolCbor.decode(ConfigPushPayload.serializer(), bytes).voiceAssist)
    }
}
