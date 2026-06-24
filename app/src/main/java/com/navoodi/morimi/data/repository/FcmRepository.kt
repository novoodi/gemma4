package com.navoodi.morimi.data.repository

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

object FcmRepository {
    private const val TAG = "FcmRepository"
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    suspend fun saveToken(token: String) {
        val uid = auth.currentUser?.uid ?: run {
            Log.w(TAG, "saveToken: 로그인 사용자 없음 — 저장 건너뜀")
            return
        }
        try {
            db.collection("users").document(uid)
                .set(mapOf("fcmTokens" to FieldValue.arrayUnion(token)), SetOptions.merge())
                .await()
            Log.d(TAG, "FCM 토큰 저장 완료 uid=$uid")
        } catch (e: Exception) {
            Log.e(TAG, "FCM 토큰 저장 실패", e)
        }
    }

    suspend fun fetchAndSaveToken() {
        try {
            val token = FirebaseMessaging.getInstance().token.await()
            saveToken(token)
        } catch (e: Exception) {
            Log.e(TAG, "FCM 토큰 발급 실패", e)
        }
    }
}
