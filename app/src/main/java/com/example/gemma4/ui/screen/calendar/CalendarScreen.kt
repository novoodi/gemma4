package com.example.gemma4.ui.screen.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.gemma4.data.model.CalendarEvent
import com.example.gemma4.data.repository.CalendarRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.time.YearMonth

class CalendarViewModel : ViewModel() {
    private val _currentYearMonth = MutableStateFlow(YearMonth.now())
    val currentYearMonth: StateFlow<YearMonth> = _currentYearMonth.asStateFlow()

    private val _selectedDate = MutableStateFlow<LocalDate?>(null)
    val selectedDate: StateFlow<LocalDate?> = _selectedDate.asStateFlow()

    val events = CalendarRepository.events

    fun prevMonth() { _currentYearMonth.value = _currentYearMonth.value.minusMonths(1) }
    fun nextMonth() { _currentYearMonth.value = _currentYearMonth.value.plusMonths(1) }

    fun selectDate(date: LocalDate) {
        _selectedDate.value = if (_selectedDate.value == date) null else date
    }

    fun addEvent(title: String, date: String, location: String, note: String) {
        CalendarRepository.addEvent(CalendarEvent(title = title, date = date, location = location, note = note))
    }

    fun removeEvent(id: String) { CalendarRepository.removeEvent(id) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    navController: NavController,
    viewModel: CalendarViewModel = viewModel()
) {
    val yearMonth by viewModel.currentYearMonth.collectAsStateWithLifecycle()
    val selectedDate by viewModel.selectedDate.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now() }
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("캘린더") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "일정 추가")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 8.dp)
        ) {
            MonthHeader(
                yearMonth = yearMonth,
                onPrev = viewModel::prevMonth,
                onNext = viewModel::nextMonth
            )
            Spacer(modifier = Modifier.height(4.dp))
            CalendarGrid(
                yearMonth = yearMonth,
                events = events,
                selectedDate = selectedDate,
                today = today,
                onDateSelected = viewModel::selectDate
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            if (selectedDate != null) {
                val dateStr = selectedDate.toString()
                val dateEvents = events.filter { it.date == dateStr }
                Text(
                    text = "${selectedDate!!.year}년 ${selectedDate!!.monthValue}월 ${selectedDate!!.dayOfMonth}일",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                )
                if (dateEvents.isEmpty()) {
                    Text(
                        text = "일정이 없어요",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(dateEvents, key = { it.id }) { event ->
                            EventItem(event = event, onDelete = { viewModel.removeEvent(event.id) })
                        }
                    }
                }
            } else {
                Text(
                    text = "날짜를 선택하면 일정을 볼 수 있어요",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }

    if (showAddDialog) {
        AddEventDialog(
            initialDate = selectedDate?.toString() ?: today.toString(),
            onDismiss = { showAddDialog = false },
            onConfirm = { title, date, location, note ->
                viewModel.addEvent(title, date, location, note)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun MonthHeader(yearMonth: YearMonth, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPrev) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "이전 달")
        }
        Text(
            text = "${yearMonth.year}년 ${yearMonth.monthValue}월",
            style = MaterialTheme.typography.titleLarge
        )
        IconButton(onClick = onNext) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "다음 달")
        }
    }
}

@Composable
private fun CalendarGrid(
    yearMonth: YearMonth,
    events: List<CalendarEvent>,
    selectedDate: LocalDate?,
    today: LocalDate,
    onDateSelected: (LocalDate) -> Unit
) {
    // MON=1..SUN=7 → Sun=0..Sat=6
    val firstDayOffset = yearMonth.atDay(1).dayOfWeek.value % 7
    val daysInMonth = yearMonth.lengthOfMonth()

    val cells: List<LocalDate?> = buildList {
        repeat(firstDayOffset) { add(null) }
        for (day in 1..daysInMonth) add(yearMonth.atDay(day))
        while (size % 7 != 0) add(null)
    }

    val eventDates = remember(events) { events.map { it.date }.toSet() }

    Column {
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("일", "월", "화", "수", "목", "금", "토").forEach { label ->
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        cells.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    DayCell(
                        date = date,
                        isToday = date == today,
                        isSelected = date == selectedDate,
                        hasEvents = date != null && date.toString() in eventDates,
                        modifier = Modifier.weight(1f),
                        onClick = { date?.let { onDateSelected(it) } }
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate?,
    isToday: Boolean,
    isSelected: Boolean,
    hasEvents: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(CircleShape)
            .then(if (date != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(
                when {
                    isSelected -> MaterialTheme.colorScheme.primary
                    isToday -> MaterialTheme.colorScheme.primaryContainer
                    else -> Color.Transparent
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (date != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        isSelected -> MaterialTheme.colorScheme.onPrimary
                        isToday -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
                if (hasEvents) {
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .background(
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.primary,
                                shape = CircleShape
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun EventItem(event: CalendarEvent, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = event.title, style = MaterialTheme.typography.titleSmall)
                if (event.location.isNotBlank()) {
                    Text(
                        text = event.location,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (event.note.isNotBlank()) {
                    Text(
                        text = event.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "삭제",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun AddEventDialog(
    initialDate: String,
    onDismiss: () -> Unit,
    onConfirm: (title: String, date: String, location: String, note: String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(initialDate) }
    var location by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("일정 추가") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("제목") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("날짜") },
                    placeholder = { Text("YYYY-MM-DD") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("장소 (선택)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("메모 (선택)") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title, date, location, note) },
                enabled = title.isNotBlank() && date.isNotBlank()
            ) { Text("추가") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}
