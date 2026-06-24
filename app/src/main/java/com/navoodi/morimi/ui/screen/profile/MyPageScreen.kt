package com.navoodi.morimi.ui.screen.profile

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navoodi.morimi.MoimApp
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.exceptions.ClearCredentialException
import com.navoodi.morimi.ui.components.TalkPlusTabBar
import com.navoodi.morimi.ui.components.TpTab
import com.navoodi.morimi.ui.theme.*
import kotlinx.coroutines.launch

private const val TAG = "MyPageScreen"

@Composable
fun MyPageScreen(
    onEdit: () -> Unit,
    onTab: (TpTab) -> Unit,
    activeTab: TpTab,
    onLogout: () -> Unit,
    onModelDownload: () -> Unit = {},
) {
    var pushOn by remember { mutableStateOf(true) }
    var marketingOn by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isModelAvailable = remember {
        (context.applicationContext as MoimApp).llmService.isModelAvailable
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("로그아웃", fontWeight = FontWeight.Bold) },
            text = { Text("로그아웃 하시겠어요?") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    scope.launch {
                        // Credential Manager 캐시 제거 → 다음 로그인 시 계정 선택 창 재표시
                        try {
                            val credentialManager = CredentialManager.create(context)
                            credentialManager.clearCredentialState(ClearCredentialStateRequest())
                            Log.d(TAG, "clearCredentialState 완료")
                        } catch (e: ClearCredentialException) {
                            Log.e(TAG, "clearCredentialState 실패: ${e.message}")
                        }
                        onLogout()
                    }
                }) {
                    Text("로그아웃", color = Error600, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("취소", color = Gray500)
                }
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(Gray50).statusBarsPadding()) {
        // Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(White)
                .padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 10.dp),
        ) {
            Text("마이페이지", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Gray900, letterSpacing = (-0.5).sp)
        }
        HorizontalDivider(color = Gray100)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            // Profile card
            Box(
                modifier = Modifier
                    .padding(16.dp, 12.dp, 16.dp, 0.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(White)
                    .clickable(onClick = onEdit),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Blue600),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("T+", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = White)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("김민준", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Gray900)
                        Text("프로필 보기 · 설정", fontSize = 12.sp, color = Gray400)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = Gray300)
                }
            }

            SectionLabel("설정")
            SettingsGroup {
                SettingsRowToggle("푸시 알림", pushOn) { pushOn = it }
                SettingsDivider()
                SettingsRowToggle("마케팅 정보 수신", marketingOn) { marketingOn = it }
                SettingsDivider()
                SettingsRowChevron("언어", value = "한국어")
                SettingsDivider()
                SettingsRowChevron("환경설정")
            }

            SectionLabel("AI 모델")
            SettingsGroup {
                if (isModelAvailable) {
                    SettingsRowChevron(
                        label = "온디바이스 AI",
                        value = "설치됨 ✓",
                        showChevron = false,
                    )
                } else {
                    // 모델 미설치 상태: 강조 배너 + 다운로드 버튼
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onModelDownload)
                            .padding(horizontal = 20.dp, vertical = 15.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text("AI 모델 다운로드", fontSize = 15.sp, color = Blue600, fontWeight = FontWeight.Medium)
                            Text("약 2.6 GB · 샘플 AI 사용 중", fontSize = 12.sp, color = Gray400)
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Blue600)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text("다운로드", fontSize = 12.sp, color = White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            SectionLabel("정보")
            SettingsGroup {
                SettingsRowChevron("이용약관")
                SettingsDivider()
                SettingsRowChevron("개인정보처리방침")
                SettingsDivider()
                SettingsRowChevron("오픈소스 라이선스")
                SettingsDivider()
                SettingsRowChevron("앱 버전", value = "1.0.0")
            }

            Spacer(Modifier.height(24.dp))
            SettingsGroup(margin = false) {
                SettingsRowChevron(
                    label = "로그아웃",
                    danger = true,
                    showChevron = false,
                    onClick = { showLogoutDialog = true },
                )
            }
        }

        TalkPlusTabBar(active = activeTab, onTab = onTab)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Gray400,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp).padding(bottom = 0.dp))
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SettingsGroup(margin: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .then(if (margin) Modifier.padding(horizontal = 0.dp) else Modifier)
            .background(White),
        content = content,
    )
}

@Composable
private fun SettingsRowChevron(
    label: String,
    value: String? = null,
    danger: Boolean = false,
    showChevron: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.sp, color = if (danger) Error600 else Gray900)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (value != null) Text(value, fontSize = 14.sp, color = Gray400)
            if (showChevron) Icon(Icons.Default.ChevronRight, null, tint = Gray300, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SettingsRowToggle(label: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.sp, color = Gray900)
        TpToggle(on = on, onToggle = { onToggle(!on) })
    }
}

@Composable
fun TpToggle(on: Boolean, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .width(48.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(99.dp))
            .background(if (on) Blue600 else Gray300)
            .clickable(onClick = onToggle),
    ) {
        Box(
            modifier = Modifier
                .padding(3.dp)
                .size(22.dp)
                .align(if (on) Alignment.CenterEnd else Alignment.CenterStart)
                .clip(RoundedCornerShape(99.dp))
                .background(Color.White),
        )
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(color = Gray100, modifier = Modifier.padding(horizontal = 20.dp))
}