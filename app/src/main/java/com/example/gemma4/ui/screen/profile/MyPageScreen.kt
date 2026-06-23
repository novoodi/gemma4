package com.example.gemma4.ui.screen.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemma4.ui.components.TalkPlusTabBar
import com.example.gemma4.ui.components.TpTab
import com.example.gemma4.ui.theme.*

@Composable
fun MyPageScreen(
    onEdit: () -> Unit,
    onTab: (TpTab) -> Unit,
    activeTab: TpTab,
) {
    var pushOn by remember { mutableStateOf(true) }
    var marketingOn by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(Gray50)) {
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
                SettingsRowChevron("로그아웃", danger = true, showChevron = false)
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