package com.example.gemma4.service

import android.util.Log
import com.example.gemma4.BuildConfig
import com.example.gemma4.data.local.UserStatusEntity
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
        ragContext: String,
        userStatus: UserStatusEntity? = null
    ): String = withContext(Dispatchers.IO) {
        if (BuildConfig.GEMINI_API_KEY.isBlank() || BuildConfig.GEMINI_API_KEY.startsWith("여기에")) {
            Log.w("GeminiService", "API 키 미설정 → 목(mock) 응답 반환")
            return@withContext buildMockResponse(city, ragContext, userStatus)
        }
        val prompt = buildPrompt(summary, location, date, city, ragContext, userStatus)
        generateWithRetry(prompt)
    }

    private suspend fun generateWithRetry(prompt: String, maxRetries: Int = 3): String {
        repeat(maxRetries) { attempt ->
            try {
                val response = model.generateContent(prompt)
                return response.text ?: "추천 정보를 생성하지 못했습니다."
            } catch (e: Exception) {
                val msg = fullMessage(e)
                Log.w("GeminiService", "시도 ${attempt + 1} 실패: $msg")
                if (isRetryable(e) && attempt < maxRetries - 1) {
                    kotlinx.coroutines.delay(3000L * (attempt + 1))
                } else {
                    return toKoreanError(msg)
                }
            }
        }
        return "추천 정보를 생성하지 못했습니다."
    }

    private fun fullMessage(e: Throwable): String = buildString {
        var cause: Throwable? = e
        while (cause != null) {
            if (isNotEmpty()) append(" | ")
            append(cause.message ?: cause.javaClass.simpleName)
            cause = cause.cause
        }
    }

    private fun isRetryable(e: Exception): Boolean {
        val msg = fullMessage(e)
        return "503" in msg || "UNAVAILABLE" in msg || "429" in msg ||
               "Something unexpected happened" in msg ||
               // deprecated SDK가 503 에러 응답의 'details' 필드 누락으로 던지는 파싱 예외
               "is required for type with serial name" in msg
    }

    private fun toKoreanError(msg: String): String {
        Log.e("GeminiService", "추천 생성 최종 실패: $msg")
        return when {
            "503" in msg || "UNAVAILABLE" in msg ||
            "is required for type with serial name" in msg -> "서버가 일시적으로 혼잡합니다. 잠시 후 다시 시도해주세요."
            "429" in msg || "quota" in msg.lowercase() -> "요청 한도를 초과했습니다. 잠시 후 다시 시도해주세요."
            "404" in msg || "NOT_FOUND" in msg -> "모델을 찾을 수 없습니다. (모델명 확인 필요)"
            "401" in msg || "403" in msg || "API_KEY" in msg -> "API 키가 유효하지 않습니다."
            else -> "추천 정보를 불러오지 못했습니다."
        }
    }

    private fun buildMockResponse(
        city: String,
        ragContext: String,
        userStatus: UserStatusEntity? = null
    ): String {
        val feedbackNote = if (ragContext.isNotBlank()) {
            "\n(과거 피드백 반영됨 — RAG context ${ragContext.lines().size}건 감지)"
        } else {
            "\n(저장된 피드백 없음 — 첫 추천)"
        }
        val statusNote = if (userStatus != null) {
            "\n(Gemma 압축 프로필 반영됨 — 선호 ${userStatus.preferences.size}개, 가용성 ${userStatus.availability.size}개)"
        } else {
            "\n(Gemma 압축 프로필 없음)"
        }
        return """
[목(Mock) 응답 — Gemini API 키를 설정하면 실제 추천으로 교체됩니다]$feedbackNote$statusNote

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
        ragContext: String,
        userStatus: UserStatusEntity? = null
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

        val statusSection = if (userStatus != null) {
            val participants = userStatus.participants.joinToString(", ").ifBlank { "정보 없음" }
            val preferences = userStatus.preferences
                .joinToString("\n") { "  - $it" }
                .ifBlank { "  - 없음" }
            val availability = userStatus.availability
                .joinToString("\n") { "  - $it" }
                .ifBlank { "  - 없음" }
            """

[채팅 분석으로 추출된 참가자 프로필 — 최우선으로 반영할 것]
참가자: $participants
선호/불호 항목:
$preferences
가능 일정 및 제약조건:
$availability

중요: 위 선호/불호와 일정 제약조건을 추천에 엄격히 반영하라.
싫어하는 요소가 포함된 장소/활동은 절대 추천하지 마라.
가능 일정에 맞지 않는 날짜는 언급하지 마라."""
        } else {
            """

[채팅 분석 프로필]
아직 충분한 대화 데이터가 없습니다 (10개 메시지 이후 자동 생성)."""
        }

        return """
당신은 모임 장소와 활동을 추천하는 AI 비서입니다.

[이번 모임 정보]
- 날짜: $date
- 지역: $city
- 확인된 장소: $location
- 모임 요약: $summary
$statusSection
$feedbackSection

위 정보를 바탕으로 다음을 추천해주세요.
참가자 프로필이 있다면 해당 선호도와 제약조건을 최우선으로 반영하고,
과거 피드백도 함께 참고하여 이전에 별로였던 환경은 피하고 좋았던 환경을 우선시하세요.

1. 모임 장소 추천 (2~3곳, 각 장소의 특징 한 줄씩)
2. 모임에서 할 수 있는 활동 2~3가지
3. 챙겨가면 좋을 것들 3~5가지

별표, 샵, 대괄호 같은 마크다운 특수기호 없이 일반 텍스트로만 답해주세요.
        """.trimIndent()
    }
}