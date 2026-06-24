package com.navoodi.morimi.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.navoodi.morimi.ui.screen.modeldownload.ModelDownloadUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class ModelDownloadService : Service() {

    companion object {
        val state = MutableStateFlow<ModelDownloadUiState>(ModelDownloadUiState.Idle)

        const val DOWNLOAD_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
        const val EXPECTED_SIZE = 2_588_147_712L

        private const val CHANNEL_ID = "model_download_channel"
        private const val NOTIF_ID = 9001

        const val ACTION_START = "com.navoodi.morimi.ACTION_MODEL_DOWNLOAD_START"
        const val ACTION_CANCEL = "com.navoodi.morimi.ACTION_MODEL_DOWNLOAD_CANCEL"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startDownload()
            ACTION_CANCEL -> cancelDownload()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun startDownload() {
        if (downloadJob?.isActive == true) return

        startForeground(
            NOTIF_ID,
            buildNotification(0f, 0L, EXPECTED_SIZE),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        state.value = ModelDownloadUiState.Downloading(0f, 0L, EXPECTED_SIZE)

        downloadJob = serviceScope.launch {
            try {
                val modelsDir = getExternalFilesDir("models")
                    ?: throw Exception("외부 저장소를 사용할 수 없습니다")
                modelsDir.mkdirs()

                val partFile = File(modelsDir, "${LlmService.MODEL_FILENAME}.part")
                val destFile = File(modelsDir, LlmService.MODEL_FILENAME)

                val totalBytes = downloadToPartFile(partFile)

                // .part → 최종 파일로 이동
                if (!partFile.renameTo(destFile)) {
                    try {
                        partFile.copyTo(destFile, overwrite = true)
                        partFile.delete()
                    } catch (e: Exception) {
                        destFile.delete()
                        throw Exception("파일 이동 실패: ${e.message}")
                    }
                }

                state.value = ModelDownloadUiState.Complete
                updateNotification(1f, totalBytes, totalBytes, complete = true)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } catch (e: CancellationException) {
                // .part 파일은 유지 — 다음 실행 시 이어받기용
                state.value = ModelDownloadUiState.Idle
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } catch (e: Exception) {
                Log.e("ModelDownloadService", "다운로드 실패", e)
                state.value = ModelDownloadUiState.Error(e.message ?: "알 수 없는 오류")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * HTTP Range 이어받기를 지원하는 다운로드.
     * partFile에 누적 저장하고 크기 검증 후 totalBytes 반환.
     */
    private suspend fun downloadToPartFile(partFile: File): Long {
        val existingSize = if (partFile.exists()) partFile.length() else 0L

        val connection = (URL(DOWNLOAD_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "MorimiApp/1.0")
            if (existingSize > 0) setRequestProperty("Range", "bytes=$existingSize-")
        }
        connection.connect()

        val responseCode = connection.responseCode
        val append: Boolean
        val totalBytes: Long

        when (responseCode) {
            206 -> {
                append = true
                // Content-Range: bytes start-end/total
                val contentRange = connection.getHeaderField("Content-Range")
                totalBytes = contentRange?.substringAfterLast("/")?.trim()?.toLongOrNull()
                    ?: (connection.contentLengthLong + existingSize).takeIf { it > existingSize }
                    ?: EXPECTED_SIZE
            }
            200 -> {
                append = false
                partFile.delete()
                totalBytes = connection.contentLengthLong.takeIf { it > 0 } ?: EXPECTED_SIZE
            }
            else -> {
                connection.disconnect()
                throw Exception("서버 오류: HTTP $responseCode")
            }
        }

        val input = connection.inputStream.buffered(65_536)
        val output = FileOutputStream(partFile, append)
        var downloadedBytes = if (append) existingSize else 0L
        var lastNotifPct = -1
        val buffer = ByteArray(65_536)
        var bytesRead: Int

        try {
            while (input.read(buffer).also { bytesRead = it } != -1) {
                currentCoroutineContext().ensureActive()
                output.write(buffer, 0, bytesRead)
                downloadedBytes += bytesRead

                val progress = downloadedBytes.toFloat() / totalBytes.toFloat()
                state.value = ModelDownloadUiState.Downloading(progress, downloadedBytes, totalBytes)

                // 알림은 1% 단위로만 업데이트 (과도한 IPC 방지)
                val pct = (progress * 100).toInt()
                if (pct != lastNotifPct) {
                    lastNotifPct = pct
                    updateNotification(progress, downloadedBytes, totalBytes)
                }
            }
        } finally {
            runCatching { output.flush(); output.close() }
            runCatching { input.close() }
            connection.disconnect()
        }

        // 크기 검증: 불일치 시 깨진 파일 삭제
        if (partFile.length() != totalBytes) {
            partFile.delete()
            throw Exception(
                "파일 크기 불일치 (${partFile.length()} vs $totalBytes bytes). " +
                "다시 시도하면 처음부터 다운로드됩니다."
            )
        }

        return totalBytes
    }

    private fun cancelDownload() {
        downloadJob?.cancel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AI 모델 다운로드",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "AI 모델 파일 다운로드 진행 상황" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(
        progress: Float,
        downloadedBytes: Long,
        totalBytes: Long,
        complete: Boolean = false,
    ): Notification {
        val pct = (progress * 100).toInt()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(
                if (complete) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_sys_download
            )
            .setContentTitle(
                if (complete) "AI 모델 다운로드 완료" else "AI 모델 다운로드 중…"
            )
            .setContentText(
                if (complete) "다운로드가 완료되었습니다"
                else "$pct%  ·  ${formatSize(downloadedBytes)} / ${formatSize(totalBytes)}"
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(!complete)
            .apply { if (!complete) setProgress(100, pct, false) }
            .build()
    }

    private fun updateNotification(
        progress: Float,
        downloadedBytes: Long,
        totalBytes: Long,
        complete: Boolean = false,
    ) {
        val notif = buildNotification(progress, downloadedBytes, totalBytes, complete)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif)
    }

    private fun formatSize(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 0.1) "%.1f GB".format(gb) else "${bytes / (1024 * 1024)} MB"
    }
}
