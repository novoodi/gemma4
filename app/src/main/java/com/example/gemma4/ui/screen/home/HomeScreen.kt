package com.example.gemma4.ui.screen.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.gemma4.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = viewModel()
) {
    val rooms by viewModel.rooms.collectAsStateWithLifecycle()
    val showDialog by viewModel.showCreateDialog.collectAsStateWithLifecycle()
    var roomNameInput by remember { mutableStateOf("") }
    var participantsInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("모임 AI 비서") },
                actions = {
                    IconButton(onClick = { navController.navigate(Screen.Calendar.route) }) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = "캘린더")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::showCreateDialog) {
                Icon(Icons.Default.Add, contentDescription = "새 모임방")
            }
        }
    ) { padding ->
        if (rooms.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "모임방을 만들어보세요\n오른쪽 아래 + 버튼을 눌러주세요",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(rooms, key = { it.id }) { room ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { navController.navigate(Screen.Chat.createRoute(room.id)) }
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = room.name,
                                style = MaterialTheme.typography.titleMedium
                            )
                            if (room.participants.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = room.participants.joinToString(", "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = {
                viewModel.hideCreateDialog()
                roomNameInput = ""
                participantsInput = ""
            },
            title = { Text("새 모임방") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = roomNameInput,
                        onValueChange = { roomNameInput = it },
                        label = { Text("모임 이름") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = participantsInput,
                        onValueChange = { participantsInput = it },
                        label = { Text("참여자 이름") },
                        placeholder = { Text("나, 철수, 영희") },
                        supportingText = { Text("쉼표로 구분, 첫 번째가 내 이름") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val participants = participantsInput
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                    viewModel.createRoom(roomNameInput, participants)
                    roomNameInput = ""
                    participantsInput = ""
                }) { Text("만들기") }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.hideCreateDialog()
                    roomNameInput = ""
                    participantsInput = ""
                }) { Text("취소") }
            }
        )
    }
}
