package com.w6tech.infinassistant.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.w6tech.infinassistant.core.data.appearance.ThemeMode
import com.w6tech.infinassistant.core.data.appearance.ThemeModeStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Exposes the stored theme mode. The store is the only source of truth: no optimistic copy is
 * held, so a failed write leaves the previous value on screen.
 */
@HiltViewModel
class AppearanceViewModel @Inject constructor(
    private val themeModeStore: ThemeModeStore,
) : ViewModel() {

    /** The stored mode; null until the store has delivered its first value. */
    val mode: StateFlow<ThemeMode?> = themeModeStore.mode.stateIn(
        viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )

    fun setMode(mode: ThemeMode) {
        viewModelScope.launch { themeModeStore.setMode(mode) }
    }
}
