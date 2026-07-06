package com.navoodi.morimi.ui.screen.chatlist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.navoodi.morimi.data.model.ChatRoom
import com.navoodi.morimi.ui.components.TalkPlusTabBar
import com.navoodi.morimi.ui.components.TpTab
import com.navoodi.morimi.ui.screen.home.HomeViewModel
import com.navoodi.morimi.ui.theme.*

// 아바타 배경/전경 — 도메인 노드 팔레트
private val avatarColors = listOf(
    Pair(MoColors.participantBg, MoColors.participant),
    Pair(MoColors.activityBg,    MoColors.activity),
    Pair(MoColors.placeBg,       MoColors.place),
    Pair(MoColors.itemBg,        MoColors.item),
    Pair(MoColors.warnBg,        MoColors.warn),
)

@Composable
fun ChatListScreen(
    onOpenChat: (String) -> Unit,
    onNewRoom: () -> Unit,
    onJoinRoom: () -> Unit,
    onTab: (TpTab) -> Unit,
    activeTab: TpTab,
    viewModel: HomeViewModel = viewModel(),
) {
    val rooms by viewModel.rooms.collectAsStateWithLifecycle()
    val roomReads by viewModel.roomReads.collectAsStateWithLifecycle()
    val showDialog by viewModel.showCreateDialog.collectAsStateWithLifecycle()
    var roomNameInput by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize().background(MoColors.surfaceBase).statusBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = MoSpacing.screenFocused, end = MoSpacing.screenFocused, top = 4.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("채팅방", style = MaterialTheme.typography.headlineLarge, color = MoColors.textPrimary)
                Box(
                    modifier = Modifier.size(38.dp).clip(CircleShape).background(MoColors.surfaceSubtle),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Search, contentDescription = "검색", tint = MoColors.textSecondary, modifier = Modifier.size(20.dp))
                }
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(rooms, key = { it.id }) { room ->
                    val hasUnread = room.lastMessageTime > (roomReads[room.id] ?: 0L)
                    RoomRow(
                        room = room,
                        colorIdx = rooms.indexOf(room),
                        hasUnread = hasUnread,
                        onClick = { onOpenChat(room.id) },
                    )
                }
                if (rooms.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                            Text("모임방을 만들어보세요", color = MoColors.textTertiary, fontFamily = Pretendard, fontSize = 14.sp)
                        }
                    }
                }
            }

            TalkPlusTabBar(active = activeTab, onTab = onTab)
        }

        // FAB group
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 22.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.End,
        ) {
            // 코드로 입장 FAB
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MoColors.surfaceSubtle)
                    .clickable { onJoinRoom() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Login, contentDescription = "코드로 입장", tint = MoColors.textSecondary, modifier = Modifier.size(20.dp))
            }
            // 새 방 만들기 FAB
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MoColors.brand)
                    .clickable { viewModel.showCreateDialog() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Edit, contentDescription = "새 모임방", tint = MoColors.textOnBrand, modifier = Modifier.size(22.dp))
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.hideCreateDialog(); roomNameInput = "" },
            title = { Text("새 모임방") },
            text = {
                OutlinedTextField(
                    value = roomNameInput,
                    onValueChange = { roomNameInput = it },
                    label = { Text("모임 이름") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.createRoom(roomNameInput)
                    roomNameInput = ""
                }) { Text("만들기") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.hideCreateDialog(); roomNameInput = "" }) { Text("취소") }
            },
        )
    }
}

@Composable
private fun RoomRow(room: ChatRoom, colorIdx: Int, hasUnread: Boolean, onClick: () -> Unit) {
    val (bg, fg) = avatarColors[colorIdx % avatarColors.size]
    val initials = room.name.take(2)
    val preview = room.lastMessage.ifEmpty { "새 채팅방" }
    val timeLabel = if (room.lastMessageTime > 0L) formatRoomTime(room.lastMessageTime) else ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 아바타 + 안 읽음 점 오버레이
        Box(modifier = Modifier.size(52.dp)) {
            Box(
                modifier = Modifier.size(52.dp).clip(CircleShape).background(bg),
                contentAlignment = Alignment.Center,
            ) {
                Text(initials, fontFamily = Pretendard, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = fg)
            }
            if (hasUnread) {
                Box(
                    modifier = Modifier
                        .size(13.dp)
                        .align(Alignment.TopEnd)
                        .border(2.dp, MoColors.surfaceBase, CircleShape)
                        .clip(CircleShape)
                        .background(MoColors.brand),
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    room.name,
                    fontFamily = Pretendard,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MoColors.textPrimary,
                    modifier = Modifier.weight(1f),
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                )
                if (timeLabel.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(timeLabel, fontFamily = Pretendard, fontSize = 12.sp, color = MoColors.textTertiary)
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                preview,
                fontFamily = Pretendard,
                fontSize = 13.sp,
                color = if (room.lastMessage.isEmpty()) MoColors.textDisabled else MoColors.textTertiary,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
            )
        }
    }

    Box(Modifier.fillMaxWidth().height(1.dp).padding(horizontal = 22.dp).background(MoColors.border))
}

private fun formatRoomTime(timestamp: Long): String {
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply { timeInMillis = timestamp }

    val sameYear = now.get(Calendar.YEAR) == target.get(Calendar.YEAR)
    val sameDay = sameYear &&
        now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
    val yesterday = sameYear &&
        now.get(Calendar.DAY_OF_YEAR) - target.get(Calendar.DAY_OF_YEAR) == 1

    return when {
        sameDay -> {
            val hour = target.get(Calendar.HOUR_OF_DAY)
            val minute = target.get(Calendar.MINUTE)
            val ampm = if (hour < 12) "오전" else "오후"
            val h = if (hour % 12 == 0) 12 else hour % 12
            "$ampm $h:${minute.toString().padStart(2, '0')}"
        }
        yesterday -> "어제"
        sameYear -> "${target.get(Calendar.MONTH) + 1}/${target.get(Calendar.DAY_OF_MONTH)}"
        else -> "${target.get(Calendar.YEAR)}/${target.get(Calendar.MONTH) + 1}/${target.get(Calendar.DAY_OF_MONTH)}"
    }
}