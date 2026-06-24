package com.navoodi.morimi.ui.screen.login

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.navoodi.morimi.R
import com.navoodi.morimi.ui.theme.*
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

private const val TAG = "LoginScreen"



private data class SocialBtn(
    val label: String,
    val bg: Color,
    val textColor: Color,
    val border: Color,
    val initial: String,
    val initBg: Color,
    val initColor: Color,
    val isGoogle: Boolean = false,
)

private val socialBtns = listOf(
    SocialBtn("Google로 계속하기",  Color.White,       Color(0xFF3C4043), Color(0xFFDADCE0), "G", Color(0xFF4285F4), Color.White, isGoogle = true),
    SocialBtn("카카오로 계속하기", Color(0xFFFEE500), Color(0xFF191919), Color(0xFFFEE500), "K", Color(0xFFFEE500), Color(0xFF191919)),
    SocialBtn("네이버로 계속하기", Color(0xFF03C75A), Color.White,      Color(0xFF03C75A), "N", Color(0xFF03C75A), Color.White),
)

@Composable
fun LoginScreen(
    authViewModel: AuthViewModel,
    onGoToOnboarding: () -> Unit,
    onGoToHome: () -> Unit,
) {
    val signInError by authViewModel.signInError.collectAsStateWithLifecycle()
    val authState by authViewModel.authState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // recomposition마다 람다가 교체돼도 항상 최신 참조를 사용
    val currentOnGoToOnboarding by rememberUpdatedState(onGoToOnboarding)
    val currentOnGoToHome by rememberUpdatedState(onGoToHome)

    // startupState가 결정되면 목적지로 이동
    LaunchedEffect(Unit) {
        Log.d(TAG, "▶ [6] LoginScreen: startupState flow collect 시작")
        authViewModel.startupState
            .filter { it == StartupState.GoToOnboarding || it == StartupState.GoToHome }
            .collect { state ->
                Log.d(TAG, "▶ [7] startupState 수신 → $state, 네비게이션 실행")
                when (state) {
                    StartupState.GoToOnboarding -> currentOnGoToOnboarding()
                    StartupState.GoToHome       -> currentOnGoToHome()
                    else                        -> Unit
                }
                Log.d(TAG, "▶ [8] 네비게이션 람다 호출 완료")
            }
    }

    val isLoading = authState is AuthState.Loading

    Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
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
                    SocialButton(
                        btn = btn,
                        enabled = !isLoading,
                        onClick = if (btn.isGoogle) {
                            {
                                Log.d(TAG, "▶ [0] Google 버튼 클릭됨")
                                scope.launch { launchGoogleSignIn(context, authViewModel) }
                            }
                        } else {
                            { }
                        },
                    )
                }
            }

            if (isLoading) {
                Spacer(Modifier.height(20.dp))
                CircularProgressIndicator(
                    color = Blue600,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(24.dp),
                )
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

        if (signInError != null) {
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                action = {
                    Text(
                        "닫기",
                        color = Blue600,
                        modifier = Modifier.clickable { authViewModel.clearSignInError() },
                    )
                },
            ) {
                Text(signInError ?: "", fontSize = 13.sp)
            }
        }
    }
}

private suspend fun launchGoogleSignIn(context: Context, authViewModel: AuthViewModel) {
    Log.d(TAG, "▶ [1] CredentialManager getCredential 시작")
    val credentialManager = CredentialManager.create(context)
    val googleIdOption = GetGoogleIdOption.Builder()
        .setFilterByAuthorizedAccounts(false)
        .setServerClientId(context.getString(R.string.default_web_client_id))
        .setAutoSelectEnabled(false)
        .build()
    val request = GetCredentialRequest.Builder()
        .addCredentialOption(googleIdOption)
        .build()
    try {
        val result = credentialManager.getCredential(context, request)
        val credential = result.credential
        Log.d(TAG, "▶ [1a] Credential 수신 — type=${credential.type}, class=${credential::class.simpleName}")

        // Credential Manager는 GoogleIdTokenCredential을 CustomCredential로 감싸서 반환함.
        // 직접 is 체크 대신 type 문자열로 판별 후 createFrom() 으로 언박싱 필요.
        when {
            credential is GoogleIdTokenCredential -> {
                Log.d(TAG, "▶ [1b] 직접 GoogleIdTokenCredential 수신")
                authViewModel.signInWithGoogleIdToken(credential.idToken)
            }
            credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL -> {
                Log.d(TAG, "▶ [1b] CustomCredential → GoogleIdTokenCredential 변환")
                val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
                authViewModel.signInWithGoogleIdToken(googleCredential.idToken)
            }
            else -> {
                Log.e(TAG, "✗ 예상치 못한 credential 타입: ${credential.type} / ${credential::class.simpleName}")
            }
        }
    } catch (e: GetCredentialException) {
        Log.w(TAG, "✗ GetCredentialException (사용자 취소 또는 설정 오류): ${e::class.simpleName} — ${e.message}")
    } catch (e: Exception) {
        Log.e(TAG, "✗ launchGoogleSignIn 예외: ${e::class.simpleName} — ${e.message}", e)
    }
}

@Composable
private fun SocialButton(btn: SocialBtn, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(btn.bg.copy(alpha = if (enabled) 1f else 0.5f))
            .border(1.5.dp, btn.border, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
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