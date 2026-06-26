package com.navoodi.morimi.ui.screen.aireport

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.navoodi.morimi.MoimApp
import com.navoodi.morimi.data.model.CalendarEvent
import com.navoodi.morimi.data.model.MeetingSummary
import com.navoodi.morimi.data.model.RecommendedPlace
import com.navoodi.morimi.data.repository.CalendarRepository
import com.navoodi.morimi.data.repository.ChatRepository
import com.navoodi.morimi.navigation.Screen
import com.navoodi.morimi.ui.theme.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// ── ViewModel ─────────────────────────────────────────────────────────────────

class AIReportViewModel(app: Application, savedStateHandle: SavedStateHandle) : AndroidViewModel(app) {
    val roomId: String = checkNotNull(savedStateHandle["roomId"])
    val isModelAvailable: Boolean = (app as MoimApp).llmService.isModelAvailable

    val summary: StateFlow<MeetingSummary?> = ChatRepository.summaries
        .map { it[roomId] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val isAddedToCalendar: StateFlow<Boolean> = CalendarRepository.events
        .map { events -> events.any { it.roomId == roomId && it.placeName.isBlank() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun addToCalendar() {
        val s = summary.value ?: return
        viewModelScope.launch {
            val name = ChatRepository.getRoomById(roomId)?.name ?: "모임"
            CalendarRepository.addEvent(
                CalendarEvent(title = name, date = s.meetingDate, location = s.location, note = s.recommendation, roomId = roomId)
            )
        }
    }

    fun addPlaceToCalendar(place: RecommendedPlace) {
        val s = summary.value ?: return
        viewModelScope.launch {
            val roomName = ChatRepository.getRoomById(roomId)?.name ?: "모임"
            CalendarRepository.addEvent(
                CalendarEvent(
                    title = roomName,
                    date = s.meetingDate,
                    roomId = roomId,
                    placeName = place.name,
                    placeAddress = place.address,
                    placeUrl = place.placeUrl,
                    location = place.address,
                )
            )
        }
    }

    fun togglePlace(place: RecommendedPlace) {
        if (CalendarRepository.isPlaceAdded(roomId, place.placeUrl, place.name)) {
            CalendarRepository.removePlaceEvent(roomId, place.placeUrl, place.name)
        } else {
            addPlaceToCalendar(place)
        }
    }
}

// ── Page types ────────────────────────────────────────────────────────────────

private sealed interface CardPage {
    data object Meeting : CardPage
    data class Place(val index: Int) : CardPage
    data object Activities : CardPage
    data object ItemsToBring : CardPage
    data object Finish : CardPage
}

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun AIReportScreen(navController: NavController, viewModel: AIReportViewModel = viewModel()) {
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val isAddedToCalendar by viewModel.isAddedToCalendar.collectAsStateWithLifecycle()
    val events by CalendarRepository.events.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8F9FC))
            .statusBarsPadding()
    ) {
        // Nav bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(White)
                .padding(horizontal = 8.dp),
        ) {
            IconButton(
                onClick = { navController.popBackStack() },
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로", tint = Gray900)
            }
            Text(
                "AI 추천",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = Gray900,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        HorizontalDivider(color = Gray100)

        // Model unavailable banner
        if (!viewModel.isModelAvailable) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFFFF3CD))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "⚠️ AI 모델 미설치 · 샘플 결과",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF856404),
                )
                TextButton(
                    onClick = { navController.navigate(Screen.ModelDownload.route) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text("다운로드", fontSize = 12.sp, color = Blue600, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (summary == null) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "채팅방에서 AI 요약 버튼을 눌러\n분석을 시작해주세요",
                    fontSize = 14.sp,
                    color = Gray400,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp,
                )
            }
        } else {
            val s = summary!!
            val pages: List<CardPage> = buildList {
                add(CardPage.Meeting)
                s.places.indices.forEach { add(CardPage.Place(it)) }
                if (s.activities.isNotEmpty()) add(CardPage.Activities)
                if (s.itemsToBring.isNotEmpty()) add(CardPage.ItemsToBring)
                add(CardPage.Finish)
            }
            val pagerState = rememberPagerState(pageCount = { pages.size })

            // Dot indicator
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                pages.indices.forEach { i ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(if (pagerState.currentPage == i) 8.dp else 5.dp)
                            .clip(CircleShape)
                            .background(if (pagerState.currentPage == i) Blue600 else Gray200)
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp),
                pageSpacing = 12.dp,
            ) { pageIndex ->
                when (val page = pages[pageIndex]) {
                    CardPage.Meeting -> MeetingCard(
                        summary = s,
                        isAdded = isAddedToCalendar,
                        onAddToCalendar = viewModel::addToCalendar,
                    )
                    is CardPage.Place -> PlaceCard(
                        place = s.places[page.index],
                        roomId = viewModel.roomId,
                        events = events,
                        onToggle = viewModel::togglePlace,
                    )
                    CardPage.Activities -> ListCard(
                        title = "🎉 추천 활동",
                        items = s.activities,
                    )
                    CardPage.ItemsToBring -> ListCard(
                        title = "🎒 챙겨갈 것",
                        items = s.itemsToBring,
                    )
                    CardPage.Finish -> FinishCard(
                        roomId = viewModel.roomId,
                        events = events,
                        navController = navController,
                        onGoToCalendar = {
                            navController.navigate(Screen.Calendar.route) {
                                popUpTo(navController.graph.id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            }
        }
    }
}

// ── Card: 모임 확인 ────────────────────────────────────────────────────────────

@Composable
private fun MeetingCard(
    summary: MeetingSummary,
    isAdded: Boolean,
    onAddToCalendar: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(White)
                .border(1.dp, Gray100, RoundedCornerShape(16.dp))
                .padding(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 14.dp),
            ) {
                Icon(GeminiIcon, contentDescription = null, tint = Blue600, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("AI가 분석한 모임 정보", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Blue600)
            }
            val rows = listOf(
                Triple("📅", "날짜", summary.meetingDate.ifBlank { "미정" }),
                Triple("📍", "장소", summary.location.ifBlank { "미정" }),
                Triple("🎯", "요약", summary.summary.take(80).ifBlank { "분석 결과 없음" }),
                Triple("🌤️", "날씨", summary.weather.ifBlank { "정보 없음" }),
            )
            rows.forEachIndexed { i, (icon, label, value) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (i < rows.size - 1) Modifier.padding(bottom = 10.dp) else Modifier),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(icon, fontSize = 16.sp)
                    Column {
                        Text(label, fontSize = 11.sp, color = Gray400, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(2.dp))
                        Text(value, fontSize = 14.sp, color = Gray900, fontWeight = FontWeight.Medium)
                    }
                }
                if (i < rows.size - 1) HorizontalDivider(color = Gray100, modifier = Modifier.padding(vertical = 5.dp))
            }
        }

        OutlinedButton(
            onClick = onAddToCalendar,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
            enabled = !isAdded,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (isAdded) Success600 else Blue600,
            ),
        ) {
            Text(
                text = if (isAdded) "✓ 캘린더에 추가됨" else "📅 캘린더에 모임 추가",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// ── Card: 장소 ────────────────────────────────────────────────────────────────

@Composable
private fun PlaceCard(
    place: RecommendedPlace,
    roomId: String,
    events: List<CalendarEvent>,
    onToggle: (RecommendedPlace) -> Unit,
) {
    val context = LocalContext.current
    val isAdded = events.any { e ->
        e.roomId == roomId &&
        if (place.placeUrl.isNotBlank()) e.placeUrl == place.placeUrl
        else e.placeName == place.name
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(White)
                .border(1.dp, Gray100, RoundedCornerShape(16.dp))
                .padding(16.dp),
        ) {
            Text(place.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Gray900)
            if (place.address.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(place.address, fontSize = 13.sp, color = Gray500)
            }
            if (place.reason.isNotBlank()) {
                HorizontalDivider(color = Gray100, modifier = Modifier.padding(vertical = 12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(GeminiIcon, contentDescription = null, tint = Blue600, modifier = Modifier.size(14.dp))
                    Text(place.reason, fontSize = 14.sp, color = Gray700, lineHeight = 22.sp)
                }
            }
        }

        if (place.placeUrl.isNotBlank()) {
            OutlinedButton(
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(place.placeUrl)))
                    } catch (_: Exception) {}
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5722)),
            ) {
                Text("🗺️ 카카오맵에서 보기", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Button(
            onClick = { onToggle(place) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isAdded) Gray200 else Blue600,
                contentColor = if (isAdded) Gray600 else White,
            ),
        ) {
            Text(
                text = if (isAdded) "✓ 담음  (탭하면 취소)" else "➕ 이 장소 담기",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// ── Card: 리스트 (활동 / 챙겨갈 것) ───────────────────────────────────────────

@Composable
private fun ListCard(title: String, items: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(White)
                .border(1.dp, Gray100, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Gray900)
            HorizontalDivider(color = Gray100)
            items.forEach { item ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("•", fontSize = 14.sp, color = Blue600, fontWeight = FontWeight.Bold)
                    Text(item, fontSize = 14.sp, color = Gray700, lineHeight = 22.sp)
                }
            }
        }
    }
}

// ── Card: 마무리 ──────────────────────────────────────────────────────────────

@Composable
private fun FinishCard(
    roomId: String,
    events: List<CalendarEvent>,
    navController: NavController,
    onGoToCalendar: () -> Unit,
) {
    val addedCount = events.count { it.roomId == roomId && it.placeName.isNotBlank() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(Color(0xFFEFF8FF), Color(0xFFDBEAFE))))
                .border(1.dp, Blue100, RoundedCornerShape(16.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("✅", fontSize = 40.sp)
            Text("마무리", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Gray900)
            Text(
                text = if (addedCount > 0)
                    "마음에 드는 장소 ${addedCount}곳을\n캘린더에 담았어요!"
                else
                    "마음에 드는 장소를\n캘린더에 담아보세요",
                fontSize = 15.sp,
                color = Gray700,
                lineHeight = 24.sp,
                textAlign = TextAlign.Center,
            )
        }

        Button(
            onClick = onGoToCalendar,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Blue600),
        ) {
            Text("📅 캘린더에서 확인하기", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }

        OutlinedButton(
            onClick = { navController.navigate(Screen.Vote.createRoute(roomId)) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("👍 그룹 투표 시작하기", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ── GeminiIcon ────────────────────────────────────────────────────────────────

private val GeminiIcon: ImageVector = ImageVector.Builder(
    name = "Gemini", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).path(fill = SolidColor(Color.Black)) {
    moveTo(12f, 2f)
    curveTo(11.5f, 7.8f, 7.8f, 11.5f, 2f, 12f)
    curveTo(7.8f, 12.5f, 11.5f, 16.2f, 12f, 22f)
    curveTo(12.5f, 16.2f, 16.2f, 12.5f, 22f, 12f)
    curveTo(16.2f, 11.5f, 12.5f, 7.8f, 12f, 2f)
    close()
}.build()