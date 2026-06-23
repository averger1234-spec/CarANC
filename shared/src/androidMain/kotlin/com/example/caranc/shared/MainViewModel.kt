package com.example.caranc.shared

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow

/**
 * Android 平台專用的 ViewModel，使用 sessionContext 取得 stateManager（而非直接 singleton）。
 */
class MainViewModel(
    private val sessionContext: AncSessionContext = GlobalAncSessionContext
) : ViewModel() {
    val uiState: StateFlow<AncState> = sessionContext.stateManager.state
}
