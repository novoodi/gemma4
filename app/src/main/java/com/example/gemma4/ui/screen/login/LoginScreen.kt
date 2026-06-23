package com.example.gemma4.ui.screen.login

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemma4.ui.theme.*

private data class SocialBtn(
    val label: String,
    val bg: Color,
    val textColor: Color,
    val border: Color,
    val initial: String,
    val initBg: Color,
    val initColor: Color,
)

private val socialBtns = listOf(
    SocialBtn("Google로 계속하기",  Color.White,       Color(0xFF3C4043), Color(0xFFDADCE0), "G", Color(0xFF4285F4), Color.White),
    SocialBtn("카카오로 계속하기", Color(0xFFFEE500), Color(0xFF191919), Color(0xFFFEE500), "K", Color(0xFFFEE500), Color(0xFF191919)),
    SocialBtn("네이버로 계속하기", Color(0xFF03C75A), Color.White,      Color(0xFF03C75A), "N", Color(0xFF03C75A), Color.White),
)

@Composable
fun LoginScreen(onLogin: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(White),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(0.34f))

        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = Gray900, fontWeight = FontWeight.ExtraBold, fontSize = 48.sp, letterSpacing = (-2).sp)) { append("Talk") }
                withStyle(SpanStyle(color = Blue600, fontWeight = FontWeight.ExtraBold, fontSize = 52.sp, letterSpacing = (-2).sp)) { append("+") }
            }
        )
        Spacer(Modifier.height(6.dp))
        Text("함께하는 대화, 더 스마트하게", color = Gray400, fontSize = 13.sp, letterSpacing = 0.2.sp)

        Spacer(Modifier.weight(0.08f))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            socialBtns.forEach { btn ->
                SocialButton(btn = btn, onClick = onLogin)
            }
        }

        Spacer(Modifier.weight(1f))

        Text(
            buildAnnotatedString {
                append("계속하면 ")
                withStyle(SpanStyle(color = Blue600)) { append("이용약관") }
                append(" 및 ")
                withStyle(SpanStyle(color = Blue600)) { append("개인정보처리방침") }
                append("에 동의하는 것으로 간주합니다")
            },
            fontSize = 11.sp,
            color = Gray400,
            modifier = Modifier.padding(horizontal = 28.dp).padding(bottom = 32.dp),
        )
    }
}

@Composable
private fun SocialButton(btn: SocialBtn, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(btn.bg)
            .border(1.5.dp, btn.border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(btn.initBg),
                contentAlignment = Alignment.Center,
            ) {
                Text(btn.initial, color = btn.initColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Text(btn.label, color = btn.textColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}