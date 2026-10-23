package com.navoodi.morimi.ui.screen.aireport

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.navoodi.morimi.data.model.CalendarEvent
import com.navoodi.morimi.data.model.MeetingSummary
import com.navoodi.morimi.data.model.RecommendedPlace
import com.navoodi.morimi.data.model.VerificationStatus
import com.navoodi.morimi.service.AhpEngine
import com.navoodi.morimi.service.MeetingContext
import com.navoodi.morimi.service.PlaceRanker
import com.navoodi.morimi.ui.theme.Gemma4Theme

/** Firebase 설정이 없는 개발 환경에서 실제 AI 추천 컴포저블을 샘플 데이터로 확인한다. */
class ReportPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Gemma4Theme {
                val context = LocalContext.current
                var meetingAdded by remember { mutableStateOf(false) }
                val selectedPlaces = remember { mutableStateListOf<String>() }
                val events = selectedPlaces.map { placeName ->
                    CalendarEvent(title = "샘플 모임", date = "2026-10-24", roomId = "report-preview", placeName = placeName)
                }
                val previewAction = {
                    Toast.makeText(context, "샘플 화면에서는 이동하지 않습니다", Toast.LENGTH_SHORT).show()
                }
                AIReportContent(
                    summary = sampleSummary,
                    isModelAvailable = true,
                    roomId = "report-preview",
                    events = events,
                    isAddedToCalendar = meetingAdded,
                    onBack = { finish() },
                    onModelDownload = previewAction,
                    onTogglePlace = { place ->
                        if (place.name in selectedPlaces) selectedPlaces.remove(place.name)
                        else selectedPlaces.add(place.name)
                    },
                    onVote = previewAction,
                    onAddToCalendar = { meetingAdded = true },
                    onGoToCalendar = previewAction,
                )
            }
        }
    }
}

internal val sampleSummary: MeetingSummary by lazy {
    val candidates = listOf(
        RecommendedPlace(
            name = "망원 한식당", address = "서울 마포구 망원로 87",
            reason = "약속 장소와 같은 구에 있고 식사 모임에 맞는 한식당입니다.",
            category = "음식점 > 한식", verification = VerificationStatus.VERIFIED,
        ),
        RecommendedPlace(
            name = "도봉 한식당", address = "서울 도봉구 마들로 916",
            reason = "식사에는 적합하지만 약속 지역과 다른 구에 있습니다.",
            category = "음식점 > 한식", verification = VerificationStatus.VERIFIED,
        ),
    )
    val context = MeetingContext.MEAL
    val ranked = PlaceRanker.rank(
        places = candidates,
        context = context,
        ahp = AhpEngine.solve(context.basePairwiseMatrix()),
        preferences = emptyList(),
        meetingLocation = "마포구",
    )
    MeetingSummary(
        roomId = "report-preview",
        meetingDate = "2026-10-24 (토)",
        location = "서울 마포구 망원동",
        summary = "10월 24일 오전 9시 30분에 망원동에서 식사 모임을 진행합니다. 참석 인원과 예산이 아직 확정되지 않았으므로 예약 가능 여부를 확인한 뒤 최종 장소를 정하는 것이 좋겠습니다. 이 문장은 길게 표시해도 끝까지 읽을 수 있는지 확인하기 위한 샘플입니다.",
        weather = "2026-10-24(토) 날씨 정보는 아직 제공되지 않습니다. 출발 전에 다시 확인해 주세요.",
        places = PlaceRanker.reorder(ranked),
        activities = listOf("식사 후 주변 산책"),
        itemsToBring = listOf("개인 우산"),
    )
}