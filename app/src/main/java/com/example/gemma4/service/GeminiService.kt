package com.example.gemma4.service

import android.util.Log
import com.example.gemma4.BuildConfig
import com.google.ai.client.generativeai.GenerativeModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GeminiService {

    private val model = GenerativeModel(
        modelName = "gemini-3.5-flash",
        apiKey = BuildConfig.GEMINI_API_KEY
    )

    suspend fun recommend(
        summary: String,
        location: String,
        date: String,
        city: String,
        ragContext: String
    ): String = withContext(Dispatchers.IO) {
        if (BuildConfig.GEMINI_API_KEY.isBlank() || BuildConfig.GEMINI_API_KEY.startsWith("여기에")) {
            Log.w("GeminiService", "API 키 미설정 → 목(mock) 응답 반환")
            return@withContext buildMockResponse(city, ragContext)
        }
        val prompt = buildPrompt(summary, location, date, city, ragContext)
        generateWithRetry(prompt)
    }

    private suspend fun generateWithRetry(prompt: String, maxRetries: Int = 2): String {
        repeat(maxRetries) { attempt ->
            try {
                val response = model.generateContent(prompt)
                return response.text ?: "추천 정보를 생성하지 못했습니다."
            } catch (e: Exception) {
                val msg = e.message ?: e.cause?.message ?: ""
                Log.w("GeminiService", "시도 ${attempt + 1} 실패: $msg")
                if (isRetryable(msg) && attempt < maxRetries - 1) {
                    kotlinx.coroutines.delay(3000L * (attempt + 1))
                } else {
                    return toKoreanError(msg)
                }
            }
        }
        return "추천 정보를 생성하지 못했습니다."
    }

    private fun isRetryable(msg: String) = "503" in msg || "UNAVAILABLE" in msg || "429" in msg

    private fun toKoreanError(msg: String): String {
        Log.e("GeminiService", "추천 생성 최종 실패: $msg")
        return when {
            "503" in msg || "UNAVAILABLE" in msg -> "서버가 일시적으로 혼잡합니다. 잠시 후 다시 시도해주세요."
            "429" in msg || "quota" in msg.lowercase() -> "요청 한도를 초과했습니다. 잠시 후 다시 시도해주세요."
            "404" in msg || "NOT_FOUND" in msg -> "모델을 찾을 수 없습니다. (모델명 확인 필요)"
            "401" in msg || "403" in msg || "API_KEY" in msg -> "API 키가 유효하지 않습니다."
            else -> "추천 정보를 불러오지 못했습니다."
        }
    }

    private fun buildMockResponse(city: String, ragContext: String): String {
        val feedbackNote = if (ragContext.isNotBlank()) {
            "\n(과거 피드백 반영됨 — RAG context ${ragContext.lines().size}건 감지)"
        } else {
            "\n(저장된 피드백 없음 — 첫 추천)"
        }
        return """
[목(Mock) 응답 — Gemini API 키를 설정하면 실제 추천으로 교체됩니다]$feedbackNote

장소 추천
1. ${city.ifBlank { "해당 지역" }} 조용한 카페 — 대화하기 좋은 아늑한 분위기
2. 루프탑 레스토랑 — 탁 트인 뷰와 다양한 메뉴
3. 보드게임 카페 — 오랜 시간 즐길 수 있는 실내 공간

활동 추천
- 보드게임 or 방탈출 (실내 활동)
- 근처 공원 산책 후 카페 방문
- 맛집 투어 후 디저트 카게

챙겨갈 것들
- 편한 신발
- 보조 배터리
- 가벼운 겉옷
- 현금 (소규모 가게 대비)
        """.trimIndent()
    }

    private fun buildPrompt(
        summary: String,
        location: String,
        date: String,
        city: String,
        ragContext: String
    ): String {
        val feedbackSection = if (ragContext.isNotBlank()) {
            """

[사용자 과거 피드백 이력 — 반드시 반영할 것]
$ragContext"""
        } else {
            """

[사용자 과거 피드백 이력]
없음 (첫 모임 추천)"""
        }

        return """
당신은 모임 장소와 활동을 추천하는 AI 비서입니다.

[이번 모임 정보]
- 날짜: $date
- 지역: $city
- 확인된 장소: $location
- 모임 요약: $summary
$feedbackSection

위 정보를 바탕으로 다음을 추천해주세요.
과거 피드백이 있다면 반드시 반영하여 이전에 별로였던 환경은 피하고, 좋았던 환경은 우선시하세요.

1. 모임 장소 추천 (2~3곳, 각 장소의 특징 한 줄씩)
2. 모임에서 할 수 있는 활동 2~3가지
3. 챙겨가면 좋을 것들 3~5가지

별표, 샵, 대괄호 같은 마크다운 특수기호 없이 일반 텍스트로만 답해주세요.
        """.trimIndent()
    }
}