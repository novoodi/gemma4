package com.navoodi.morimi.ui.screen.calendar

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
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
    val categories = CalendarRepository.categories

    fun prevMonth() { _ym.value = _ym.value.minusMonths(1) }
    fun nextMonth() { _ym.value = _ym.value.plusMonths(1) }
    fun selectDate(d: LocalDate?) { _sel.value = d }
    fun addEvent(title: String, date: String, time: String, location: String, note: String) {
        CalendarRepository.addEvent(
            CalendarEvent(title = title.trim(), date = date, time = time.trim(), location = location.trim(), note = note.trim())
        )
    }
    fun removeEvent(id: String) = CalendarRepository.removeEvent(id)
    fun setEventCategory(id: String, category: String) = CalendarRepository.updateEventCategory(id, category)
    fun addCategory(name: String): Boolean = CalendarRepository.addCategory(name)
    fun removeCategory(name: String) = CalendarRepository.removeCategory(name)
}

private const val ALL = "전체"

/** 카테고리 칩 색상 — 사용자가 추가한 카테고리는 순서대로 팔레트를 순환 */
private data class CatStyle(val color: Color, val bg: Color)
private val allStyle = CatStyle(MoColors.brand, MoColors.brandSubtle)
private val catPalette = listOf(
    CatStyle(MoColors.activity, MoColors.activityBg),
    CatStyle(MoColors.place,    MoColors.placeBg),
    CatStyle(MoColors.item,     MoColors.itemBg),
    CatStyle(Color(0xFF0EA5E9), Color(0xFFE0F2FE)),  // sky blue
    CatStyle(Color(0xFFEF4444), Color(0xFFFFF0F0)),
    CatStyle(Color(0xFFEC4899), Color(0xFFFDF2F8)),
)
private fun styleOf(category: String, categories: List<String>): CatStyle {
    val idx = categories.indexOf(category)
    return if (idx < 0) allStyle else catPalette[idx % catPalette.size]
}

private val weekLabels = listOf("월", "화", "수", "목", "금", "토", "일")

@OptIn(ExperimentalFoundationApi::class)
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
    val cats      by viewModel.categories.collectAsStateWithLifecycle()
    val today     = remember { LocalDate.now() }
    var activeCat by remember { mutableStateOf(ALL) }
    var addDate   by remember { mutableStateOf<LocalDate?>(null) }       // 일정 추가 다이얼로그
    var showNewCat by remember { mutableStateOf(false) }                 // 카테고리 추가 다이얼로그
    var catToDelete by remember { mutableStateOf<String?>(null) }        // 카테고리 삭제 확인
    var catTarget by remember { mutableStateOf<CalendarEvent?>(null) }   // 카테고리 지정 대상 일정

    // 삭제된 카테고리를 보고 있었다면 전체로 복귀
    LaunchedEffect(cats) { if (activeCat != ALL && activeCat !in cats) activeCat = ALL }

    val eventDates = remember(events) { events.groupBy { it.date } }

    Column(modifier = Modifier.fillMaxSize().background(MoColors.surfaceBase).statusBarsPadding()) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = MoSpacing.screenFocused, end = MoSpacing.screenFocused, top = 4.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("캘린더", style = MaterialTheme.typography.headlineMedium, color = MoColors.textPrimary)
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(MoColors.brand)
                    .clickable { addDate = selDate ?: today },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Add, "일정 추가", tint = MoColors.textOnBrand, modifier = Modifier.size(20.dp))
            }
        }

        // Category filter
        LazyRow(
            contentPadding = PaddingValues(horizontal = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 14.dp),
        ) {
            val chips = listOf(ALL) + cats
            items(chips, key = { it }) { cat ->
                val active = activeCat == cat
                val style = styleOf(cat, cats)
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (active) style.color else MoColors.surfaceBase)
                        .border(1.5.dp, if (active) style.color else MoColors.border, CircleShape)
                        .combinedClickable(
                            onClick = { activeCat = cat },
                            // "전체"는 삭제 불가 — 사용자 카테고리만 길게 눌러 삭제
                            onLongClick = if (cat != ALL) ({ catToDelete = cat }) else null,
                        )
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                ) {
                    Text(cat, fontFamily = Pretendard, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (active) MoColors.textOnBrand else MoColors.textSecondary)
                }
            }
            item(key = "__add__") {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .border(1.5.dp, MoColors.border, CircleShape)
                        .clickable { showNewCat = true }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Add, "카테고리 추가", tint = MoColors.textSecondary, modifier = Modifier.size(16.dp))
                }
            }
        }

        // Month nav
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 0.dp).padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(32.dp).clip(MoRadius.sm).background(MoColors.surfaceSubtle).clickable { viewModel.prevMonth() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.ChevronLeft, "이전 달", tint = MoColors.textSecondary, modifier = Modifier.size(16.dp))
            }
            Text("${ym.year}년 ${ym.monthValue}월", fontFamily = Pretendard, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MoColors.textPrimary)
            Box(Modifier.size(32.dp).clip(MoRadius.sm).background(MoColors.surfaceSubtle).clickable { viewModel.nextMonth() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.ChevronRight, "다음 달", tint = MoColors.textSecondary, modifier = Modifier.size(16.dp))
            }
        }

        // Calendar grid
        val startOffset = (ym.atDay(1).dayOfWeek.value - 1) % 7 // Mon=0
        val daysInMonth = ym.lengthOfMonth()
        val cells: List<Int?> = List(startOffset) { null } + (1..daysInMonth).toList()
        val rows = cells.chunked(7)
        val catInfo = styleOf(activeCat, cats)

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                weekLabels.forEachIndexed { i, d ->
                    Text(d, fontFamily = Pretendard, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 12.sp,
                        fontWeight = FontWeight.Medium, color = if (i >= 5) MoColors.brand else MoColors.textTertiary)
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
                            val hasMatch = if (activeCat == ALL) !dayEvs.isNullOrEmpty()
                                          else dayEvs?.any { it.category == activeCat } == true
                            val dimmed  = activeCat != ALL && !hasMatch && !isToday

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .padding(1.dp)
                                    .clip(CircleShape)
                                    .background(when {
                                        isToday -> MoColors.brand
                                        isSel   -> MoColors.borderStrong
                                        hasMatch && activeCat != ALL -> catInfo.bg
                                        else -> Color.Transparent
                                    })
                                    // 날짜 탭 → 그 날짜 선택 + 일정 추가 다이얼로그
                                    .clickable { viewModel.selectDate(date); addDate = date },
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        d.toString(),
                                        fontFamily = Pretendard,
                                        fontSize = 14.sp,
                                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                        color = when {
                                            isToday -> MoColors.textOnBrand
                                            isSel   -> MoColors.textPrimary
                                            dimmed  -> MoColors.textDisabled
                                            else    -> MoColors.textPrimary
                                        },
                                    )
                                    if (!dayEvs.isNullOrEmpty() && !isToday) {
                                        Box(Modifier.size(4.dp).clip(CircleShape)
                                            .background(if (activeCat == ALL) MoColors.brand else catInfo.color))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Upcoming events
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (selDate != null) "${selDate!!.monthValue}월 ${selDate!!.dayOfMonth}일 일정" else "다가오는 일정",
                style = MaterialTheme.typography.titleMedium, color = MoColors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            if (selDate != null) {
                Text("전체 보기", fontFamily = Pretendard, fontSize = 13.sp, color = MoColors.brand,
                    modifier = Modifier.clickable { viewModel.selectDate(null) })
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 0.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val todayStr = today.toString()
            val displayEvents = events
                // 지난 일정은 숨김. AI 추천 일정은 날짜가 "10월 3일"처럼 비정형일 수 있어 그런 건 항상 표시
                .filter { if (selDate != null) it.date == selDate.toString() else !DATE_REGEX.matches(it.date) || it.date >= todayStr }
                .filter { activeCat == ALL || it.category == activeCat }
                .sortedWith(compareBy<CalendarEvent>({ it.date }, { it.time.ifBlank { "99:99" } }))
            if (displayEvents.isEmpty()) {
                item { Text("일정이 없어요", fontFamily = Pretendard, fontSize = 14.sp, color = MoColors.textTertiary, modifier = Modifier.padding(vertical = 24.dp)) }
            }
            items(displayEvents, key = { it.id }) { ev ->
                EventCard(
                    ev = ev,
                    style = if (ev.category.isBlank()) allStyle else styleOf(ev.category, cats),
                    onClick = { catTarget = ev },
                    onRemove = { viewModel.removeEvent(ev.id) },
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        TalkPlusTabBar(active = activeTab, onTab = onTab)
    }

    addDate?.let { d ->
        AddEventDialog(
            initialDate = d,
            onDismiss = { addDate = null },
            onAdd = { title, date, time, location, note ->
                viewModel.addEvent(title, date, time, location, note)
                addDate = null
            },
        )
    }

    if (showNewCat) {
        var name by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showNewCat = false },
            title = { Text("카테고리 추가") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(10); error = null },
                    label = { Text("이름 (최대 10자)") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        if (viewModel.addCategory(name)) { activeCat = name.trim(); showNewCat = false }
                        else error = "이미 있거나 사용할 수 없는 이름이에요"
                    },
                ) { Text("추가") }
            },
            dismissButton = { TextButton(onClick = { showNewCat = false }) { Text("취소") } },
        )
    }

    catToDelete?.let { cat ->
        AlertDialog(
            onDismissRequest = { catToDelete = null },
            title = { Text("카테고리 삭제") },
            text = { Text("'$cat' 카테고리를 삭제할까요?\n이 카테고리의 일정은 삭제되지 않고 미분류로 바뀝니다.") },
            confirmButton = {
                TextButton(onClick = { viewModel.removeCategory(cat); catToDelete = null }) {
                    Text("삭제", color = MoRed500)
                }
            },
            dismissButton = { TextButton(onClick = { catToDelete = null }) { Text("취소") } },
        )
    }

    catTarget?.let { ev ->
        AlertDialog(
            onDismissRequest = { catTarget = null },
            title = { Text("카테고리 지정") },
            text = {
                Column {
                    Text(ev.title, fontFamily = Pretendard, fontSize = 13.sp, color = MoColors.textTertiary,
                        modifier = Modifier.padding(bottom = 8.dp))
                    (listOf("") + cats).forEach { c ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(MoRadius.md)
                                .clickable { viewModel.setEventCategory(ev.id, c); catTarget = null }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = ev.category == c,
                                onClick = { viewModel.setEventCategory(ev.id, c); catTarget = null },
                            )
                            if (c.isNotEmpty()) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(styleOf(c, cats).color))
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(c.ifEmpty { "미분류" }, fontFamily = Pretendard, fontSize = 15.sp, color = MoColors.textPrimary)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { catTarget = null }) { Text("닫기") } },
        )
    }
}

private val TIME_REGEX = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
private val DATE_REGEX = Regex("^\\d{4}-\\d{2}-\\d{2}$")

@Composable
private fun AddEventDialog(
    initialDate: LocalDate,
    onDismiss: () -> Unit,
    onAdd: (title: String, date: String, time: String, location: String, note: String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var dateStr by remember { mutableStateOf(initialDate.toString()) }
    var time by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    val dateValid = DATE_REGEX.matches(dateStr) && runCatching { LocalDate.parse(dateStr) }.isSuccess
    val timeValid = time.isBlank() || TIME_REGEX.matches(time)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("일정 추가") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("제목") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = dateStr, onValueChange = { dateStr = it }, label = { Text("날짜") },
                        placeholder = { Text("YYYY-MM-DD") }, singleLine = true, isError = !dateValid,
                        modifier = Modifier.weight(1.4f),
                    )
                    OutlinedTextField(
                        value = time, onValueChange = { time = it.take(5) }, label = { Text("시간") },
                        placeholder = { Text("19:00") }, singleLine = true, isError = !timeValid,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(value = location, onValueChange = { location = it }, label = { Text("장소") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("메모") }, maxLines = 3, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(title, dateStr, time, location, note) },
                enabled = title.isNotBlank() && dateValid && timeValid,
            ) { Text("추가") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun EventCard(ev: CalendarEvent, style: CatStyle, onClick: () -> Unit, onRemove: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MoRadius.lg)
            .background(MoColors.surfaceSubtle)
            .border(1.dp, MoColors.border, MoRadius.lg)
            .clickable(onClick = onClick)   // 탭 → 카테고리 지정
            .padding(13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(style.color))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ev.title, fontFamily = Pretendard, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = MoColors.textPrimary, modifier = Modifier.weight(1f, fill = false))
                    if (ev.category.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            ev.category, fontFamily = Pretendard, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                            color = style.color,
                            modifier = Modifier.clip(CircleShape).background(style.bg).padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
                if (ev.placeName.isNotBlank()) {
                    Text(ev.placeName, fontFamily = Pretendard, fontSize = 12.sp, color = MoColors.place, fontWeight = FontWeight.Medium)
                } else if (ev.location.isNotBlank()) {
                    Text(ev.location, fontFamily = Pretendard, fontSize = 12.sp, color = MoColors.textSecondary)
                }
                Text(
                    listOf(ev.date, ev.time).filter { it.isNotBlank() }.joinToString("  "),
                    fontFamily = Pretendard, fontSize = 12.sp, color = MoColors.textTertiary,
                )
                if (ev.note.isNotBlank()) {
                    Text(ev.note, fontFamily = Pretendard, fontSize = 12.sp, color = MoColors.textSecondary, maxLines = 2)
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, "삭제", tint = MoColors.textDisabled, modifier = Modifier.size(18.dp))
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
                Text("🗺️ 카카오맵 보기", fontFamily = Pretendard, fontSize = 12.sp, color = MoColors.brand, fontWeight = FontWeight.Medium)
            }
        }
    }
}