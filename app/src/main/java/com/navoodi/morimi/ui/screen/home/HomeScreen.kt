package com.navoodi.morimi.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.navoodi.morimi.ui.components.TalkPlusTabBar
import com.navoodi.morimi.ui.components.TpTab
import com.navoodi.morimi.ui.theme.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DAY_LABELS = listOf("월", "화", "수", "목", "금", "토", "일")
private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy년 M월 d일")

@Composable
fun HomeScreen(
    onOpenChat: (String) -> Unit,
    onTab: (TpTab) -> Unit,
    activeTab: TpTab,
    viewModel: HomeViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val today = uiState.today
    val monday = today.with(DayOfWeek.MONDAY)
    val todayDayIdx = today.dayOfWeek.value - 1  // 0=월 … 6=일

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MoColors.surfaceBase)
            .statusBarsPadding()
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = MoSpacing.screenFocused, end = MoSpacing.screenFocused, top = 2.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column {
                Text(
                    today.format(DATE_FORMATTER),
                    color = MoColors.textTertiary,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "오늘",
                    color = MoColors.textPrimary,
                    style = MaterialTheme.typography.displayMedium,
                )
            }
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(MoColors.surfaceSubtle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Notifications, contentDescription = "알림", tint = MoColors.textSecondary, modifier = Modifier.size(20.dp))
            }
        }

        // Week strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 18.dp),
        ) {
            DAY_LABELS.forEachIndexed { i, day ->
                val date: LocalDate = monday.plusDays(i.toLong())
                val isToday = i == todayDayIdx
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        day,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isToday) MoColors.brand else MoColors.textTertiary,
                        fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(if (isToday) MoColors.brand else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            date.dayOfMonth.toString(),
                            fontFamily = Pretendard,
                            fontSize = 14.sp,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                            color = when {
                                isToday -> MoColors.textOnBrand
                                i < todayDayIdx -> MoColors.textDisabled
                                else -> MoColors.textSecondary
                            },
                        )
                    }
                    if (isToday) {
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.size(4.dp).clip(CircleShape).background(MoColors.brand))
                    }
                }
            }
        }

        // Timeline
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MoSpacing.screenOpen)
                .padding(bottom = 12.dp),
        ) {
            SectionLabel("오늘 일정")

            if (uiState.todayEvents.isEmpty()) {
                EmptyState("오늘 일정이 없어요")
            } else {
                Row {
                    // Timeline line + dots
                    Box(modifier = Modifier.width(40.dp)) {
                        Box(
                            modifier = Modifier
                                .width(1.5.dp)
                                .fillMaxHeight()
                                .background(MoColors.border)
                                .align(Alignment.Center)
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            uiState.todayEvents.forEach { ev ->
                                val cardH = if (ev.isUpcoming) 116.dp else 80.dp
                                Box(modifier = Modifier.height(cardH + 8.dp), contentAlignment = Alignment.TopCenter) {
                                    Box(
                                        modifier = Modifier
                                            .padding(top = 20.dp)
                                            .size(if (ev.isUpcoming) 14.dp else 10.dp)
                                            .clip(CircleShape)
                                            .background(if (ev.isUpcoming) MoColors.brand else MoColors.surfaceBase)
                                            .border(2.dp, if (ev.isUpcoming) MoColors.brand else MoColors.borderStrong, CircleShape)
                                    )
                                }
                            }
                        }
                    }

                    // Cards
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        uiState.todayEvents.forEach { ev ->
                            val cardH = if (ev.isUpcoming) 116.dp else 80.dp
                            val bg = if (ev.isUpcoming) MoColors.brand else MoColors.surfaceSubtle
                            val textColor = if (ev.isUpcoming) MoColors.textOnBrand else MoColors.textPrimary
                            Box(
                                modifier = Modifier
                                    .height(cardH)
                                    .fillMaxWidth()
                                    .clip(MoRadius.lg)
                                    .background(bg)
                                    .border(
                                        if (ev.isUpcoming) 0.dp else 1.dp,
                                        if (ev.isUpcoming) Color.Transparent else MoColors.border,
                                        MoRadius.lg,
                                    )
                                    .then(
                                        if (ev.chatId != null)
                                            Modifier.clickable { onOpenChat(ev.chatId) }
                                        else Modifier
                                    )
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(ev.title, fontFamily = Pretendard, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = textColor)
                                        if (ev.location.isNotBlank()) {
                                            Text(
                                                ev.location,
                                                fontFamily = Pretendard,
                                                fontSize = 12.sp,
                                                color = if (ev.isUpcoming) MoColors.textOnBrand.copy(0.8f) else MoColors.textTertiary,
                                            )
                                        }
                                    }
                                    if (ev.isUpcoming && ev.memberCount > 0) {
                                        Spacer(Modifier.height(10.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                                            ev.memberInitials.forEach { initial ->
                                                Box(
                                                    modifier = Modifier
                                                        .size(26.dp)
                                                        .clip(CircleShape)
                                                        .background(MoColors.textOnBrand.copy(0.25f))
                                                        .border(1.5.dp, MoColors.textOnBrand.copy(0.6f), CircleShape),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Text(initial, fontFamily = Pretendard, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MoColors.textOnBrand)
                                                }
                                            }
                                            Spacer(Modifier.width(8.dp))
                                            Text("+${ev.memberCount}명 참석", fontFamily = Pretendard, fontSize = 11.sp, color = MoColors.textOnBrand.copy(0.8f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel("내일 일정")

            if (uiState.tomorrowEvents.isEmpty()) {
                EmptyState("내일 일정이 없어요")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    uiState.tomorrowEvents.forEach { ev ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MoRadius.lg)
                                .background(MoColors.surfaceSubtle)
                                .border(1.dp, MoColors.border, MoRadius.lg)
                                .then(
                                    if (ev.chatId != null)
                                        Modifier.clickable { onOpenChat(ev.chatId) }
                                    else Modifier
                                )
                                .padding(13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                ev.location.ifBlank { "—" },
                                fontFamily = Pretendard,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MoColors.brand,
                            )
                            Spacer(Modifier.width(14.dp))
                            Box(Modifier.width(1.dp).height(32.dp).background(MoColors.border))
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text(ev.title, fontFamily = Pretendard, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MoColors.textPrimary)
                                if (ev.memberCount > 0) {
                                    Text("${ev.memberCount}명", fontFamily = Pretendard, fontSize = 12.sp, color = MoColors.textTertiary)
                                }
                            }
                        }
                    }
                }
            }
        }

        TalkPlusTabBar(active = activeTab, onTab = onTab)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MoColors.textTertiary,
        modifier = Modifier.padding(bottom = 14.dp),
    )
}

@Composable
private fun EmptyState(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, fontFamily = Pretendard, fontSize = 13.sp, color = MoColors.textTertiary)
    }
}
