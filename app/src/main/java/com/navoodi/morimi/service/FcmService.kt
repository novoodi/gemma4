package com.navoodi.morimi.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.navoodi.morimi.MainActivity
import com.navoodi.morimi.R
import com.navoodi.morimi.data.repository.FcmRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FcmService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FcmService"
        const val CHANNEL_ID = "morimi_chat"

        fun createNotificationChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "채팅 알림",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "채팅방 새 메시지 알림"
                enableVibration(true)
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        fun showAnalysisDoneNotification(context: Context, roomId: String, success: Boolean) {
            try {
                createNotificationChannel(context)
                val title = if (success) "AI 분석 완료" else "AI 분석 실패"
                val body  = if (success) "모임 추천이 준비됐어요. 탭해서 확인하세요."
                            else         "AI 분석 중 오류가 발생했어요. 다시 시도해 주세요."
                val notifId = roomId.hashCode()
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("roomId", roomId)
                    putExtra("navTo", "aiReport")
                }
                val pendingIntent = PendingIntent.getActivity(
                    context, notifId, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .build()
                context.getSystemService(NotificationManager::class.java).notify(notifId, notification)
                Log.d(TAG, "AI 분석 알림 표시: success=$success roomId=$roomId")
            } catch (e: Exception) {
                Log.w(TAG, "showAnalysisDoneNotification 실패", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel(this)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "새 FCM 토큰 발급됨 — Firestore 갱신 시작")
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            FcmRepository.saveToken(token)
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val title = remoteMessage.notification?.title ?: "모이미"
        val body  = remoteMessage.notification?.body  ?: return   // body 없으면 표시 불필요
        val roomId = remoteMessage.data["roomId"]

        // 방마다 독립 알림 — 같은 방에서 온 새 알림은 이전 알림을 갱신
        val notifId = roomId?.hashCode() ?: title.hashCode()

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (roomId != null) putExtra("roomId", roomId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            notifId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        getSystemService(NotificationManager::class.java).notify(notifId, notification)
        Log.d(TAG, "포그라운드 알림 표시: title=$title roomId=$roomId")
    }
}
