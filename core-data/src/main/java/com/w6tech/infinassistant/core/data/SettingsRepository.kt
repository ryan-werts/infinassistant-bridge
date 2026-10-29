package com.w6tech.infinassistant.core.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.w6tech.infinassistant.core.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

interface SettingsRepository {
    val settings: Flow<BridgeSettings>

    suspend fun setHaLocalUrl(url: String)
    suspend fun setHaRemoteUrl(url: String)
    /** The home networks an http local URL may be used on ([HomeSubnets]); empty for any network. */
    suspend fun setHomeSubnets(subnets: List<String>)
    suspend fun setHaToken(token: String?)
    suspend fun addWatchDevice(device: WatchDevice)
    suspend fun removeWatchDevice(address: String)
    suspend fun setPrimaryWatch(address: String)
    suspend fun setConnectedModeEnabled(enabled: Boolean)

    suspend fun setNotificationPrefs(prefs: NotificationPrefs)
    suspend fun setNotificationEventType(eventType: String)

    suspend fun setInfinassistantEnabled(enabled: Boolean)
    suspend fun setInfinassistantWebhookId(id: String)
    /** URL-safe base64 HMAC secret from the integration's setup QR payload. */
    suspend fun setInfinassistantHmacSecret(secret: String)
    suspend fun setInfinassistantReportInterval(seconds: Int)

    suspend fun setVoiceAssistEnabled(enabled: Boolean)
    /** The Assist pipeline voice uses; null for the one HA marks as preferred. */
    suspend fun setVoiceAssistPipeline(pipelineId: String?)

    suspend fun setLastPushedWatchFaceJson(json: String?)
    suspend fun setLastResolvedWatchFaceLayoutJson(json: String?)
    suspend fun setDebugSharePath(path: String)
    suspend fun setDebugShareCredentials(username: String, password: String)
}

private val Context.dataStore by preferencesDataStore(name = "bridge_settings")

class DataStoreSettingsRepository(
    private val appContext: Context,
    private val tokenStore: HaTokenStore,
    private val defaults: SettingsDefaults = SettingsDefaults(),
) : SettingsRepository {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private fun validateUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return ""
        require(trimmed.length <= 2048) { "URL too long (max 2048 chars)" }
        val parsed = java.net.URL(trimmed)  // throws MalformedURLException
        require(parsed.protocol in listOf("http", "https")) { "Only http/https URLs supported" }
        return trimmed
    }

    override val settings: Flow<BridgeSettings> = appContext.dataStore.data.map { p ->
        // Migration logic: if legacy baseUrl exists but new fields don't, migrate
        val legacyBaseUrl = p[Keys.HA_URL] ?: ""
        val localUrl = p[Keys.HA_LOCAL_URL] ?: ""
        val remoteUrl = p[Keys.HA_REMOTE_URL] ?: ""

        val (migratedLocalUrl, migratedRemoteUrl) = if (legacyBaseUrl.isNotBlank() && localUrl.isBlank() && remoteUrl.isBlank()) {
            // Migrate: Heuristic - if contains .local or IP address = local, else = remote
            if (legacyBaseUrl.contains(".local") || legacyBaseUrl.matches(Regex(".*://\\d+\\.\\d+\\.\\d+\\.\\d+.*"))) {
                Pair(legacyBaseUrl, "")
            } else {
                Pair("", legacyBaseUrl)
            }
        } else {
            Pair(localUrl, remoteUrl)
        }

        // Parse device list; migrate from single device if needed
        val devicesJson = p[Keys.DEVICES_JSON]
        val legacyAddr = p[Keys.DEVICE_ADDR]
        val devices: List<WatchDevice> = when {
            devicesJson != null -> json.decodeFromString(ListSerializer(WatchDevice.serializer()), devicesJson)
            legacyAddr != null -> listOf(WatchDevice(address = legacyAddr, isPrimary = true))
            else -> emptyList()
        }

        BridgeSettings(
            ha = HaConnectionSettings(
                localUrl = migratedLocalUrl,
                remoteUrl = migratedRemoteUrl,
                homeSubnets = HomeSubnets.parse(p[Keys.HOME_SUBNETS], defaults.homeSubnets),
                hasToken = tokenStore.getToken() != null,
            ),
            associatedDeviceAddress = legacyAddr,
            associatedDevices = devices,
            connectedModeEnabled = p[Keys.CONNECTED_MODE] ?: false,
            notificationPrefs = p[Keys.NOTIF_PREFS_JSON]?.let { json.decodeFromString(NotificationPrefs.serializer(), it) }
                ?: NotificationPrefs(),
            notificationEventType = p[Keys.NOTIF_EVENT_TYPE] ?: "pt_bridge_notify",
            infinassistant = p[Keys.INFINASSISTANT_JSON]?.let { json.decodeFromString(InfinassistantSettings.serializer(), it) }
                ?: InfinassistantSettings(),
            voiceAssist = decodeVoiceAssist(p[Keys.VOICE_ASSIST_JSON]),
            lastPushedWatchFaceJson = p[Keys.LAST_PUSHED_WATCHFACE_JSON],
            lastResolvedWatchFaceLayoutJson = p[Keys.LAST_RESOLVED_LAYOUT_JSON],
            debugSharePath = p[Keys.DEBUG_SHARE_PATH] ?: defaults.debugSharePath,
            debugShareUsername = p[Keys.DEBUG_SHARE_USERNAME] ?: "",
            debugSharePassword = p[Keys.DEBUG_SHARE_PASSWORD] ?: "",
        )
    }

    override suspend fun setHaLocalUrl(url: String) {
        appContext.dataStore.edit { it[Keys.HA_LOCAL_URL] = validateUrl(url) }
    }

    override suspend fun setHaRemoteUrl(url: String) {
        val validated = validateUrl(url)
        if (validated.isNotBlank()) {
            require(validated.startsWith("https://")) { "Remote URLs must use HTTPS" }
        }
        appContext.dataStore.edit { it[Keys.HA_REMOTE_URL] = validated }
    }

    override suspend fun setHomeSubnets(subnets: List<String>) {
        appContext.dataStore.edit { it[Keys.HOME_SUBNETS] = HomeSubnets.format(subnets) }
    }

    override suspend fun setHaToken(token: String?) {
        tokenStore.setToken(token)
        // Force downstream collectors to refresh by touching a key.
        appContext.dataStore.edit { it[Keys.TOKEN_PRESENT] = (token != null).toString() }
    }

    override suspend fun addWatchDevice(device: WatchDevice) {
        appContext.dataStore.edit { prefs ->
            val current = prefs[Keys.DEVICES_JSON]?.let {
                json.decodeFromString(ListSerializer(WatchDevice.serializer()), it)
            }?.toMutableList() ?: run {
                // Migrate from legacy single device if present
                val legacy = prefs[Keys.DEVICE_ADDR]
                if (legacy != null) mutableListOf(WatchDevice(address = legacy, isPrimary = true))
                else mutableListOf()
            }
            // Don't add duplicates
            if (current.none { it.address == device.address }) {
                // If this is the first device, make it primary
                val toAdd = if (current.isEmpty()) device.copy(isPrimary = true) else device
                current.add(toAdd)
            }
            prefs[Keys.DEVICES_JSON] = json.encodeToString(ListSerializer(WatchDevice.serializer()), current)
            // Keep legacy field in sync with primary device
            val primary = current.firstOrNull { it.isPrimary } ?: current.firstOrNull()
            if (primary != null) prefs[Keys.DEVICE_ADDR] = primary.address else prefs.remove(Keys.DEVICE_ADDR)
        }
    }

    override suspend fun removeWatchDevice(address: String) {
        appContext.dataStore.edit { prefs ->
            val current = prefs[Keys.DEVICES_JSON]?.let {
                json.decodeFromString(ListSerializer(WatchDevice.serializer()), it)
            }?.toMutableList() ?: return@edit
            val wasPrimary = current.removeAll { it.address == address }
            // If we removed the primary, promote the first remaining device
            if (wasPrimary && current.isNotEmpty() && current.none { it.isPrimary }) {
                current[0] = current[0].copy(isPrimary = true)
            }
            prefs[Keys.DEVICES_JSON] = json.encodeToString(ListSerializer(WatchDevice.serializer()), current)
            val primary = current.firstOrNull { it.isPrimary } ?: current.firstOrNull()
            if (primary != null) prefs[Keys.DEVICE_ADDR] = primary.address else prefs.remove(Keys.DEVICE_ADDR)
        }
    }

    override suspend fun setPrimaryWatch(address: String) {
        appContext.dataStore.edit { prefs ->
            val current = prefs[Keys.DEVICES_JSON]?.let {
                json.decodeFromString(ListSerializer(WatchDevice.serializer()), it)
            } ?: return@edit
            val updated = current.map { it.copy(isPrimary = it.address == address) }
            prefs[Keys.DEVICES_JSON] = json.encodeToString(ListSerializer(WatchDevice.serializer()), updated)
            prefs[Keys.DEVICE_ADDR] = address
        }
    }

    override suspend fun setConnectedModeEnabled(enabled: Boolean) {
        appContext.dataStore.edit { it[Keys.CONNECTED_MODE] = enabled }
    }

    override suspend fun setNotificationPrefs(prefs: NotificationPrefs) {
        appContext.dataStore.edit { it[Keys.NOTIF_PREFS_JSON] = json.encodeToString(NotificationPrefs.serializer(), prefs) }
    }

    override suspend fun setNotificationEventType(eventType: String) {
        appContext.dataStore.edit { it[Keys.NOTIF_EVENT_TYPE] = eventType.trim() }
    }

    override suspend fun setInfinassistantEnabled(enabled: Boolean) {
        appContext.dataStore.edit { prefs ->
            val current = prefs[Keys.INFINASSISTANT_JSON]?.let {
                json.decodeFromString(InfinassistantSettings.serializer(), it)
            } ?: InfinassistantSettings()

            val updated = current.copy(enabled = enabled)
            prefs[Keys.INFINASSISTANT_JSON] = json.encodeToString(InfinassistantSettings.serializer(), updated)
        }
    }

    override suspend fun setInfinassistantWebhookId(id: String) {
        appContext.dataStore.edit { prefs ->
            val current = prefs[Keys.INFINASSISTANT_JSON]?.let {
                json.decodeFromString(InfinassistantSettings.serializer(), it)
            } ?: InfinassistantSettings()

            val updated = current.copy(webhookId = id.trim())
            prefs[Keys.INFINASSISTANT_JSON] = json.encodeToString(InfinassistantSettings.serializer(), updated)
        }
    }

    override suspend fun setInfinassistantHmacSecret(secret: String) {
        appContext.dataStore.edit { prefs ->
            val current = prefs[Keys.INFINASSISTANT_JSON]?.let {
                json.decodeFromString(InfinassistantSettings.serializer(), it)
            } ?: InfinassistantSettings()

            val updated = current.copy(hmacSecret = secret.trim())
            prefs[Keys.INFINASSISTANT_JSON] = json.encodeToString(InfinassistantSettings.serializer(), updated)
        }
    }

    override suspend fun setInfinassistantReportInterval(seconds: Int) {
        appContext.dataStore.edit { prefs ->
            val current = prefs[Keys.INFINASSISTANT_JSON]?.let {
                json.decodeFromString(InfinassistantSettings.serializer(), it)
            } ?: InfinassistantSettings()

            val updated = current.copy(sensorReportIntervalSeconds = seconds)
            prefs[Keys.INFINASSISTANT_JSON] = json.encodeToString(InfinassistantSettings.serializer(), updated)
        }
    }

    override suspend fun setVoiceAssistEnabled(enabled: Boolean) {
        updateVoiceAssist { it.copy(enabled = enabled) }
    }

    override suspend fun setVoiceAssistPipeline(pipelineId: String?) {
        updateVoiceAssist { it.copy(pipelineId = pipelineId?.trim()?.takeIf { id -> id.isNotEmpty() }) }
    }

    /** The stored voice settings; a corrupt value falls back to the defaults (voice off). */
    private fun decodeVoiceAssist(raw: String?): VoiceAssistSettings =
        raw?.let { runCatching { json.decodeFromString(VoiceAssistSettings.serializer(), it) }.getOrNull() }
            ?: VoiceAssistSettings()

    private suspend fun updateVoiceAssist(change: (VoiceAssistSettings) -> VoiceAssistSettings) {
        appContext.dataStore.edit { prefs ->
            val current = decodeVoiceAssist(prefs[Keys.VOICE_ASSIST_JSON])
            prefs[Keys.VOICE_ASSIST_JSON] = json.encodeToString(VoiceAssistSettings.serializer(), change(current))
        }
    }

    override suspend fun setLastPushedWatchFaceJson(json: String?) {
        appContext.dataStore.edit {
            if (json == null) it.remove(Keys.LAST_PUSHED_WATCHFACE_JSON) else it[Keys.LAST_PUSHED_WATCHFACE_JSON] = json
        }
    }

    override suspend fun setLastResolvedWatchFaceLayoutJson(json: String?) {
        appContext.dataStore.edit {
            if (json == null) it.remove(Keys.LAST_RESOLVED_LAYOUT_JSON) else it[Keys.LAST_RESOLVED_LAYOUT_JSON] = json
        }
    }

    override suspend fun setDebugSharePath(path: String) {
        appContext.dataStore.edit { it[Keys.DEBUG_SHARE_PATH] = path.trim() }
    }

    override suspend fun setDebugShareCredentials(username: String, password: String) {
        appContext.dataStore.edit {
            it[Keys.DEBUG_SHARE_USERNAME] = username.trim()
            it[Keys.DEBUG_SHARE_PASSWORD] = password
        }
    }

    private object Keys {
        val HA_URL = stringPreferencesKey("ha_url")  // Legacy - kept for migration
        val HA_LOCAL_URL = stringPreferencesKey("ha_local_url")
        val HA_REMOTE_URL = stringPreferencesKey("ha_remote_url")
        val HOME_SUBNETS = stringPreferencesKey("home_subnets")  // Comma-separated CIDRs; absent = the supplied default
        val TOKEN_PRESENT = stringPreferencesKey("ha_token_present")
        val DEVICE_ADDR = stringPreferencesKey("device_addr")
        val CONNECTED_MODE = booleanPreferencesKey("connected_mode")
        val NOTIF_PREFS_JSON = stringPreferencesKey("notif_prefs_json")
        val NOTIF_EVENT_TYPE = stringPreferencesKey("notif_event_type")
        val INFINASSISTANT_JSON = stringPreferencesKey("infinassistant_json")
        val VOICE_ASSIST_JSON = stringPreferencesKey("voice_assist_json")
        val LAST_PUSHED_WATCHFACE_JSON = stringPreferencesKey("last_pushed_watchface_json")
        val LAST_RESOLVED_LAYOUT_JSON = stringPreferencesKey("last_resolved_layout_json")
        val DEVICES_JSON = stringPreferencesKey("associated_devices_json")
        val DEBUG_SHARE_PATH = stringPreferencesKey("debug_share_path")
        val DEBUG_SHARE_USERNAME = stringPreferencesKey("debug_share_username")
        val DEBUG_SHARE_PASSWORD = stringPreferencesKey("debug_share_password")
    }
}
