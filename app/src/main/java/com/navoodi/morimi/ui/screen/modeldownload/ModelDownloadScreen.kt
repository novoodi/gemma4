package com.navoodi.morimi.ui.screen.modeldownload

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.navoodi.morimi.navigation.Screen
import com.navoodi.morimi.ui.theme.*

@Composable
fun ModelDownloadScreen(
    navController: NavController,
    viewModel: ModelDownloadViewModel = viewModel(),
) {
    val state by viewModel.downloadState.collectAsStateWithLifecycle()
    var showMobileDataDialog by remember { mutableStateOf(false) }

    // 다운로드 완료 시 파이프라인 재초기화 후 Home으로 이동
    LaunchedEffect(state) {
        if (state is ModelDownloadUiState.Complete) {
            viewModel.reinitializePipelines()
            navController.navigate(Screen.Home.route) {
                popUpTo(Screen.ModelDownload.route) { inclusive = true }
            }
        }
    }

    // POST_NOTIFICATIONS 요청 후 (결과 무관) 다운로드 시작
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.startDownload() }

    fun triggerDownload() = notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8F9FC))
            .statusBarsPadding()
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))

        Text("🤖", fontSize = 56.sp)
        Spacer(Modifier.height(16.dp))
        Text("AI 모델 준비", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MoColors.textPrimary)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "온디바이스 AI 기능을 사용하려면\nGemma·임베딩 모델 (약 2.8 GB)이 필요합니다.",
            fontSize = 14.sp,
            color = MoColors.textSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
        )

        Spacer(Modifier.height(32.dp))

        // Wi-Fi 권장 배너 (대기/오류 상태에서만)
        if (state is ModelDownloadUiState.Idle || state is ModelDownloadUiState.Error) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .fillMaxWidth()
                    .background(Color(0xFFFFF8E1), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("📶", fontSize = 16.sp)
                Text(
                    "Wi-Fi 연결 상태에서 다운로드를 권장합니다",
                    fontSize = 13.sp,
                    color = Color(0xFF795548),
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        // 진행률 바
        if (state is ModelDownloadUiState.Downloading) {
            val s = state as ModelDownloadUiState.Downloading
            Column(
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LinearProgressIndicator(
                    progress = { s.progress },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = MoColors.brand,
                    trackColor = MoColors.border,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${(s.progress * 100).toInt()}%  ·  ${formatSize(s.downloadedBytes)} / ${formatSize(s.totalBytes)}",
                    fontSize = 13.sp,
                    color = MoColors.textSecondary,
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        // 오류 메시지
        if (state is ModelDownloadUiState.Error) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = (state as ModelDownloadUiState.Error).message,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }

        Spacer(Modifier.weight(1f))

        // 버튼 영역
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = {
                    when (state) {
                        is ModelDownloadUiState.Downloading -> viewModel.cancelDownload()
                        else -> {
                            if (!viewModel.isWifiConnected()) showMobileDataDialog = true
                            else triggerDownload()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = when (state) {
                        is ModelDownloadUiState.Downloading -> MaterialTheme.colorScheme.error
                        else -> MoColors.brand
                    },
                ),
            ) {
                Text(
                    text = when (state) {
                        is ModelDownloadUiState.Downloading -> "다운로드 취소"
                        is ModelDownloadUiState.Error -> "다시 시도"
                        else -> "AI 모델 다운로드 (약 2.8 GB)"
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (state !is ModelDownloadUiState.Downloading) {
                TextButton(
                    onClick = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.ModelDownload.route) { inclusive = true }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("나중에 (샘플 AI로 계속)", fontSize = 14.sp, color = MoColors.textTertiary)
                }
            }
        }
    }

    // 모바일 데이터 경고 다이얼로그
    if (showMobileDataDialog) {
        AlertDialog(
            onDismissRequest = { showMobileDataDialog = false },
            title = { Text("모바일 데이터 사용") },
            text = {
                Text(
                    "Wi-Fi에 연결되어 있지 않습니다.\n" +
                    "모바일 데이터로 약 2.8 GB를 다운로드합니다.\n" +
                    "계속하시겠습니까?"
                )
            },
            confirmButton = {
                Button(onClick = {
                    showMobileDataDialog = false
                    triggerDownload()
                }) { Text("다운로드") }
            },
            dismissButton = {
                TextButton(onClick = { showMobileDataDialog = false }) { Text("취소") }
            },
        )
    }
}

private fun formatSize(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 0.1) "%.1f GB".format(gb) else "${bytes / (1024 * 1024)} MB"
}
