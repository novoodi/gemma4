package com.navoodi.morimi.data.repository

import android.content.Context
import android.util.Log
import com.navoodi.morimi.data.local.AppDatabase
import com.navoodi.morimi.data.local.MeetingSummaryEntity
import com.navoodi.morimi.data.local.MeetingSummaryJson
import com.navoodi.morimi.data.model.MeetingSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AI 추천 결과(MeetingSummary) Room 영속 저장소.
 *
 * 읽기 모델은 여전히 [ChatRepository.summaries](인메모리 StateFlow) — 화면들은 그대로 두고,
 * 여기는 그 뒤의 영속 계층만 담당한다: 추천 성공 시 [save], 앱 시작 시 [loadAll]로
 * 복원해 ChatRepository에 하이드레이션(MoimApp 참조). 앱 재시작 후에도 지난 추천 재확인 가능.
 */
class SummaryRepository(context: Context) {

    private val dao = AppDatabase.getInstance(context).meetingSummaryDao()

    suspend fun save(summary: MeetingSummary) = withContext(Dispatchers.IO) {
        dao.upsert(
            MeetingSummaryEntity(
                roomId = summary.roomId,
                json = MeetingSummaryJson.toJson(summary),
                updatedAt = System.currentTimeMillis(),
            )
        )
        Log.d("SummaryRepository", "추천 결과 영속 roomId=${summary.roomId}")
    }

    suspend fun loadAll(): List<MeetingSummary> = withContext(Dispatchers.IO) {
        dao.getAll().mapNotNull { e ->
            MeetingSummaryJson.fromJson(e.json).also {
                if (it == null) Log.w("SummaryRepository", "저장된 요약 파싱 실패 — 스킵 roomId=${e.roomId}")
            }
        }
    }
}
