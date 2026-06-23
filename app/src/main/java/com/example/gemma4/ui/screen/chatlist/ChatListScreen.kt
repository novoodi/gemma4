package com.example.gemma4.ui.screen.chatlist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.gemma4.data.model.ChatRoom
import com.example.gemma4.ui.components.TalkPlusTabBar
import com.example.gemma4.ui.components.TpTab
import com.example.gemma4.ui.screen.home.HomeViewModel
import com.example.gemma4.ui.theme.*

private val avatarColors = listOf(
    Pair(Blue100,    Blue700),
    Pair(Purple50,   Purple600),
    Pair(Success50,  Success600),
    Pair(Color(0xFFFEF0C7), Color(0xFFB54708)),
    Pair(Color(0xFFFEE4E2), Error600),
)

@Composable
fun ChatListScreen(
    onOpenChat: (String) -> Unit,
    onNewRoom: () -> Unit,
    onTab: (TpTab) -> Unit,
    activeTab: TpTab,
    viewModel: HomeViewModel = viewModel(),
) {
    val rooms by viewModel.rooms.collectAsStateWithLifecycle()
    val showDialog by viewModel.showCreateDialog.collectAsStateWithLifecycle()
    var roomNameInput by remember { mutableStateOf("") }
    var participantsInput by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize().background(White)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("채팅방", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Gray900, letterSpacing = (-0.5).sp)
                Box(
                    modifier = Modifier.size(38.dp).clip(CircleShape).background(Gray50),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Search, contentDescription = null, tint = Gray600, modifier = Modifier.size(20.dp))
                }
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(rooms, key = { it.id }) { room ->
                    RoomRow(room = room, colorIdx = rooms.indexOf(room), onClick = { onOpenChat(room.id) })
                }
                if (rooms.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                            Text("모임방을 만들어보세요", color = Gray400, fontSize = 14.sp)
                        }
                    }
                }
            }

            TalkPlusTabBar(active = activeTab, onTab = onTab)
        }

        // FAB
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 22.dp, bottom = 96.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Blue600)
                    .clickable { viewModel.showCreateDialog() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Edit, contentDescription = "새 모임방", tint = White, modifier = Modifier.size(22.dp))
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.hideCreateDialog(); roomNameInput = ""; participantsInput = "" },
            title = { Text("새 모임방") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = roomNameInput, onValueChange = { roomNameInput = it }, label = { Text("모임 이름") }, singleLine = true)
                    OutlinedTextField(value = participantsInput, onValueChange = { participantsInput = it }, label = { Text("참여자 이름") }, placeholder = { Text("나, 철수, 영희") }, supportingText = { Text("쉼표로 구분, 첫 번째가 내 이름") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val p = participantsInput.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    viewModel.createRoom(roomNameInput, p)
                    roomNameInput = ""; participantsInput = ""
                }) { Text("만들기") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.hideCreateDialog(); roomNameInput = ""; participantsInput = "" }) { Text("취소") }
            },
        )
    }
}

@Composable
private fun RoomRow(room: ChatRoom, colorIdx: Int, onClick: () -> Unit) {
    val (bg, fg) = avatarColors[colorIdx % avatarColors.size]
    val initials = room.name.take(2)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(modifier = Modifier.size(52.dp)) {
            Box(
                modifier = Modifier.size(52.dp).clip(CircleShape).background(bg),
                contentAlignment = Alignment.Center,
            ) {
                Text(initials, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = fg)
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(room.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Gray900)
                Text("방금", fontSize = 12.sp, color = Gray400)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${room.participants.size}명 참여 중",
                fontSize = 13.sp, color = Gray400,
                overflow = TextOverflow.Ellipsis, maxLines = 1,
            )
        }
    }

    Box(Modifier.fillMaxWidth().height(1.dp).padding(horizontal = 22.dp).background(Gray50))
}