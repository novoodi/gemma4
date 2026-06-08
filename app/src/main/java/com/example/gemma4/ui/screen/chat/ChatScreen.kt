package com.example.gemma4.ui.screen.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.gemma4.data.SampleData
import com.example.gemma4.data.model.Message
import com.example.gemma4.data.model.Participant
import com.example.gemma4.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    navController: NavController,
    viewModel: ChatViewModel = viewModel()
) {
    val room by viewModel.room.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val inputText by viewModel.inputText.collectAsStateWithLifecycle()
    val summaryState by viewModel.summaryState.collectAsStateWithLifecycle()
    val currentSender by viewModel.currentSender.collectAsStateWithLifecycle()
    val participants by viewModel.participants.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    LaunchedEffect(summaryState) {
        if (summaryState is SummaryState.Success) {
            navController.navigate(Screen.Summary.createRoute(viewModel.roomId))
            viewModel.resetSummaryState()
        }
    }

    if (summaryState is SummaryState.Error) {
        AlertDialog(
            onDismissRequest = viewModel::resetSummaryState,
            title = { Text("오류") },
            text = { Text((summaryState as SummaryState.Error).message) },
            confirmButton = {
                TextButton(onClick = viewModel::resetSummaryState) { Text("확인") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(room?.name ?: "") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                actions = {
                    var showSampleMenu by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { showSampleMenu = true }) {
                            Text("더미 로드")
                        }
                        DropdownMenu(
                            expanded = showSampleMenu,
                            onDismissRequest = { showSampleMenu = false }
                        ) {
                            SampleData.datasets.forEachIndexed { index, dataset ->
                                DropdownMenuItem(
                                    text = { Text(dataset.name) },
                                    onClick = {
                                        viewModel.loadSampleData(index)
                                        showSampleMenu = false
                                    }
                                )
                            }
                        }
                    }
                    TextButton(
                        onClick = viewModel::summarize,
                        enabled = messages.isNotEmpty() && summaryState !is SummaryState.Loading
                    ) {
                        Text("이야기 정리")
                    }
                }
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .navigationBarsPadding()
                    .imePadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SenderSelector(
                    currentSender = currentSender,
                    participants = participants,
                    onSenderSelected = viewModel::setSender
                )
                Spacer(modifier = Modifier.width(4.dp))
                OutlinedTextField(
                    value = inputText,
                    onValueChange = viewModel::onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("메시지 입력") },
                    maxLines = 3
                )
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(onClick = viewModel::sendMessage) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "전송")
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (messages.isEmpty()) {
                Text(
                    text = "모임에 대해 자유롭게 이야기해 보세요",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        MessageBubble(message = message)
                    }
                }
            }

            if (summaryState is SummaryState.Loading) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "AI가 대화를 분석하고 있어요...",
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SenderSelector(
    currentSender: Participant,
    participants: List<Participant>,
    onSenderSelected: (Participant) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        AssistChip(
            onClick = { expanded = !expanded },
            label = { Text(currentSender.name) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            participants.forEach { participant ->
                DropdownMenuItem(
                    text = { Text(participant.name) },
                    onClick = {
                        onSenderSelected(participant)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(message: Message) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isMe) Alignment.End else Alignment.Start
    ) {
        if (!message.isMe) {
            Text(
                text = message.senderName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 2.dp, start = 4.dp)
            )
        }
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = if (message.isMe) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(
                text = message.content,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                color = if (message.isMe) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
