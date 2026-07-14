package com.navoodi.morimi.data.repository

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.navoodi.morimi.data.model.ChatRoom
import com.navoodi.morimi.data.model.MeetingSummary
import com.navoodi.morimi.data.model.Message
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

sealed class JoinResult {
    data class Success(val roomId: String) : JoinResult()
    data object NotFound : JoinResult()
    data object InvalidFormat : JoinResult()
}

object ChatRepository {
    private const val TAG = "ChatRepository"
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    private const val INVITE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val INVITE_CODE_REGEX = Regex("^[A-HJ-NP-Z2-9]{6}$")

    private fun generateInviteCode(): String =
        (1..6).map { INVITE_ALPHABET.random() }.joinToString("")

    // 시각 필드를 epoch millis로 읽는다.
    // 신규 데이터는 서버 Timestamp, 아직 서버 확정 전 로컬 쓰기는 ESTIMATE로 추정값을 채우며,
    // 레거시 데이터(Long millis)도 함께 지원한다.
    private fun DocumentSnapshot.millisField(field: String): Long =
        when (val v = get(field, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)) {
            is Timestamp -> v.toDate().time
            is Number    -> v.toLong()
            else         -> 0L
        }

    val currentUid: String? get() = auth.currentUser?.uid

    // 요약 결과 읽기 모델 (인메모리 StateFlow) — 영속은 SummaryRepository가 담당,
    // 앱 시작 시 hydrateSummaries()로 복원된다
    private val _summaries = MutableStateFlow<Map<String, MeetingSummary>>(emptyMap())
    val summaries: StateFlow<Map<String, MeetingSummary>> = _summaries.asStateFlow()

    // ── 방 목록: participantUids 배열에 내 uid 포함된 방 실시간 구독 ──────────

    fun getRoomsFlow(): Flow<List<ChatRoom>> = callbackFlow {
        val uid = currentUid
        if (uid == null) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }
        val listener = db.collection("rooms")
            .whereArrayContains("participantUids", uid)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.e(TAG, "rooms 구독 오류", err)
                    return@addSnapshotListener
                }
                val rooms = snap?.documents?.mapNotNull { doc ->
                    runCatching {
                        ChatRoom(
                            id = doc.id,
                            name = doc.getString("name") ?: return@mapNotNull null,
                            participantUids = (doc.get("participantUids") as? List<*>)
                                ?.filterIsInstance<String>() ?: emptyList(),
                            createdAt = doc.getLong("createdAt") ?: 0L,
                            inviteCode = doc.getString("inviteCode") ?: "",
                            lastMessage = doc.getString("lastMessage") ?: "",
                            lastMessageTime = doc.millisField("lastMessageTime"),
                        )
                    }.getOrNull()
                } ?: emptyList()
                // lastMessageTime 내림차순 정렬 (복합 인덱스 없이 클라이언트 정렬)
                trySend(rooms.sortedByDescending { it.lastMessageTime })
            }
        awaitClose { listener.remove() }
    }

    // ── 단일 방 실시간 구독 ───────────────────────────────────────────────────

    fun getRoomFlow(roomId: String): Flow<ChatRoom?> = callbackFlow {
        val listener = db.collection("rooms").document(roomId)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.e(TAG, "room 구독 오류 roomId=$roomId", err)
                    return@addSnapshotListener
                }
                val room = if (snap?.exists() == true) runCatching {
                    ChatRoom(
                        id = snap.id,
                        name = snap.getString("name") ?: "",
                        participantUids = (snap.get("participantUids") as? List<*>)
                            ?.filterIsInstance<String>() ?: emptyList(),
                        createdAt = snap.getLong("createdAt") ?: 0L,
                        inviteCode = snap.getString("inviteCode") ?: ""
                    )
                }.getOrNull() else null
                trySend(room)
            }
        awaitClose { listener.remove() }
    }

    // ── 메시지 실시간 구독 (timestamp 오름차순) ──────────────────────────────

    fun getMessagesFlow(roomId: String): Flow<List<Message>> = callbackFlow {
        val listener = db.collection("rooms").document(roomId)
            .collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.e(TAG, "messages 구독 오류 roomId=$roomId", err)
                    return@addSnapshotListener
                }
                val messages = snap?.documents?.mapNotNull { doc ->
                    runCatching {
                        Message(
                            id = doc.id,
                            roomId = roomId,
                            senderId = doc.getString("senderId") ?: return@mapNotNull null,
                            senderName = doc.getString("senderName") ?: "",
                            content = doc.getString("content") ?: "",
                            timestamp = doc.millisField("timestamp")
                        )
                    }.getOrNull()
                } ?: emptyList()
                trySend(messages)
            }
        awaitClose { listener.remove() }
    }

    // ── 방 생성: 현재 uid를 participantUids에 포함 ───────────────────────────

    suspend fun createRoom(name: String): ChatRoom? {
        val uid = currentUid ?: run {
            Log.e(TAG, "createRoom: 로그인 사용자 없음")
            return null
        }
        return try {
            // 코드 중복 방지: inviteCodes에 이미 존재하면 재생성
            var code: String
            do {
                code = generateInviteCode()
            } while (db.collection("inviteCodes").document(code).get().await().exists())

            val ref = db.collection("rooms").add(
                hashMapOf(
                    "name" to name,
                    "participantUids" to listOf(uid),
                    "createdAt" to System.currentTimeMillis(),
                    "inviteCode" to code
                )
            ).await()

            // inviteCodes/{code} 문서에 roomId만 저장
            db.collection("inviteCodes").document(code)
                .set(hashMapOf("roomId" to ref.id))
                .await()

            Log.d(TAG, "방 생성 완료 id=${ref.id} code=$code")
            ChatRoom(id = ref.id, name = name, participantUids = listOf(uid), inviteCode = code)
        } catch (e: Exception) {
            Log.e(TAG, "방 생성 실패", e)
            null
        }
    }

    // ── 초대 코드로 방 입장 ─────────────────────────────────────────────────

    suspend fun joinRoomByCode(rawCode: String): JoinResult {
        val code = rawCode.trim().uppercase()
        if (!code.matches(INVITE_CODE_REGEX)) {
            return JoinResult.InvalidFormat
        }
        val uid = currentUid ?: run {
            Log.e(TAG, "joinRoomByCode: 로그인 사용자 없음")
            return JoinResult.NotFound
        }
        return try {
            // rooms 쿼리 대신 inviteCodes/{code} 정확한 경로로 get
            val codeDoc = db.collection("inviteCodes").document(code).get().await()
            if (!codeDoc.exists()) return JoinResult.NotFound

            val roomId = codeDoc.getString("roomId") ?: run {
                Log.e(TAG, "joinRoomByCode: roomId 필드 없음 code=$code")
                return JoinResult.NotFound
            }

            // participantUids만 변경 — 다른 필드는 건드리지 않음
            db.collection("rooms").document(roomId)
                .update("participantUids", FieldValue.arrayUnion(uid))
                .await()

            Log.d(TAG, "joinRoomByCode 성공 roomId=$roomId")
            JoinResult.Success(roomId)
        } catch (e: Exception) {
            Log.e(TAG, "joinRoomByCode 실패 code=$code", e)
            JoinResult.NotFound
        }
    }

    // ── 읽음 시각 갱신 ───────────────────────────────────────────────────────

    suspend fun updateLastReadTime(roomId: String) {
        val uid = currentUid ?: return
        try {
            db.collection("users").document(uid)
                .collection("roomReads").document(roomId)
                .set(hashMapOf("lastReadTime" to FieldValue.serverTimestamp()))
                .await()
        } catch (e: Exception) {
            Log.e(TAG, "updateLastReadTime 실패 roomId=$roomId", e)
        }
    }

    // ── 내 전체 방 읽음 시각 실시간 구독 ────────────────────────────────────

    fun getRoomReadsFlow(): Flow<Map<String, Long>> = callbackFlow {
        val uid = currentUid ?: run {
            trySend(emptyMap())
            awaitClose { }
            return@callbackFlow
        }
        val listener = db.collection("users").document(uid)
            .collection("roomReads")
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.e(TAG, "roomReads 구독 오류", err)
                    return@addSnapshotListener
                }
                val reads = snap?.documents?.associate { doc ->
                    doc.id to doc.millisField("lastReadTime")
                } ?: emptyMap()
                trySend(reads)
            }
        awaitClose { listener.remove() }
    }

    // ── 메시지 전송: senderId = 현재 uid ────────────────────────────────────

    suspend fun sendMessage(roomId: String, content: String, senderName: String): Boolean {
        val uid = currentUid ?: run {
            Log.e(TAG, "sendMessage: 로그인 사용자 없음")
            return false
        }
        return try {
            // 서버 타임스탬프 — 기기 시계 오차와 무관하게 순서·안읽음 판정을 일관되게 유지.
            // batch 커밋 시 세 필드 모두 동일한 서버 시각으로 해석된다.
            val now = FieldValue.serverTimestamp()
            val msgRef = db.collection("rooms").document(roomId)
                .collection("messages").document()
            val roomRef = db.collection("rooms").document(roomId)
            val myReadRef = db.collection("users").document(uid)
                .collection("roomReads").document(roomId)
            val batch = db.batch()
            batch.set(msgRef, hashMapOf(
                "senderId" to uid,
                "senderName" to senderName,
                "content" to content,
                "timestamp" to now,
            ))
            batch.update(roomRef, mapOf(
                "lastMessage" to content,
                "lastMessageTime" to now,
            ))
            // 내가 보낸 메시지가 즉시 안 읽음 점으로 뜨지 않게 동시 갱신
            batch.set(myReadRef, hashMapOf("lastReadTime" to now))
            batch.commit().await()
            true
        } catch (e: Exception) {
            Log.e(TAG, "메시지 전송 실패 roomId=$roomId", e)
            false
        }
    }

    // ── 단건 조회 (AIReport / Summary용) ────────────────────────────────────

    suspend fun getRoomById(roomId: String): ChatRoom? {
        return try {
            val snap = db.collection("rooms").document(roomId).get().await()
            if (!snap.exists()) return null
            ChatRoom(
                id = snap.id,
                name = snap.getString("name") ?: "",
                participantUids = (snap.get("participantUids") as? List<*>)
                    ?.filterIsInstance<String>() ?: emptyList(),
                createdAt = snap.getLong("createdAt") ?: 0L,
                inviteCode = snap.getString("inviteCode") ?: ""
            )
        } catch (e: Exception) {
            Log.e(TAG, "getRoomById 실패 roomId=$roomId", e)
            null
        }
    }

    // ── 방 나가기: 트랜잭션으로 본인 제거 + 남은 인원 원자적 판정 ─────────────

    private data class LeaveTxResult(
        val existed: Boolean,
        val remaining: Int,
        val inviteCode: String?,
    )

    suspend fun leaveRoom(roomId: String) {
        val uid = currentUid ?: run {
            Log.e(TAG, "leaveRoom: 로그인 사용자 없음")
            return
        }
        try {
            val roomRef = db.collection("rooms").document(roomId)

            // 1. 트랜잭션: 본인 uid 제거 + 제거 후 남은 인원 수를 원자적으로 확정.
            //    동시 퇴장 시 Firestore가 트랜잭션을 직렬화/재시도하므로,
            //    두 번째 퇴장은 첫 번째의 제거가 반영된 상태를 읽는다 → 유령 방 방지.
            val result = db.runTransaction { tx ->
                val snap = tx.get(roomRef)
                if (!snap.exists()) {
                    return@runTransaction LeaveTxResult(existed = false, remaining = 0, inviteCode = null)
                }
                val participants = (snap.get("participantUids") as? List<*>)
                    ?.filterIsInstance<String>() ?: emptyList()
                val remaining = participants.filterNot { it == uid }
                tx.update(roomRef, "participantUids", remaining)
                LeaveTxResult(existed = true, remaining = remaining.size, inviteCode = snap.getString("inviteCode"))
            }.await()

            if (!result.existed) {
                Log.d(TAG, "leaveRoom: 방이 이미 삭제됨 roomId=$roomId")
                return
            }

            if (result.remaining == 0) {
                // 내가 마지막 멤버 — 부수 리소스 정리 (하위 컬렉션 삭제는 트랜잭션 불가)
                // a) messages 하위 컬렉션 500개 단위 batch 삭제
                deleteMessagesSubcollection(roomId)

                // b) inviteCodes/{code} 문서 삭제
                val inviteCode = result.inviteCode
                if (!inviteCode.isNullOrEmpty()) {
                    db.collection("inviteCodes").document(inviteCode).delete().await()
                    Log.d(TAG, "leaveRoom: inviteCode=$inviteCode 삭제 완료")
                }

                // c) rooms/{roomId} 문서 삭제 (복구 여지를 위해 마지막에)
                roomRef.delete().await()
                Log.d(TAG, "leaveRoom: 마지막 멤버 — 방 삭제 완료 roomId=$roomId")
            } else {
                Log.d(TAG, "leaveRoom: 본인 제거 완료 roomId=$roomId 남은 멤버=${result.remaining}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "leaveRoom 실패 roomId=$roomId", e)
            throw e
        }
    }

    private suspend fun deleteMessagesSubcollection(roomId: String) {
        val messagesRef = db.collection("rooms").document(roomId).collection("messages")
        while (true) {
            val snap = messagesRef.limit(500).get().await()
            if (snap.isEmpty) break
            val batch = db.batch()
            snap.documents.forEach { batch.delete(it.reference) }
            batch.commit().await()
            Log.d(TAG, "deleteMessagesSubcollection: ${snap.size()} 건 삭제 roomId=$roomId")
        }
    }

    // ── 요약 저장 (인메모리) ────────────────────────────────────────────────

    fun saveSummary(summary: MeetingSummary) {
        _summaries.value = _summaries.value + (summary.roomId to summary)
    }

    /**
     * 앱 시작 시 Room 영속본으로 복원. 하이드레이션이 끝나기 전에 새 추천이 먼저
     * 들어왔을 수 있으므로 이미 인메모리에 있는(더 새로운) 요약은 덮어쓰지 않는다.
     */
    fun hydrateSummaries(persisted: List<MeetingSummary>) {
        _summaries.value = persisted.associateBy { it.roomId } + _summaries.value
    }
}