package com.example.gemma4.data.pipeline

import com.example.gemma4.data.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MockOnDeviceLlm : OnDeviceLlmPort {

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
