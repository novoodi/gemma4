package com.example.gemma4.ui.screen.chat

import androidx.compose.animation.core.*
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.gemma4.data.SampleData
import com.example.gemma4.data.model.Message
import com.example.gemma4.navigation.Screen
import com.example.gemma4.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    navController: NavController,
    viewModel: ChatViewModel = viewModel(),
) {
    val room     by viewModel.room.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val inputText by viewModel.inputText.collectAsStateWithLifecycle()
    val summaryState by viewModel.summaryState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    // Error dialog
    if (summaryState is SummaryState.Error) {
        AlertDialog(
            onDismissRequest = viewModel::resetSummaryState,
            title = { Text("오류") },
            text = { Text((summaryState as SummaryState.Error).message) },
            confirmButton = { TextButton(onClick = viewModel::resetSummaryState) { Text("확인") } },
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(White)) {
        // Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(White)
                .padding(horizontal = 8.dp),
        ) {
            IconButton(
                onClick = { navController.popBackStack() },
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로", tint = Gray900)
            }
            Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(room?.name ?: "", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Gray900)
                Text("${room?.participants?.size ?: 0}명 참여 중", fontSize = 11.sp, color = Gray400)
            }
            // Sample data menu
            var showMenu by remember { mutableStateOf(false) }
            Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                TextButton(onClick = { showMenu = true }) { Text("메뉴", fontSize = 12.sp) }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    SampleData.datasets.forEachIndexed { i, ds ->
                        DropdownMenuItem(
                            text = { Text(ds.name) },
                            onClick = { viewModel.loadSampleData(i); showMenu = false },
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = Gray100)

        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .background(Color(0xFFF8F9FC))
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Surface(
                        shape = CircleShape,
                        color = White,
                        shadowElevation = 1.dp,
                    ) {
                        Text("오늘", fontSize = 11.sp, color = Gray500, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                    }
                }
            }

            items(messages, key = { it.id }) { msg ->
                MessageBubble(msg)
            }

            // System AI chip — show when messages exist
            if (messages.isNotEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        AssistChip(
                            onClick = {
                                viewModel.summarize()
                                navController.navigate(Screen.AILoading.createRoute(viewModel.roomId))
                            },
                            label = { Text("⚡ AI 요약 준비됨 — 추천 장소 3곳 발견", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Blue700) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = Blue50),
                            border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = Blue100),
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
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Gemini star button
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Blue600),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(
                    onClick = {
                        viewModel.summarize()
                        navController.navigate(Screen.AILoading.createRoute(viewModel.roomId))
                    },
                ) {
                    Icon(
                        imageVector = GeminiIcon,
                        contentDescription = "AI 요약",
                        tint = White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            OutlinedTextField(
                value = inputText,
                onValueChange = viewModel::onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("메시지 입력…", color = Gray400) },
                maxLines = 3,
                shape = RoundedCornerShape(22.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = Gray100,
                    focusedBorderColor = Blue300,
                    unfocusedContainerColor = Gray50,
                    focusedContainerColor = Gray50,
                ),
            )

            val hasInput = inputText.isNotBlank()
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (hasInput) Blue600 else Gray200),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = viewModel::sendMessage) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "전송",
                        tint = if (hasInput) White else Gray400,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: Message) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.isMe) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!msg.isMe) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Blue100),
                contentAlignment = Alignment.Center,
            ) {
                Text(msg.senderName.take(1), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Blue700)
            }
            Spacer(Modifier.width(8.dp))
        }

        Column(
            modifier = Modifier.widthIn(max = 260.dp),
            horizontalAlignment = if (msg.isMe) Alignment.End else Alignment.Start,
        ) {
            if (!msg.isMe) {
                Text(msg.senderName, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Gray500, modifier = Modifier.padding(bottom = 4.dp, start = 2.dp))
            }
            Surface(
                shape = RoundedCornerShape(
                    topStart = 18.dp, topEnd = 18.dp,
                    bottomStart = if (msg.isMe) 18.dp else 4.dp,
                    bottomEnd   = if (msg.isMe) 4.dp  else 18.dp,
                ),
                color = if (msg.isMe) Blue600 else White,
                shadowElevation = if (msg.isMe) 0.dp else 1.dp,
            ) {
                Text(
                    text = msg.content,
                    fontSize = 14.sp,
                    color = if (msg.isMe) White else Gray900,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(horizontal = 15.dp, vertical = 11.dp),
                )
            }
        }
    }
}

private val GeminiIcon: ImageVector = ImageVector.Builder(
    name = "Gemini",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).path(fill = SolidColor(Color.Black)) {
    moveTo(12f, 2f)
    curveTo(11.5f, 7.8f, 7.8f, 11.5f, 2f, 12f)
    curveTo(7.8f, 12.5f, 11.5f, 16.2f, 12f, 22f)
    curveTo(12.5f, 16.2f, 16.2f, 12.5f, 22f, 12f)
    curveTo(16.2f, 11.5f, 12.5f, 7.8f, 12f, 2f)
    close()
}.build()