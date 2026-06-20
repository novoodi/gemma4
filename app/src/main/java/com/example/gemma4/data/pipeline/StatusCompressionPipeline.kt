package com.example.gemma4.data.pipeline

import android.util.Log
import com.example.gemma4.data.local.UserStatusEntity
import com.example.gemma4.data.model.Message
import com.example.gemma4.data.repository.UserStatusRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class StatusCompressionPipeline(
    private val llmPort: OnDeviceLlmPort,
    private val repository: UserStatusRepository
) {
    companion object {
        private const val TAG = "StatusCompressionPipeline"

        // LLM 응답에 설명 텍스트·마크다운이 섞여도 { ... } 블록만 greedy하게 추출
        private val JSON_BLOCK = Regex("""\{[\s\S]*\}""")

        // Gemma가 자주 생성하는 trailing comma 패턴 제거
        private val TRAILING_COMMA_BEFORE_BRACKET = Regex(""",\s*]""")
        private val TRAILING_COMMA_BEFORE_BRACE = Regex(""",\s*\}""")
    }

    suspend fun compress(roomId: String, messages: List<Message>) = withContext(Dispatchers.IO) {
        try {
            val raw = llmPort.compress(messages)
            Log.d(TAG, "LLM 응답 raw (앞 200자): ${raw.take(200)}")

            val entity = extractAndParse(raw, roomId) ?: run {
                Log.w(TAG, "JSON 추출 실패 — 스킵 roomId=$roomId")
                return@withContext
            }

            repository.upsert(entity)
            Log.d(TAG, "저장 완료 roomId=$roomId " +
                "participants=${entity.participants.size} " +
                "prefs=${entity.preferences.size} " +
                "avail=${entity.availability.size}")
        } catch (e: Exception) {
            Log.e(TAG, "압축 파이프라인 오류 roomId=$roomId", e)
        }
    }

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
