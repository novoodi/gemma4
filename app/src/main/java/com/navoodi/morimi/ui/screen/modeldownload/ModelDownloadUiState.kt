package com.navoodi.morimi.ui.screen.modeldownload

sealed class ModelDownloadUiState {
    data object Idle : ModelDownloadUiState()
    data class Downloading(
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : ModelDownloadUiState()
    data object Complete : ModelDownloadUiState()
    data class Error(val message: String) : ModelDownloadUiState()
}
