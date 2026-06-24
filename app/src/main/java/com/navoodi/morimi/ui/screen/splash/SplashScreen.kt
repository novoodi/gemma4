package com.navoodi.morimi.ui.screen.splash

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navoodi.morimi.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(onDone: () -> Unit) {
    var progress by remember { mutableFloatStateOf(0f) }
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 60),
        label = "splash"
    )

    LaunchedEffect(Unit) {
        while (progress < 1f) {
            delay(60)
            progress = (progress + 0.04f).coerceAtMost(1f)
        }
        delay(300)
        onDone()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(White)
            .statusBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = Gray900, fontWeight = FontWeight.Bold, fontSize = 48.sp, letterSpacing = (-2).sp)) {
                        append("Talk")
                    }
                    withStyle(SpanStyle(color = Blue600, fontWeight = FontWeight.Bold, fontSize = 52.sp, letterSpacing = (-2).sp)) {
                        append("+")
                    }
                }
            )
            Spacer(Modifier.height(8.dp))
            Text("Smart Group Scheduling", color = Gray500, fontSize = 15.sp)
            Spacer(Modifier.height(64.dp))
            Box(
                modifier = Modifier
                    .width(200.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(Gray100)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animatedProgress)
                        .clip(RoundedCornerShape(99.dp))
                        .background(Blue600)
                )
            }
        }
        Text(
            "Preparing your experience…",
            color = Gray400,
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp)
        )
    }
}