package com.example.gemma4.service

import android.content.Context
import android.util.Log
import com.example.gemma4.data.model.MeetingSummary
import com.example.gemma4.data.model.Message
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class LlmService(private val context: Context) {

    companion object {
        const val MODEL_FILENAME = "gemma-4-E2B-it.litertlm"
        private const val NO_MARKDOWN = "별표, 샵, 대괄호 같은 특수기호 없이 일반 텍스트로만 답해줘."
    }

    private var engine: Engine? = null
    private var activeConversation: Any? = null

    val modelPath: String
        get() = "${context.getExternalFilesDir("models")?.absolutePath}/$MODEL_FILENAME"

    val isModelAvailable: Boolean
        get() = File(modelPath).exists()

    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (engine != null) return@withContext
        val config = EngineConfig(modelPath = modelPath, backend = Backend.GPU())
        engine = Engine(config)
        engine!!.initialize()
    }

    private fun closeActiveConversation() {
        val conv = activeConversation ?: return
        activeConversation = null
        try {
            (conv as? AutoCloseable)?.close()
                ?: conv::class.java.getMethod("close").invoke(conv)
        } catch (_: Exception) {}
    }

    suspend fun runPipeline(roomId: String, messages: List<Message>): MeetingSummary {
        val eng = checkNotNull(engine) { "엔진이 초기화되지 않았습니다" }

        closeActiveConversation()

        val transcript = messages.joinToString("\n") { "[${it.senderName}]: ${it.content}" }
        // 대화가 오갔던 시점의 날짜를 기준으로 삼음 (더미 데이터는 오늘, 실제 대화는 해당 날짜)
        val chatDate = messages.firstOrNull()?.let {
            Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
        } ?: LocalDate.now()
        val conv = eng.createConversation()
        activeConversation = conv

        return try {
            // Step 1: 대화 내용 분석 및 정리
            val summary = withContext(Dispatchers.IO) {
                conv.sendMessage(
                    "아래 모임 대화를 읽고 핵심 내용을 3~5문장으로 정리해줘. $NO_MARKDOWN\n\n$transcript"
                ).toString()
            }

            // Step 2: 모임 장소 추출
            val location = withContext(Dispatchers.IO) {
                conv.sendMessage(
                    "위 대화에서 모임 장소 이름만 짧게 알려줘. $NO_MARKDOWN"
                ).toString()
            }

            // Step 3: 모임 날짜 추출 (YYYY-MM-DD)
            val meetingDate = withContext(Dispatchers.IO) {
                val raw = conv.sendMessage(
                    "대화가 이루어진 날짜는 $chatDate 입니다. " +
                    "위 대화에서 참석자들이 최종 확정한 모임 당일 날짜를 YYYY-MM-DD 형식으로만 답해줘. " +
                    "투표 마감일, 제안된 날짜, 취소된 날짜는 제외하고 최종 결정된 날짜만 추출해. " +
                    "'N일'처럼 일만 있으면 $chatDate 의 연도와 월을 그대로 사용해. " +
                    "날짜를 특정할 수 없으면 미정 이라고만 해. $NO_MARKDOWN"
                ).toString()
                Log.d("LlmService", "날짜 raw: $raw")
                Regex("\\d{4}-\\d{2}-\\d{2}").find(raw)?.value ?: "미정"
            }

            // Step 4: 날씨 검색용 도시명 추출
            val city = withContext(Dispatchers.IO) {
                val raw = conv.sendMessage(
                    "위 모임 장소가 있는 도시나 지역 이름을 한국어로만 알려줘. 예: 서울, 부산, 인천, 대구, 홍대, 강남. $NO_MARKDOWN"
                ).toString().trim()
                Log.d("LlmService", "도시 raw: $raw")
                raw
            }

            // Step 5: 모임 활동
            val activities = withContext(Dispatchers.IO) {
                conv.sendMessage(
                    "위 모임에서 어떤 활동을 할 예정인지 2~3문장으로 알려줘. $NO_MARKDOWN"
                ).toString()
            }

            // Step 6: 챙겨갈 것
            val whatToBring = withContext(Dispatchers.IO) {
                conv.sendMessage(
                    "위 모임에 참석할 때 챙겨가면 좋을 것들을 3~5가지 추천해줘. 줄바꿈으로 구분하고 $NO_MARKDOWN"
                ).toString()
            }

            val weather = WeatherService.getWeather(city, meetingDate)

            // Step 8: TODO - 카카오맵 API 연동
            val directions = "지하철 2호선 홍대입구역 2번 출구 하차 후 도보 2분"

            MeetingSummary(
                roomId = roomId,
                summary = summary,
                location = location,
                meetingDate = meetingDate,
                activities = activities,
                whatToBring = whatToBring,
                weather = weather,
                directions = directions
            )
        } finally {
            closeActiveConversation()
        }
    }

    fun release() {
        closeActiveConversation()
        engine = null
    }
}
