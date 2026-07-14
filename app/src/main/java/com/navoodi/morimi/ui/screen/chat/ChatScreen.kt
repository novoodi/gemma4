package com.navoodi.morimi.ui.screen.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import com.navoodi.morimi.data.repository.ChatRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import android.widget.Toast
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.navoodi.morimi.data.model.Message
import com.navoodi.morimi.navigation.Screen
import com.navoodi.morimi.ui.components.GeminiIcon
import com.navoodi.morimi.ui.components.MoStatus
import com.navoodi.morimi.ui.components.MoStatusBadge
import com.navoodi.morimi.ui.theme.*
import java.util.Calendar

// ── List item types ─────────────────────────────────────────────────────────

private sealed class ChatListItem {
    data class DateSeparator(val text: String) : ChatListItem()
    data class MessageRow(
        val message: Message,
        val showTime: Boolean,
        val showSenderName: Boolean,
    ) : ChatListItem()
}

// ── Build display list ───────────────────────────────────────────────────────

private fun buildChatItems(messages: List<Message>, currentUid: String?): List<ChatListItem> {
    val result = mutableListOf<ChatListItem>()
    messages.forEachIndexed { i, msg ->
        val prev = messages.getOrNull(i - 1)
        val next = messages.getOrNull(i + 1)

        // Insert date separator at day boundary
        if (prev == null || !sameDay(prev.timestamp, msg.timestamp)) {
            result.add(ChatListItem.DateSeparator(formatDate(msg.timestamp)))
        }

        // Show time label on last message of each same-sender, same-minute group
        val showTime = next == null
            || next.senderId != msg.senderId
            || !sameMinute(msg.timestamp, next.timestamp)
            || !sameDay(msg.timestamp, next.timestamp)

        // Show sender name only on the first message of a group (others' messages only)
        val showSenderName = msg.senderId != currentUid && (
            prev == null || prev.senderId != msg.senderId || !sameDay(prev.timestamp, msg.timestamp)
        )

        result.add(ChatListItem.MessageRow(msg, showTime, showSenderName))
    }
    return result
}

// ── Main screen ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    navController: NavController,
    viewModel: ChatViewModel = viewModel(),
) {
    val room         by viewModel.room.collectAsStateWithLifecycle()
    val messages     by viewModel.messages.collectAsStateWithLifecycle()
    val currentUid    = viewModel.currentUid
    val inputText    by viewModel.inputText.collectAsStateWithLifecycle()
    val summaryState by viewModel.summaryState.collectAsStateWithLifecycle()
    val leaveState   by viewModel.leaveState.collectAsStateWithLifecycle()
    val isCompressing by viewModel.isCompressing.collectAsStateWithLifecycle()
    val showFeedbackPrompt by viewModel.showFeedbackPrompt.collectAsStateWithLifecycle()
    val hasSavedSummary by viewModel.hasSavedSummary.collectAsStateWithLifecycle()
    val listState     = rememberLazyListState()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    // 화면에 남아 있는 사용자: 분석 완료 시 자동으로 AIReport 이동.
    // resetSummaryState()로 Idle 복귀해 재진입 시 재navigate 방지.
    LaunchedEffect(summaryState) {
        if (summaryState is SummaryState.Success) {
            navController.navigate(Screen.AIReport.createRoute(viewModel.roomId)) {
                launchSingleTop = true
            }
            viewModel.resetSummaryState()
        }
    }

    val chatItems = remember(messages, currentUid) { buildChatItems(messages, currentUid) }

    var showCodeDialog  by remember { mutableStateOf(false) }
    var codeCopied      by remember { mutableStateOf(false) }
    var showLeaveDialog by remember { mutableStateOf(false) }

    // Auto-scroll to bottom + 읽음 처리 (진입 시 및 새 메시지 도착 시)
    LaunchedEffect(chatItems.size) {
        if (chatItems.isNotEmpty()) {
            listState.animateScrollToItem(chatItems.size)
            viewModel.markAsRead()
        }
    }

    // 화면 이탈 시 최종 읽음 시각 갱신 (NonCancellable: 코루틴 취소 후에도 실행 보장)
    LaunchedEffect(Unit) {
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                ChatRepository.updateLastReadTime(viewModel.roomId)
            }
        }
    }

    // Navigate back to ChatList after successful leave
    LaunchedEffect(leaveState) {
        if (leaveState is LeaveState.Done) {
            navController.popBackStack()
        }
    }

    // ── Dialogs ──────────────────────────────────────────────────────────────

    if (summaryState is SummaryState.Error) {
        AlertDialog(
            onDismissRequest = viewModel::resetSummaryState,
            title = { Text("오류") },
            text = { Text((summaryState as SummaryState.Error).message) },
            confirmButton = { TextButton(onClick = viewModel::resetSummaryState) { Text("확인") } },
        )
    }

    if (showFeedbackPrompt) {
        FeedbackPromptDialog(
            onDismiss = viewModel::dismissFeedbackPrompt,
            onSubmit = viewModel::submitFeedback,
        )
    }

    if (leaveState is LeaveState.Error) {
        AlertDialog(
            onDismissRequest = viewModel::resetLeaveError,
            title = { Text("오류") },
            text = { Text((leaveState as LeaveState.Error).message) },
            confirmButton = { TextButton(onClick = viewModel::resetLeaveError) { Text("확인") } },
        )
    }

    if (showCodeDialog) {
        val code = room?.inviteCode ?: ""
        AlertDialog(
            onDismissRequest = { showCodeDialog = false },
            title = { Text("초대 코드", fontWeight = FontWeight.Bold) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("이 코드를 공유해 친구를 초대하세요", fontFamily = Pretendard, fontSize = 13.sp, color = MoColors.textTertiary)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        code,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        color = MoColors.brand,
                        letterSpacing = 6.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboardManager.setText(AnnotatedString(code))
                    codeCopied = true
                }) {
                    Text(if (codeCopied) "복사됨 ✓" else "복사", color = if (codeCopied) MoColors.textTertiary else MoColors.brand)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCodeDialog = false }) { Text("닫기") }
            },
        )
    }

    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            title = { Text("방 나가기", fontWeight = FontWeight.Bold) },
            text = { Text("이 채팅방에서 나가시겠습니까?\n마지막 멤버라면 방이 삭제됩니다.") },
            confirmButton = {
                Button(
                    onClick = {
                        showLeaveDialog = false
                        viewModel.leaveRoom()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MoColors.warningText),
                ) { Text("나가기") }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveDialog = false }) { Text("취소") }
            },
        )
    }

    // ── Layout ───────────────────────────────────────────────────────────────

    Column(modifier = Modifier.fillMaxSize().background(MoColors.surfaceBase).statusBarsPadding().imePadding()) {
        // Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(MoColors.surfaceBase)
                .padding(horizontal = 8.dp),
        ) {
            IconButton(
                onClick = { navController.popBackStack() },
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로", tint = MoColors.textPrimary)
            }
            Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(room?.name ?: "", fontFamily = Pretendard, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MoColors.textPrimary)
                Text("${room?.participantUids?.size ?: 0}명 참여 중", fontFamily = Pretendard, fontSize = 11.sp, color = MoColors.textTertiary)
            }
            var showMenu by remember { mutableStateOf(false) }
            Row(modifier = Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                if (room?.inviteCode?.isNotEmpty() == true) {
                    IconButton(onClick = { showCodeDialog = true; codeCopied = false }) {
                        Icon(Icons.Outlined.Info, contentDescription = "초대 코드", tint = MoColors.textTertiary, modifier = Modifier.size(20.dp))
                    }
                }
                Box {
                    TextButton(onClick = { showMenu = true }) { Text("메뉴", fontFamily = Pretendard, fontSize = 12.sp) }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("방 나가기", color = MoColors.warningText) },
                            onClick = { showMenu = false; showLeaveDialog = true },
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = MoColors.border)

        // 백그라운드 성향 압축 중 — "스피너 없는" 조용한 플로팅 상태배지
        if (isCompressing) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MoColors.surfaceSubtle)
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                MoStatusBadge(status = MoStatus.Syncing, text = "성향을 정리하고 있어요")
            }
        }

        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .background(MoColors.surfaceSubtle)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(
                count = chatItems.size,
                key = { i ->
                    when (val item = chatItems[i]) {
                        is ChatListItem.DateSeparator -> "sep_${item.text}"
                        is ChatListItem.MessageRow   -> item.message.id
                    }
                },
            ) { i ->
                when (val item = chatItems[i]) {
                    is ChatListItem.DateSeparator -> DateSeparatorItem(item.text)
                    is ChatListItem.MessageRow   -> MessageBubble(
                        msg = item.message,
                        currentUid = currentUid,
                        showTime = item.showTime,
                        showSenderName = item.showSenderName,
                    )
                }
            }

            if (messages.isNotEmpty()) {
                item(key = "ai_chip") {
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                        AssistChip(
                            // 옵션 (a): 로딩 화면 없이 백그라운드 진행 + 완료 시 알림.
                            // 화면에 남아 있으면 LaunchedEffect(summaryState)로 자동 이동.
                            onClick = {
                                viewModel.summarize()
                                Toast.makeText(context, "분석을 시작했어요. 끝나면 알림으로 알려드릴게요", Toast.LENGTH_LONG).show()
                            },
                            enabled = summaryState !is SummaryState.Loading,
                            label = { Text("✨ AI로 이야기 정리하기", fontFamily = Pretendard, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MoColors.brand) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = MoColors.brandSubtle),
                            border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = MoBlue100),
                            shape = CircleShape,
                        )
                    }
                }
            }

            // 지난 추천 재확인 진입점 — 영속 복원된 결과 포함(앱 재시작 후에도 유지)
            if (hasSavedSummary) {
                item(key = "last_report_chip") {
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
                        AssistChip(
                            onClick = {
                                navController.navigate(Screen.AIReport.createRoute(viewModel.roomId)) {
                                    launchSingleTop = true
                                }
                            },
                            label = { Text("📋 지난 추천 보기", fontFamily = Pretendard, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MoColors.textSecondary) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = MoColors.surfaceCard),
                            border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = MoColors.border),
                            shape = CircleShape,
                        )
                    }
                }
            }
        }

        // Input bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(White)
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape).background(MoColors.brand),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(
                    onClick = {
                        viewModel.summarize()
                        Toast.makeText(context, "분석을 시작했어요. 끝나면 알림으로 알려드릴게요", Toast.LENGTH_LONG).show()
                    },
                    enabled = summaryState !is SummaryState.Loading,
                ) {
                    Icon(imageVector = GeminiIcon, contentDescription = "AI 요약", tint = MoColors.textOnBrand, modifier = Modifier.size(18.dp))
                }
            }

            OutlinedTextField(
                value = inputText,
                onValueChange = viewModel::onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("메시지 입력…", fontFamily = Pretendard, color = MoColors.textTertiary) },
                textStyle = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                shape = RoundedCornerShape(22.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MoColors.border,
                    focusedBorderColor = MoColors.brand,
                    unfocusedContainerColor = MoColors.surfaceSubtle,
                    focusedContainerColor = MoColors.surfaceSubtle,
                    cursorColor = MoColors.brand,
                ),
            )

            val hasInput = inputText.isNotBlank()
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (hasInput) MoColors.brand else MoColors.borderStrong),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = viewModel::sendMessage) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "전송",
                        tint = if (hasInput) MoColors.textOnBrand else MoColors.textTertiary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

// ── Date separator ───────────────────────────────────────────────────────────

@Composable
private fun DateSeparatorItem(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MoColors.surfaceCard,
            shadowElevation = 1.dp,
        ) {
            Text(
                text,
                fontFamily = Pretendard,
                fontSize = 11.sp,
                color = MoColors.textSecondary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }
    }
}

// ── Message bubble ───────────────────────────────────────────────────────────

@Composable
private fun MessageBubble(
    msg: Message,
    currentUid: String?,
    showTime: Boolean,
    showSenderName: Boolean,
) {
    val isMe = msg.senderId == currentUid
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!isMe) {
            // Avatar: shown for first message in group, spacer otherwise (keeps bubble aligned)
            if (showSenderName) {
                Box(
                    modifier = Modifier.size(30.dp).clip(CircleShape).background(MoColors.participantBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(msg.senderName.take(1), fontFamily = Pretendard, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MoColors.participant)
                }
            } else {
                Spacer(Modifier.size(30.dp))
            }
            Spacer(Modifier.width(8.dp))
        }

        // Time label: left of my bubble, right of others'
        if (isMe && showTime) {
            Text(formatTime(msg.timestamp), fontFamily = Pretendard, fontSize = 10.sp, color = MoColors.textTertiary, modifier = Modifier.align(Alignment.Bottom))
            Spacer(Modifier.width(4.dp))
        }

        Column(
            modifier = Modifier.widthIn(max = 260.dp),
            horizontalAlignment = if (isMe) Alignment.End else Alignment.Start,
        ) {
            if (!isMe && showSenderName) {
                Text(
                    msg.senderName,
                    fontFamily = Pretendard,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MoColors.textSecondary,
                    modifier = Modifier.padding(bottom = 4.dp, start = 2.dp),
                )
            }
            Surface(
                shape = RoundedCornerShape(
                    topStart = 18.dp, topEnd = 18.dp,
                    bottomStart = if (isMe) 18.dp else 4.dp,
                    bottomEnd   = if (isMe) 4.dp  else 18.dp,
                ),
                color = if (isMe) MoColors.brand else MoColors.surfaceCard,
                shadowElevation = if (isMe) 0.dp else 1.dp,
            ) {
                Text(
                    text = msg.content,
                    fontFamily = Pretendard,
                    fontSize = 14.sp,
                    color = if (isMe) MoColors.textOnBrand else MoColors.textPrimary,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(horizontal = 15.dp, vertical = 11.dp),
                )
            }
        }

        if (!isMe && showTime) {
            Spacer(Modifier.width(4.dp))
            Text(formatTime(msg.timestamp), fontFamily = Pretendard, fontSize = 10.sp, color = MoColors.textTertiary, modifier = Modifier.align(Alignment.Bottom))
        }
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

private fun formatTime(timestamp: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
    val hour = cal.get(Calendar.HOUR_OF_DAY)
    val minute = cal.get(Calendar.MINUTE)
    val ampm = if (hour < 12) "오전" else "오후"
    val h = if (hour % 12 == 0) 12 else hour % 12
    return "$ampm $h:${minute.toString().padStart(2, '0')}"
}

private fun formatDate(timestamp: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
    return "${cal.get(Calendar.YEAR)}년 ${cal.get(Calendar.MONTH) + 1}월 ${cal.get(Calendar.DAY_OF_MONTH)}일"
}

private fun sameDay(t1: Long, t2: Long): Boolean {
    val c1 = Calendar.getInstance().apply { timeInMillis = t1 }
    val c2 = Calendar.getInstance().apply { timeInMillis = t2 }
    return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
        c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)
}

private fun sameMinute(t1: Long, t2: Long) = t1 / 60_000 == t2 / 60_000

// ── 후기 팝업 ─────────────────────────────────────────────────────────────────
// 추천받은 방 재진입 시 노출. 저장 시 온디바이스 임베딩 인덱싱 → 다음 추천 RAG에 반영.
@Composable
private fun FeedbackPromptDialog(
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("지난 모임은 어떠셨어요?") },
        text = {
            Column {
                Text(
                    text = "후기를 남기면 다음 모임 추천이 더 정확해져요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("예: 조용하고 분위기 좋았어 / 주차가 너무 불편했어") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSubmit(text) }, enabled = text.isNotBlank()) { Text("저장") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("다음에") }
        },
    )
}
