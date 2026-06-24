package com.navoodi.morimi.ui.screen.modeldownload

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import com.navoodi.morimi.MoimApp
import com.navoodi.morimi.service.ModelDownloadService
import kotlinx.coroutines.flow.StateFlow

class ModelDownloadViewModel(application: Application) : AndroidViewModel(application) {

    val downloadState: StateFlow<ModelDownloadUiState> = ModelDownloadService.state

    fun isWifiConnected(): Boolean {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        val nc = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun startDownload() {
        val intent = Intent(getApplication(), ModelDownloadService::class.java).apply {
            action = ModelDownloadService.ACTION_START
        }
        getApplication<Application>().startForegroundService(intent)
    }

    fun cancelDownload() {
        val intent = Intent(getApplication(), ModelDownloadService::class.java).apply {
            action = ModelDownloadService.ACTION_CANCEL
        }
        getApplication<Application>().startService(intent)
    }

    fun reinitializePipelines() {
        (getApplication() as MoimApp).reinitializePipelines()
    }
}
