package com.example.gemma4.data.pipeline

import com.example.gemma4.data.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MockOnDeviceLlm : OnDeviceLlmPort {

    override suspend fun summarizeForPrivacy(messages: List<Message>): String = withContext(Dispatchers.IO) {
        val corpus = messages.joinToString(" ") { it.content }.lowercase()

        val dateHint = when {
            "다음 주" in corpus || "다음주" in corpus -> "다음 주"
            "토요일" in corpus -> "이번 토요일"
            "일요일" in corpus -> "이번 일요일"
            "주말" in corpus -> "이번 주말"
            "평일" in corpus || "주중" in corpus -> "평일 중"
            "저녁" in corpus -> "저녁 시간대"
            "오후" in corpus -> "오후 시간대"
            else -> "일정 미정"
        }

        val placeHint = when {
            "홍대" in corpus -> "서울 서북부(홍대 인근)"
            "강남" in corpus -> "서울 남부(강남 인근)"
            "신촌" in corpus -> "서울 서북부(신촌 인근)"
            "종로" in corpus || "광화문" in corpus -> "서울 도심(종로 인근)"
            "부산" in corpus -> "부산"
            "대구" in corpus -> "대구"
            "카페" in corpus || "커피" in corpus -> "카페 밀집 지역"
            else -> "장소 미정"
        }

        val purposeHint = when {
            "식사" in corpus || "밥" in corpus || "음식" in corpus -> "식사 모임"
            "영화" in corpus -> "영화 관람 모임"
            "술" in corpus || "맥주" in corpus -> "저녁 음주 모임"
            "카페" in corpus || "커피" in corpus -> "카페 모임"
            "공원" in corpus || "산책" in corpus -> "야외 활동 모임"
            else -> "친목 모임"
        }

        "${dateHint}에 ${placeHint}에서 ${purposeHint}을 진행할 예정입니다. " +
            "구체적인 시간과 인원은 조율 중이며, 날씨 및 장소 추천이 필요한 상태입니다."
    }

    override suspend fun compress(messages: List<Message>): String = withContext(Dispatchers.IO) {
        val senderNames = messages.map { it.senderName }.distinct()
        val corpus = messages.joinToString(" ") { it.content }.lowercase()

        val preferences = mutableListOf<String>()
        if ("카페" in corpus || "커피" in corpus) preferences += "좋아요: 카페"
        if ("조용" in corpus) preferences += "좋아요: 조용한 분위기"
        if ("술" in corpus || "맥주" in corpus) preferences += "선호: 술집"
        if ("식당" in corpus || "밥" in corpus || "음식" in corpus) preferences += "선호: 음식점"
        if ("야외" in corpus || "공원" in corpus) preferences += "선호: 야외 장소"
        if ("시끄" in corpus || "복잡" in corpus) preferences += "싫어요: 시끄러운 곳"
        if (preferences.isEmpty()) preferences += "선호: 편안한 장소"

        val availability = mutableListOf<String>()
        if ("토요일" in corpus || "토" in corpus) availability += "토요일 가능"
        if ("일요일" in corpus || "일" in corpus) availability += "일요일 가능"
        if ("주말" in corpus) availability += "주말 가능"
        if ("평일" in corpus || "주중" in corpus) availability += "평일 가능"
        if ("저녁" in corpus) availability += "저녁 시간대 선호"
        if ("오후" in corpus) availability += "오후 가능"
        if (availability.isEmpty()) availability += "일정 미정"

        JSONObject().apply {
            put("participants", JSONArray(senderNames))
            put("preferences", JSONArray(preferences))
            put("availability", JSONArray(availability))
        }.toString()
    }
}
