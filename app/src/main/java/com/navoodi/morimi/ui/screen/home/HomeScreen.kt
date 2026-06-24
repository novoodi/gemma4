package com.navoodi.morimi.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navoodi.morimi.ui.components.TalkPlusTabBar
import com.navoodi.morimi.ui.components.TpTab
import com.navoodi.morimi.ui.theme.*

private data class ScheduleEvent(
    val id: Int, val time: String, val title: String, val sub: String,
    val members: List<String>, val bg: Color, val textColor: Color,
    val active: Boolean = false, val past: Boolean = false,
    val chatId: String? = null,
)

private val weekDays  = listOf("월", "화", "수", "목", "금", "토", "일")
private val weekDates = listOf(20, 21, 22, 23, 24, 25, 26)
private const val todayIdx = 2

private val todayEvents = listOf(
    ScheduleEvent(1, "09:00", "팀 스탠드업",   "매주 월요일 · 15분",         listOf("김", "이", "박"),       Gray50,   Gray900, past = true),
    ScheduleEvent(2, "12:30", "팀 점심 약속",  "카페 보라 · 인사동",          listOf("김", "이", "박", "최"), Blue600,  White,   active = true, chatId = "room1"),
    ScheduleEvent(3, "15:00", "스터디 그룹",   "강남 스터디 카페 · 3명",      listOf("김", "정", "오"),       Gray50,   Gray900),
    ScheduleEvent(4, "19:30", "생일 파티",     "홍대 레스토랑 · 8명",         listOf("박", "이", "최", "윤"), Gray50,   Gray900),
)
private val tomorrowEvents = listOf(
    ScheduleEvent(5, "11:00", "주말 여행 계획", "화상회의 · 5명", emptyList(), Gray50, Gray900),
    ScheduleEvent(6, "18:00", "가족 저녁 모임", "서울 강남구 · 12명", emptyList(), Gray50, Gray900, chatId = "room5"),
)

@Composable
fun HomeScreen(
    onOpenChat: (String) -> Unit,
    onTab: (TpTab) -> Unit,
    activeTab: TpTab,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(White)
            .statusBarsPadding()
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 22.dp, end = 22.dp, top = 2.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column {
                Text("2026년 6월 22일", color = Gray400, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text("오늘", color = Gray900, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
            }
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Gray50)
                    .padding(top = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Notifications, contentDescription = null, tint = Gray600, modifier = Modifier.size(20.dp))
            }
        }

        // Week strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 18.dp),
        ) {
            weekDays.forEachIndexed { i, day ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val isToday = i == todayIdx
                    Text(
                        day,
                        fontSize = 11.sp,
                        color = if (isToday) Blue600 else Gray400,
                        fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(if (isToday) Blue600 else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            weekDates[i].toString(),
                            fontSize = 14.sp,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                            color = when {
                                isToday -> White
                                i < todayIdx -> Gray300
                                else -> Gray700
                            },
                        )
                    }
                    if (isToday) {
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.size(4.dp).clip(CircleShape).background(Blue600))
                    }
                }
            }
        }

        // Timeline
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 12.dp),
        ) {
            SectionLabel("오늘 일정")

            Row {
                // Timeline line
                Box(modifier = Modifier.width(40.dp)) {
                    Box(
                        modifier = Modifier
                            .width(1.5.dp)
                            .fillMaxHeight()
                            .background(Gray100)
                            .align(Alignment.Center)
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        todayEvents.forEachIndexed { i, ev ->
                            val h = if (i == 1) 116.dp else 80.dp
                            Box(modifier = Modifier.height(h), contentAlignment = Alignment.TopCenter) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 20.dp)
                                        .size(if (ev.active) 14.dp else 10.dp)
                                        .clip(CircleShape)
                                        .background(if (ev.active) Blue600 else White)
                                        .border(2.dp, if (ev.active) Blue600 else Gray300, CircleShape)
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
                    todayEvents.forEach { ev ->
                        val h = if (ev.active) 116.dp else 80.dp
                        Box(
                            modifier = Modifier
                                .height(h)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(ev.bg)
                                .border(
                                    if (ev.active) 0.dp else 1.dp,
                                    if (ev.active) Color.Transparent else Gray100,
                                    RoundedCornerShape(14.dp)
                                )
                                .then(if (ev.chatId != null) Modifier.clickable { onOpenChat(ev.chatId) } else Modifier)
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(ev.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ev.textColor)
                                    Text(ev.time, fontSize = 12.sp, color = if (ev.active) White.copy(0.8f) else Gray400)
                                }
                                Spacer(Modifier.height(3.dp))
                                Text(ev.sub, fontSize = 12.sp, color = if (ev.active) White.copy(0.75f) else Gray500)
                                if (ev.active) {
                                    Spacer(Modifier.height(10.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                                        ev.members.forEachIndexed { i, m ->
                                            Box(
                                                modifier = Modifier
                                                    .size(26.dp)
                                                    .clip(CircleShape)
                                                    .background(White.copy(0.25f))
                                                    .border(1.5.dp, White.copy(0.6f), CircleShape),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text(m, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = White)
                                            }
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Text("+${ev.members.size}명 참석", fontSize = 11.sp, color = White.copy(0.8f))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel("내일 일정")

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tomorrowEvents.forEach { ev ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Gray50)
                            .border(1.dp, Gray100, RoundedCornerShape(14.dp))
                            .then(if (ev.chatId != null) Modifier.clickable { onOpenChat(ev.chatId) } else Modifier)
                            .padding(13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(ev.time, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Blue600)
                        Spacer(Modifier.width(14.dp))
                        Box(Modifier.width(1.dp).height(32.dp).background(Gray200))
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(ev.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Gray900)
                            Text(ev.sub, fontSize = 12.sp, color = Gray400)
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
        text = text.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = Gray400,
        letterSpacing = 0.04.sp,
        modifier = Modifier.padding(bottom = 14.dp),
    )
}