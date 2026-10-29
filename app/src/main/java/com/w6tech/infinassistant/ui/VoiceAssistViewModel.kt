package com.w6tech.infinassistant.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.w6tech.infinassistant.core.data.HaTokenStore
import com.w6tech.infinassistant.core.data.SettingsRepository
import com.w6tech.infinassistant.core.data.UrlSelectionResult
import com.w6tech.infinassistant.core.data.UrlSelector
import com.w6tech.infinassistant.core.data.VoiceAuthGuard
import com.w6tech.infinassistant.core.ha.AssistAuthException
import com.w6tech.infinassistant.core.ha.AssistPipelineClient
import com.w6tech.infinassistant.core.ha.AssistPipelineList
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** What the Voice assist section knows of HA's Assist pipelines. */
sealed interface PipelineChoices {
    data object NotLoaded : PipelineChoices
    data object Loading : PipelineChoices
    data class Loaded(val list: AssistPipelineList) : PipelineChoices
    data class Failed(val reason: String) : PipelineChoices
}

/** The Voice assist section's settings and its pipeline list from HA. */
@HiltViewModel
class VoiceAssistViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val urlSelector: UrlSelector,
    private val tokenStore: HaTokenStore,
    private val assistClient: AssistPipelineClient,
    private val authGuard: VoiceAuthGuard,
) : ViewModel() {

    private val _pipelines = MutableStateFlow<PipelineChoices>(PipelineChoices.NotLoaded)
    val pipelines: StateFlow<PipelineChoices> = _pipelines

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setVoiceAssistEnabled(enabled) }
    }

    /** Chooses a pipeline; null is the one HA marks preferred. */
    fun setPipeline(pipelineId: String?) {
        viewModelScope.launch { settingsRepo.setVoiceAssistPipeline(pipelineId) }
    }

    /**
     * Asks HA for its pipelines, at the address URL selection picks (local or remote), with the
     * token: the list request is a websocket that authenticates before it asks.
     */
    fun loadPipelines() {
        if (_pipelines.value is PipelineChoices.Loading) return
        _pipelines.value = PipelineChoices.Loading
        viewModelScope.launch {
            _pipelines.value = try {
                val token = tokenStore.getToken()
                if (token.isNullOrBlank()) {
                    PipelineChoices.Failed("HA token not configured")
                } else if (authGuard.isRejected(token)) {
                    // HA refused this token already: no request until the token changes.
                    PipelineChoices.Failed(VoiceAuthGuard.REJECTED_MESSAGE)
                } else {
                    when (val url = urlSelector.selectUrl(settingsRepo.settings.first().ha)) {
                        is UrlSelectionResult.Error -> PipelineChoices.Failed(url.reason)
                        is UrlSelectionResult.Selected ->
                            PipelineChoices.Loaded(assistClient.listPipelines(url.url, token))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AssistAuthException) {
                tokenStore.getToken()?.let(authGuard::recordRejection)
                PipelineChoices.Failed(VoiceAuthGuard.REJECTED_MESSAGE)
            } catch (e: Exception) {
                PipelineChoices.Failed(e.message ?: "Could not reach Home Assistant")
            }
        }
    }
}
