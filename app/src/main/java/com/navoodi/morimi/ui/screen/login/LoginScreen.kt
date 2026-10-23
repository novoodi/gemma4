package com.navoodi.morimi.ui.screen.login

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
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



// 브랜드 컬러 — 블루 → 스카이블루 그라데이션 (워드마크)
private val MorimiBlue = Color(0xFF1F4EF5)
private val MorimiSky = Color(0xFF38BDF8)
private val MorimiGradient = Brush.horizontalGradient(listOf(MorimiBlue, MorimiSky))

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

            // morimi 워드마크 — 블루 → 스카이블루 그라데이션
            Text(
                text = "morimi",
                style = TextStyle(
                    brush = MorimiGradient,
                    fontFamily = Pretendard,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 52.sp,
                    letterSpacing = (-1.5).sp,
                ),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "당신을 위한 모임 알리미",
                fontFamily = Pretendard,
                color = MoColors.textTertiary,
                fontSize = 14.sp,
                letterSpacing = 0.2.sp,
            )

            Spacer(Modifier.weight(0.08f))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 순서: Google → 카카오 → 네이버 (각 사 공식 가이드 버튼 이미지 사용)
                GoogleLoginButton(
                    enabled = !isLoading,
                    onClick = {
                        Log.d(TAG, "▶ [0] Google 버튼 클릭됨")
                        scope.launch { launchGoogleSignIn(context, authViewModel) }
                    },
                )
                BrandImageButton(
                    resId = R.drawable.btn_kakao_login_wide,
                    description = "카카오 로그인",
                    aspectRatio = 600f / 90f,
                    enabled = !isLoading,
                    onClick = { Toast.makeText(context, "카카오 로그인은 준비 중입니다", Toast.LENGTH_SHORT).show() },
                )
                BrandImageButton(
                    resId = R.drawable.btn_naver_login_wide,
                    description = "네이버 로그인",
                    aspectRatio = 1472f / 192f,
                    enabled = !isLoading,
                    onClick = { Toast.makeText(context, "네이버 로그인은 준비 중입니다", Toast.LENGTH_SHORT).show() },
                )
            }

            if (isLoading) {
                Spacer(Modifier.height(20.dp))
                CircularProgressIndicator(
                    color = MoColors.brand,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(24.dp),
                )
            }

            Spacer(Modifier.weight(1f))

            Text(
                buildAnnotatedString {
                    append("계속하면 ")
                    withStyle(SpanStyle(color = MoColors.brand)) { append("이용약관") }
                    append(" 및 ")
                    withStyle(SpanStyle(color = MoColors.brand)) { append("개인정보처리방침") }
                    append("에 동의하는 것으로 간주합니다")
                },
                fontSize = 11.sp,
                color = MoColors.textTertiary,
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
                        color = MoColors.brand,
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

/** Google 공식 G 로고 + 한글 문구 (Google 브랜드 가이드: 흰 배경, 회색 테두리) */
@Composable
private fun GoogleLoginButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = if (enabled) 1f else 0.5f))
            .border(1.dp, Color(0xFFDADCE0), RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_google_logo),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 18.dp)
                .size(20.dp),
        )
        Text(
            "Google 로그인",
            fontFamily = Pretendard,
            color = Color(0xFF1F1F1F),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 카카오·네이버 공식 와이드 버튼 이미지 — 원본 비율을 유지해 왜곡 없이 표시 */
@Composable
private fun BrandImageButton(
    resId: Int,
    description: String,
    aspectRatio: Float,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Image(
        painter = painterResource(resId),
        contentDescription = description,
        contentScale = ContentScale.Fit,
        alpha = if (enabled) 1f else 0.5f,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
    )
}
