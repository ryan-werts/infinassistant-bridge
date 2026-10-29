package com.w6tech.infinassistant.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.w6tech.infinassistant.core.common.AppResult
import com.w6tech.infinassistant.core.common.LocalNetwork
import com.w6tech.infinassistant.core.common.NetworkDetector
import com.w6tech.infinassistant.core.data.SettingsRepository
import com.w6tech.infinassistant.core.domain.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val networkDetector: NetworkDetector,
) : ViewModel() {

    val settings: StateFlow<BridgeSettings> = settingsRepo.settings.stateIn(
        viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BridgeSettings(),
    )

    fun setHaLocalUrl(url: String) = viewModelScope.launch { settingsRepo.setHaLocalUrl(url) }
    fun setHaRemoteUrl(url: String) = viewModelScope.launch { settingsRepo.setHaRemoteUrl(url) }
    fun setHomeSubnets(subnets: List<String>) = viewModelScope.launch { settingsRepo.setHomeSubnets(subnets) }

    /** The subnets of the Wi-Fi or Ethernet network the phone is on now, canonical: none on mobile data or a VPN. */
    suspend fun currentSubnets(): List<String> =
        networkDetector.getLocalIpv4Networks().mapNotNull(LocalNetwork::normalizeIpv4Subnet).distinct()
    fun setHaToken(token: String?) = viewModelScope.launch { settingsRepo.setHaToken(token) }
    fun addWatchDevice(address: String, name: String = "") = viewModelScope.launch {
        settingsRepo.addWatchDevice(WatchDevice(address = address, name = name))
    }
    fun removeWatchDevice(address: String) = viewModelScope.launch { settingsRepo.removeWatchDevice(address) }
    fun setPrimaryWatch(address: String) = viewModelScope.launch { settingsRepo.setPrimaryWatch(address) }
    fun setConnectedModeEnabled(enabled: Boolean) = viewModelScope.launch { settingsRepo.setConnectedModeEnabled(enabled) }

    fun setNotificationPrefs(prefs: NotificationPrefs) = viewModelScope.launch { settingsRepo.setNotificationPrefs(prefs) }
    fun setNotificationEventType(eventType: String) = viewModelScope.launch { settingsRepo.setNotificationEventType(eventType) }
    fun setInfinassistantEnabled(enabled: Boolean) = viewModelScope.launch { settingsRepo.setInfinassistantEnabled(enabled) }
    fun setInfinassistantWebhookId(url: String) = viewModelScope.launch { settingsRepo.setInfinassistantWebhookId(url) }
    fun setInfinassistantHmacSecret(secret: String) = viewModelScope.launch { settingsRepo.setInfinassistantHmacSecret(secret) }
    fun setInfinassistantReportInterval(seconds: Int) = viewModelScope.launch { settingsRepo.setInfinassistantReportInterval(seconds) }
    fun setDebugSharePath(path: String) = viewModelScope.launch { settingsRepo.setDebugSharePath(path) }
    fun setDebugShareCredentials(username: String, password: String) = viewModelScope.launch { settingsRepo.setDebugShareCredentials(username, password) }
}
