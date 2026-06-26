package com.navoodi.morimi.ui.screen.calendar

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.navoodi.morimi.data.model.CalendarEvent
import com.navoodi.morimi.data.repository.CalendarRepository
import com.navoodi.morimi.ui.components.TalkPlusTabBar
import com.navoodi.morimi.ui.components.TpTab
import com.navoodi.morimi.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.time.YearMonth

class CalendarViewModel : ViewModel() {
    private val _ym = MutableStateFlow(YearMonth.now())
    val currentYearMonth: StateFlow<YearMonth> = _ym.asStateFlow()

    private val _sel = MutableStateFlow<LocalDate?>(null)
    val selectedDate: StateFlow<LocalDate?> = _sel.asStateFlow()

    val events = CalendarRepository.events

    fun prevMonth() { _ym.value = _ym.value.minusMonths(1) }
    fun nextMonth() { _ym.value = _ym.value.plusMonths(1) }
    fun selectDate(d: LocalDate) { _sel.value = if (_sel.value == d) null else d }
    fun addEvent(title: String, date: String, location: String, note: String) {
        CalendarRepository.addEvent(CalendarEvent(title = title, date = date, location = location, note = note))
    }
    fun removeEvent(id: String) = CalendarRepository.removeEvent(id)
}

private data class Category(val id: String, val color: Color, val bg: Color)
private val categories = listOf(
    Category("전체", Blue600, Blue50),
    Category("학교",  Purple600, Purple50),
    Category("운동",  Success600, Success50),
    Category("회의",  Warning600, Warning50),
    Category("모임",  Blue600, Blue50),
)
private val weekLabels = listOf("월", "화", "수", "목", "금", "토", "일")

@Composable
fun CalendarScreen(
    navController: NavController? = null,
    onTab: (TpTab) -> Unit = {},
    activeTab: TpTab = TpTab.Calendar,
    viewModel: CalendarViewModel = viewModel(),
) {
    val ym        by viewModel.currentYearMonth.collectAsStateWithLifecycle()
    val selDate   by viewModel.selectedDate.collectAsStateWithLifecycle()
    val events    by viewModel.events.collectAsStateWithLifecycle()
    val today     = remember { LocalDate.now() }
    var activeCat by remember { mutableStateOf("전체") }
    var showAdd   by remember { mutableStateOf(false) }

    val eventDates = remember(events) { events.groupBy { it.date } }

    Column(modifier = Modifier.fillMaxSize().background(White).statusBarsPadding()) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("캘린더", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Gray900, letterSpacing = (-0.5).sp)
            Box(Modifier.size(38.dp).clip(CircleShape).background(Gray50), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Search, null, tint = Gray600, modifier = Modifier.size(19.dp))
            }
        }

        // Category filter
        LazyRow(
            contentPadding = PaddingValues(horizontal = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 14.dp),
        ) {
            items(categories) { cat ->
                val active = activeCat == cat.id
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (active) cat.color else White)
                        .border(1.5.dp, if (active) cat.color else Gray200, CircleShape)
                        .clickable { activeCat = cat.id }
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                ) {
                    Text(cat.id, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (active) White else Gray500)
                }
            }
        }

        // Month nav
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 0.dp).padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(Gray100).clickable { viewModel.prevMonth() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.ChevronLeft, null, tint = Gray600, modifier = Modifier.size(16.dp))
            }
            Text("${ym.year}년 ${ym.monthValue}월", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Gray900)
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(Gray100).clickable { viewModel.nextMonth() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.ChevronRight, null, tint = Gray600, modifier = Modifier.size(16.dp))
            }
        }

        // Calendar grid
        val startOffset = (ym.atDay(1).dayOfWeek.value - 1) % 7 // Mon=0
        val daysInMonth = ym.lengthOfMonth()
        val cells: List<Int?> = List(startOffset) { null } + (1..daysInMonth).toList()
        val rows = cells.chunked(7)
        val catInfo = categories.find { it.id == activeCat } ?: categories[0]

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                weekLabels.forEachIndexed { i, d ->
                    Text(d, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 12.sp,
                        fontWeight = FontWeight.Medium, color = if (i >= 5) Blue600 else Gray400)
                }
            }
            Spacer(Modifier.height(4.dp))
            rows.forEach { row ->
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
                    repeat(7) { col ->
                        val d = row.getOrNull(col)
                        if (d == null) { Box(modifier = Modifier.weight(1f).aspectRatio(1f)) }
                        else {
                            val date = ym.atDay(d)
                            val isToday = date == today
                            val isSel   = date == selDate && !isToday
                            val dayEvs  = eventDates[date.toString()]
                            val hasMatch = if (activeCat == "전체") !dayEvs.isNullOrEmpty()
                                          else dayEvs?.any { it.note.contains(activeCat, ignoreCase = true) || activeCat == "전체" } == true
                            val dimmed  = activeCat != "전체" && !hasMatch && !isToday

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .padding(1.dp)
                                    .clip(CircleShape)
                                    .background(when {
                                        isToday -> Blue600
                                        isSel   -> Gray200
                                        hasMatch && activeCat != "전체" -> catInfo.bg
                                        else -> Color.Transparent
                                    })
                                    .then(if (dimmed) Modifier.then(Modifier) else Modifier)
                                    .clickable { viewModel.selectDate(date) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        d.toString(),
                                        fontSize = 14.sp,
                                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                        color = when {
                                            isToday -> White
                                            isSel   -> Gray900
                                            dimmed  -> Gray300
                                            else    -> Gray800
                                        },
                                    )
                                    if (!dayEvs.isNullOrEmpty() && !isToday) {
                                        Box(Modifier.size(4.dp).clip(CircleShape)
                                            .background(if (activeCat == "전체") Blue600 else catInfo.color))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Upcoming events
        Text("다가오는 일정", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Gray900,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 0.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val displayEvents = if (selDate != null) events.filter { it.date == selDate.toString() } else events
            if (displayEvents.isEmpty()) {
                item { Text("일정이 없어요", fontSize = 14.sp, color = Gray400, modifier = Modifier.padding(vertical = 24.dp)) }
            }
            items(displayEvents, key = { it.id }) { ev ->
                EventCard(ev, onRemove = { viewModel.removeEvent(ev.id) })
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        TalkPlusTabBar(active = activeTab, onTab = onTab)
    }

    // EventCard composable — defined below
    // Add FAB
    // (skipped inline — handled by existing event dialog)
    if (showAdd) {
        var title by remember { mutableStateOf("") }
        var dateStr by remember { mutableStateOf(today.toString()) }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("일정 추가") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("제목") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = dateStr, onValueChange = { dateStr = it }, label = { Text("날짜") }, placeholder = { Text("YYYY-MM-DD") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.addEvent(title, dateStr, "", ""); showAdd = false }, enabled = title.isNotBlank()) { Text("추가") } },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun EventCard(ev: CalendarEvent, onRemove: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Gray50)
            .border(1.dp, Gray100, RoundedCornerShape(14.dp))
            .padding(13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Blue600))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(ev.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Gray900)
                if (ev.placeName.isNotBlank()) {
                    Text(ev.placeName, fontSize = 12.sp, color = Blue700, fontWeight = FontWeight.Medium)
                }
                Text(ev.date, fontSize = 12.sp, color = Gray400)
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, null, tint = Gray300, modifier = Modifier.size(18.dp))
            }
        }
        if (ev.placeUrl.isNotBlank()) {
            TextButton(
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ev.placeUrl)))
                    } catch (_: Exception) {}
                },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            ) {
                Text("🗺️ 카카오맵 보기", fontSize = 12.sp, color = Blue600, fontWeight = FontWeight.Medium)
            }
        }
    }
}