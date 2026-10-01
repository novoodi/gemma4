package com.navoodi.morimi.data.pipeline

import android.util.Log
import com.navoodi.morimi.data.local.UserStatusEntity
import com.navoodi.morimi.data.model.Message
import com.navoodi.morimi.data.repository.UserStatusRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.navoodi.morimi.service.PreferenceEntries
import org.json.JSONObject

class StatusCompressionPipeline(
    private val llmPort: OnDeviceLlmPort,
    private val repository: UserStatusRepository
) {
    companion object {
        private const val TAG = "StatusCompressionPipeline"

        // 병합 후 리스트별 최대 보관 개수 — 대화가 길어져도 DB·프롬프트 크기를 유한하게 유지.
        // 초과 시 오래된 항목부터 버린다(신규 성향이 더 중요).
        private const val MAX_ITEMS_PER_LIST = 25

        // LLM 응답에 설명 텍스트·마크다운이 섞여도 { ... } 블록만 greedy하게 추출
        private val JSON_BLOCK = Regex("""\{[\s\S]*\}""")

        // Gemma가 자주 생성하는 trailing comma 패턴 제거
        private val TRAILING_COMMA_BEFORE_BRACKET = Regex(""",\s*]""")
        private val TRAILING_COMMA_BEFORE_BRACE = Regex(""",\s*\}""")

        /** 재구성 시 Gemma에 한 번에 넣는 메시지 수 — 평소 증분 압축 창(15)과 같다 */
        internal const val REBUILD_CHUNK_SIZE = 15
        /** 청크 사이 겹침 — 경계에서 앞 말 맥락("나도")이 끊기지 않게 직전 메시지를 다시 보여준다 */
        internal const val REBUILD_OVERLAP = 2
        /**
         * 재구성에 쓰는 청크 상한(최근 메시지 우선). 15×20 = 최근 300개 메시지(겹침 제외).
         * Gemma 호출 수를 유한하게 묶기 위한 것으로, 이보다 오래된 발언의 선호는 재구성에서 빠진다.
         */
        internal const val MAX_REBUILD_CHUNKS = 20
    }

    /** compress·rebuild가 같은 방 프로필을 동시에 읽고-쓰지 않게 직렬화(늦게 끝난 쪽이 덮어쓰는 경합 방지) */
    private val writeMutex = Mutex()

    enum class RebuildResult { REBUILT, EMPTY, FAILED }

    /**
     * 증분(rolling) 압축: [messages]는 **직전 압축 이후 새 메시지 델타**만 전달된다.
     * 누적 전체를 매번 재압축하면 대화가 길어질수록 Gemma 컨텍스트(~8k)를 넘겨
     * 압축이 조용히 실패한다. 델타만 LLM에 넘겨 토큰을 유한하게 유지하고,
     * 직전 [UserStatusEntity]와 결정론적으로 병합해 과거 성향을 보존한다.
     */
    suspend fun compress(
        roomId: String,
        messages: List<Message>,
        memberIds: Set<String>? = null,
    ) = withContext(Dispatchers.IO) {
        writeMutex.withLock { compressLocked(roomId, messages, memberIds) }
    }

    private suspend fun compressLocked(roomId: String, all: List<Message>, memberIds: Set<String>?) {
        try {
            // 나간 사람의 메시지는 선호 추출 대상이 아니다(동조 맥락은 인용으로 보존)
            val messages = MemberScope.scope(all, memberIds)
            if (messages.isEmpty()) {
                Log.d(TAG, "멤버 메시지 없음 — 압축 스킵 roomId=$roomId")
                return
            }
            val raw = llmPort.compress(messages)
            Log.d(TAG, "LLM 응답 raw (앞 200자): ${raw.take(200)}")

            val fresh = extractAndParse(raw, roomId) ?: run {
                Log.w(TAG, "JSON 추출 실패 — 스킵 roomId=$roomId")
                return
            }

            // 나간 사람이 섞였을 수 있는 프로필에는 병합하지 않는다(재구성이 따로 돈다)
            val stored = repository.getStatus(roomId)
            val existing = stored?.takeIf { memberIds == null || it.sourceMemberIds.isEmpty() || memberIds.containsAll(it.sourceMemberIds) }
            // 출처 기록: 새 프로필이면 현재 멤버로 남긴다. 출처 미기록(v7 이전) 프로필에 병합할 때는
            // 남기지 않는다 — 그 안에 이미 나간 사람의 선호가 있을 수 있어, 현재 멤버로 찍으면 "깨끗한
            // 프로필"로 세탁된다. 그런 프로필은 재구성([rebuild])만 출처를 기록한다.
            val stamp = when {
                memberIds == null -> existing?.sourceMemberIds ?: emptyList()
                existing != null && existing.sourceMemberIds.isEmpty() -> emptyList()
                else -> memberIds.sorted()
            }
            val merged = mergeStatus(existing, fresh).copy(sourceMemberIds = stamp)

            repository.upsert(merged)
            Log.d(TAG, "증분 병합 저장 roomId=$roomId " +
                "participants=${merged.participants.size} " +
                "prefs=${merged.preferences.size} " +
                "avail=${merged.availability.size} " +
                "(델타 ${messages.size}건)")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "압축 파이프라인 오류 roomId=$roomId", e)
        }
    }

    /**
     * 현재 멤버의 대화만으로 프로필을 처음부터 다시 만든다(멤버가 나가거나 다시 들어왔을 때).
     *
     * - 나간 사람의 메시지는 선호 추출에서 빠지고, 남은 사람의 동조 맥락만 인용으로 남는다([MemberScope.scope])
     * - 시각 순으로 [REBUILD_CHUNK_SIZE]씩 나눠 Gemma로 압축하고 결정론적으로 병합한다(증분 압축과 같은 규칙)
     * - 최근 [MAX_REBUILD_CHUNKS] 청크까지만 — 더 오래된 발언은 포함하지 않는다
     * - 일부 청크가 실패하면 성공분으로 만든다. 전부 실패하면 저장된 프로필을 건드리지 않고 [RebuildResult.FAILED]
     *   (호출자는 그동안 프로필을 쓰지 않는다 — 오래된 프로필은 [MemberScope.profileUsable]이 막는다)
     * - 남은 멤버의 메시지가 없으면 출처만 기록한 빈 프로필([RebuildResult.EMPTY])
     */
    suspend fun rebuild(roomId: String, allMessages: List<Message>, memberIds: Set<String>): RebuildResult =
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                val scoped = MemberScope.scope(allMessages, memberIds).sortedBy { it.timestamp }
                val stamp = memberIds.sorted()
                if (scoped.isEmpty()) {
                    repository.upsert(UserStatusEntity(roomId = roomId, sourceMemberIds = stamp))
                    Log.d(TAG, "재구성: 남은 멤버 메시지 없음 — 빈 프로필 roomId=$roomId")
                    return@withLock RebuildResult.EMPTY
                }
                val chunks = rebuildChunks(scoped)
                var acc: UserStatusEntity? = null
                var ok = 0
                for (chunk in chunks) {
                    val fresh = try {
                        extractAndParse(llmPort.compress(chunk), roomId)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "재구성 청크 실패 roomId=$roomId", e)
                        null
                    } ?: continue
                    acc = mergeStatus(acc, fresh)
                    ok++
                }
                if (acc == null) {
                    Log.w(TAG, "재구성 실패(청크 ${chunks.size}개 전부) — 기존 프로필 유지(사용 차단) roomId=$roomId")
                    return@withLock RebuildResult.FAILED
                }
                repository.upsert(acc.copy(sourceMemberIds = stamp, lastUpdated = System.currentTimeMillis()))
                Log.d(TAG, "재구성 완료 roomId=$roomId 메시지 ${scoped.size}건 청크 $ok/${chunks.size}")
                RebuildResult.REBUILT
            }
        }

    /** 시각 순 메시지를 겹침 있는 청크로 나누고 최근 [MAX_REBUILD_CHUNKS]개만 남긴다. */
    internal fun rebuildChunks(ordered: List<Message>): List<List<Message>> {
        val step = REBUILD_CHUNK_SIZE
        val chunks = ArrayList<List<Message>>()
        var start = 0
        while (start < ordered.size) {
            val from = maxOf(0, start - REBUILD_OVERLAP)
            chunks += ordered.subList(from, minOf(ordered.size, start + step))
            start += step
        }
        return chunks.takeLast(MAX_REBUILD_CHUNKS)
    }

    /**
     * 직전 상태와 새로 추출한 상태를 결정론적으로 병합한다(LLM 재요약에 의존하지 않음).
     * 각 리스트는 합집합·중복제거하고 [MAX_ITEMS_PER_LIST]로 상한(오래된 것부터 폐기).
     * internal — 단위 테스트로 직접 검증 가능.
     */
    internal fun mergeStatus(existing: UserStatusEntity?, fresh: UserStatusEntity): UserStatusEntity {
        // 파싱 단계에서도 빈 항목을 거르지만, 그 이전 버전에서 이미 저장된 빈 항목("좋아요:")은
        // 병합 시점에 함께 정리한다 — 그렇지 않으면 프로필에 영구히 남는다.
        if (existing == null) return fresh.copy(preferences = PreferenceEntries.contentful(fresh.preferences))
        return fresh.copy(
            participants = mergeDistinct(existing.participants, fresh.participants),
            preferences = mergeDistinct(
                PreferenceEntries.contentful(existing.preferences),
                PreferenceEntries.contentful(fresh.preferences),
            ),
            availability = mergeDistinct(existing.availability, fresh.availability),
        )
    }

    private fun mergeDistinct(old: List<String>, new: List<String>): List<String> =
        (old + new)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .takeLast(MAX_ITEMS_PER_LIST)

    /**
     * 1단계: Regex로 { ... } 블록 추출
     * 2단계: trailing comma 정제
     * 3단계: JSONObject 파싱 → UserStatusEntity 변환
     *
     * internal로 열어둬 단위 테스트에서 직접 검증 가능
     */
    internal fun extractAndParse(raw: String, roomId: String): UserStatusEntity? {
        return try {
            val cleaned = JSON_BLOCK.find(raw)?.value
                ?.replace(TRAILING_COMMA_BEFORE_BRACKET, "]")
                ?.replace(TRAILING_COMMA_BEFORE_BRACE, "}")
                ?: return null

            val obj = JSONObject(cleaned)

            fun parseArray(key: String): List<String> {
                val arr = obj.optJSONArray(key) ?: return emptyList()
                return (0 until arr.length()).mapNotNull {
                    arr.optString(it).trim().takeIf(String::isNotBlank)
                }
            }

            UserStatusEntity(
                roomId = roomId,
                participants = parseArray("participants"),
                // 접두사만 있고 내용이 빈 항목("좋아요:")을 **여기서** 버린다.
                // 2026-09-13 실기기 평가에서 566개 중 119개(21.0%)가 그랬다.
                // constrained decoding이 스키마는 강제하지만 내용 공백은 막지 못한다.
                // 읽는 쪽은 원래 걸렀지만 저장은 그대로 돼서 프로필에 영구 누적되고
                // 디버그 패널에 "좋아요:, 좋아요:"처럼 떴다. 모델 출력을 믿지 않는다(컨벤션 #2).
                preferences = PreferenceEntries.contentful(parseArray("preferences")),
                availability = parseArray("availability"),
                lastUpdated = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e(TAG, "파싱 실패 raw=$raw", e)
            null
        }
    }
}
