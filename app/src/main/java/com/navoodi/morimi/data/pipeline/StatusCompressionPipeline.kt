package com.navoodi.morimi.data.pipeline

import android.util.Log
import com.navoodi.morimi.data.local.UserStatusEntity
import com.navoodi.morimi.data.model.Message
import com.navoodi.morimi.data.repository.UserStatusRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    }

    /**
     * 증분(rolling) 압축: [messages]는 **직전 압축 이후 새 메시지 델타**만 전달된다.
     * 누적 전체를 매번 재압축하면 대화가 길어질수록 Gemma 컨텍스트(~8k)를 넘겨
     * 압축이 조용히 실패한다. 델타만 LLM에 넘겨 토큰을 유한하게 유지하고,
     * 직전 [UserStatusEntity]와 결정론적으로 병합해 과거 성향을 보존한다.
     */
    suspend fun compress(roomId: String, messages: List<Message>) = withContext(Dispatchers.IO) {
        try {
            val raw = llmPort.compress(messages)
            Log.d(TAG, "LLM 응답 raw (앞 200자): ${raw.take(200)}")

            val fresh = extractAndParse(raw, roomId) ?: run {
                Log.w(TAG, "JSON 추출 실패 — 스킵 roomId=$roomId")
                return@withContext
            }

            val existing = repository.getStatus(roomId)
            val merged = mergeStatus(existing, fresh)

            repository.upsert(merged)
            Log.d(TAG, "증분 병합 저장 roomId=$roomId " +
                "participants=${merged.participants.size} " +
                "prefs=${merged.preferences.size} " +
                "avail=${merged.availability.size} " +
                "(델타 ${messages.size}건)")
        } catch (e: Exception) {
            Log.e(TAG, "압축 파이프라인 오류 roomId=$roomId", e)
        }
    }

    /**
     * 직전 상태와 새로 추출한 상태를 결정론적으로 병합한다(LLM 재요약에 의존하지 않음).
     * 각 리스트는 합집합·중복제거하고 [MAX_ITEMS_PER_LIST]로 상한(오래된 것부터 폐기).
     * internal — 단위 테스트로 직접 검증 가능.
     */
    internal fun mergeStatus(existing: UserStatusEntity?, fresh: UserStatusEntity): UserStatusEntity {
        if (existing == null) return fresh
        return fresh.copy(
            participants = mergeDistinct(existing.participants, fresh.participants),
            preferences = mergeDistinct(existing.preferences, fresh.preferences),
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
                preferences = parseArray("preferences"),
                availability = parseArray("availability"),
                lastUpdated = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e(TAG, "파싱 실패 raw=$raw", e)
            null
        }
    }
}
