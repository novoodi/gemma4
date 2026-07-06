package com.navoodi.morimi.ui.screen.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navoodi.morimi.ui.theme.*
import kotlinx.coroutines.delay

private val statusOptions = listOf(
    "☕ 카페 좋아요", "🍜 밥 먹고 싶어요", "📚 공부 중",
    "🎮 게임 중", "😴 쉬는 중", "🏃 운동 중", "✈️ 여행 중", "💼 일 중",
)

@Composable
fun ProfileEditScreen(onBack: () -> Unit, onSave: () -> Unit) {
    var name     by remember { mutableStateOf("김민준") }
    var bio      by remember { mutableStateOf("주말 모임을 좋아해요 ☕") }
    var location by remember { mutableStateOf("서울") }
    var status   by remember { mutableStateOf("☕ 카페 좋아요") }
    var saved    by remember { mutableStateOf(false) }
    var focusedField by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(saved) {
        if (saved) { delay(900); onSave() }
    }

    if (saved) {
        Column(
            modifier = Modifier.fillMaxSize().background(White).statusBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MoColors.placeBg)
                    .border(2.dp, MoColors.place, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Check, null, tint = MoColors.place, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text("저장됐어요", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MoColors.textPrimary)
            Spacer(Modifier.height(4.dp))
            Text("프로필이 업데이트됐습니다", fontSize = 14.sp, color = MoColors.textSecondary)
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(MoColors.surfaceSubtle).statusBarsPadding()) {
        // Nav bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(White)
                .padding(horizontal = 8.dp),
        ) {
            TextButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Text("취소", fontSize = 15.sp, color = MoColors.textSecondary, fontWeight = FontWeight.Normal)
            }
            Text("프로필 편집", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = MoColors.textPrimary, modifier = Modifier.align(Alignment.Center))
            TextButton(onClick = { saved = true }, modifier = Modifier.align(Alignment.CenterEnd)) {
                Text("저장", fontSize = 15.sp, color = MoColors.brand, fontWeight = FontWeight.SemiBold)
            }
        }
        HorizontalDivider(color = MoColors.border)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            // Avatar section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(White)
                    .padding(vertical = 28.dp, horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    Box(
                        modifier = Modifier.size(88.dp).clip(CircleShape).background(MoBlue100),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("나", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MoColors.brand)
                    }
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .align(Alignment.BottomEnd)
                            .offset((-2).dp, (-2).dp)
                            .clip(CircleShape)
                            .background(MoColors.textPrimary)
                            .border(2.5.dp, White, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("📷", fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("사진 변경", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MoColors.brand)
            }

            HorizontalDivider(color = MoColors.border)

            // Form
            Column(
                modifier = Modifier
                    .background(White)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                FieldLabel("이름")
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth().onFocusChanged { focusedField = if (it.isFocused) "name" else focusedField?.takeIf { f -> f != "name" } },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = fieldColors(focusedField == "name"),
                )
                Text("그룹 채팅에서 보이는 이름이에요", fontSize = 11.sp, color = MoColors.textTertiary)

                FieldLabel("소개글")
                OutlinedTextField(
                    value = bio, onValueChange = { if (it.length <= 60) bio = it },
                    modifier = Modifier.fillMaxWidth().onFocusChanged { focusedField = if (it.isFocused) "bio" else focusedField?.takeIf { f -> f != "bio" } },
                    maxLines = 2, minLines = 2,
                    shape = RoundedCornerShape(10.dp),
                    colors = fieldColors(focusedField == "bio"),
                )
                Text("${bio.length}/60", fontSize = 11.sp, color = MoColors.textTertiary, modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.End))

                FieldLabel("지역")
                OutlinedTextField(
                    value = location, onValueChange = { location = it },
                    modifier = Modifier.fillMaxWidth().onFocusChanged { focusedField = if (it.isFocused) "location" else focusedField?.takeIf { f -> f != "location" } },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = fieldColors(focusedField == "location"),
                    leadingIcon = { Text("📍", fontSize = 14.sp) },
                )

                FieldLabel("전화번호")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MoColors.surfaceSubtle)
                        .border(1.5.dp, MoColors.border, RoundedCornerShape(10.dp))
                        .padding(13.dp, 13.dp, 13.dp, 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.Check, null, tint = MoColors.place, modifier = Modifier.size(14.dp))
                    Text("010-1234-5678", fontSize = 15.sp, color = MoColors.textSecondary, modifier = Modifier.weight(1f))
                    Text("인증됨", fontSize = 12.sp, color = MoColors.place, fontWeight = FontWeight.Medium)
                }
                Text("전화번호는 변경할 수 없어요", fontSize = 11.sp, color = MoColors.textTertiary)
            }

            HorizontalDivider(color = MoColors.border)

            // Status
            Column(modifier = Modifier.background(White).padding(20.dp)) {
                FieldLabel("상태 메시지")
                Spacer(Modifier.height(12.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    statusOptions.forEach { s ->
                        val sel = status == s
                        Box(
                            modifier = Modifier
                                .padding(bottom = 8.dp)
                                .clip(CircleShape)
                                .background(if (sel) MoColors.brandSubtle else White)
                                .border(1.5.dp, if (sel) MoColors.brand else MoColors.borderStrong, CircleShape)
                                .clickable { status = s }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text(s, fontSize = 13.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, color = if (sel) MoColors.brand else MoColors.textSecondary)
                        }
                    }
                }
            }

            HorizontalDivider(color = MoColors.border)

            // Danger zone
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MoColors.textSecondary),
                ) { Text("계정 비활성화", fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                OutlinedButton(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MoColors.warningText),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFECDCA)),
                ) { Text("계정 삭제", fontSize = 14.sp, fontWeight = FontWeight.Medium) }
            }
        }

        // Bottom save bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(White)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .navigationBarsPadding(),
        ) {
            Button(
                onClick = { if (name.isNotBlank()) saved = true },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (name.isNotBlank()) MoColors.brand else MoColors.borderStrong,
                    disabledContainerColor = MoColors.borderStrong,
                ),
            ) {
                Text("변경사항 저장", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MoColors.textSecondary, letterSpacing = 0.06.sp)
}

@Composable
private fun fieldColors(focused: Boolean) = OutlinedTextFieldDefaults.colors(
    focusedBorderColor   = MoColors.brand,
    unfocusedBorderColor = MoColors.borderStrong,
    focusedContainerColor   = White,
    unfocusedContainerColor = White,
)