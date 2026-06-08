package com.example.gemma4.data.repository

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

data class FeedbackEntry(
    val date: String,
    val feedback: String,
    val roomId: String = ""
)

class FeedbackRepository(context: Context) {

    private val file = File(context.filesDir, "feedback_history.json")

    fun append(feedback: String, roomId: String = "") {
        val entries = loadAll().toMutableList()
        entries.add(FeedbackEntry(
            date = LocalDate.now().toString(),
            feedback = feedback,
            roomId = roomId
        ))
        save(entries)
        Log.d("FeedbackRepository", "피드백 저장: [$roomId] $feedback")
    }

    fun loadAll(): List<FeedbackEntry> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            List(arr.length()) { i ->
                val obj = arr.getJSONObject(i)
                FeedbackEntry(
                    date = obj.optString("date", ""),
                    feedback = obj.optString("feedback", ""),
                    roomId = obj.optString("roomId", "")
                )
            }
        } catch (e: Exception) {
            Log.e("FeedbackRepository", "피드백 읽기 실패", e)
            emptyList()
        }
    }

    // 최근 10개 피드백을 Gemini 프롬프트용 텍스트로 변환
    fun buildRagContext(): String {
        val entries = loadAll().takeLast(10)
        if (entries.isEmpty()) return ""
        return entries.joinToString("\n") { "- [${it.date}] ${it.feedback}" }
    }

    fun clearAll() {
        file.delete()
        Log.d("FeedbackRepository", "피드백 초기화 완료")
    }

    private fun save(entries: List<FeedbackEntry>) {
        val arr = JSONArray()
        entries.forEach { entry ->
            arr.put(JSONObject().apply {
                put("date", entry.date)
                put("feedback", entry.feedback)
                put("roomId", entry.roomId)
            })
        }
        file.writeText(arr.toString())
    }
}
