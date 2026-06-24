package com.navoodi.morimi.ui.screen.login

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private const val TAG = "AuthViewModel"
private const val USERS_COLLECTION = "users"
private const val FIELD_ONBOARDING = "onboardingCompleted"

sealed interface AuthState {
    data object Loading : AuthState
    data class LoggedIn(val uid: String, val displayName: String?, val email: String?) : AuthState
    data object LoggedOut : AuthState
}

// SplashScreen 이후 이동할 목적지
sealed interface StartupState {
    data object Checking : StartupState       // Firestore 조회 중 — 아직 판단 불가
    data object GoToLogin : StartupState
    data object GoToOnboarding : StartupState
    data object GoToHome : StartupState
}

class AuthViewModel : ViewModel() {
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    private val _authState = MutableStateFlow<AuthState>(
        auth.currentUser?.let { AuthState.LoggedIn(it.uid, it.displayName, it.email) }
            ?: AuthState.LoggedOut
    )
    val authState: StateFlow<AuthState> = _authState

    // 앱 시작 시 목적지 — 초기값 Checking (Firestore 결과 대기)
    private val _startupState = MutableStateFlow<StartupState>(StartupState.Checking)
    val startupState: StateFlow<StartupState> = _startupState

    private val _signInError = MutableStateFlow<String?>(null)
    val signInError: StateFlow<String?> = _signInError

    init {
        viewModelScope.launch { determineStartupDestination() }
    }

    // ──────────────────────────────────────────────
    // 앱 시작 — Firebase currentUser + Firestore 확인
    // ──────────────────────────────────────────────

    private suspend fun determineStartupDestination() {
        val user = auth.currentUser
        if (user == null) {
            Log.d(TAG, "currentUser = null → GoToLogin")
            _startupState.value = StartupState.GoToLogin
            return
        }
        Log.d(TAG, "currentUser = ${user.uid}, Firestore 온보딩 상태 조회 중…")
        _startupState.value = resolveDestinationFromFirestore(user.uid)
    }

    // Firestore users/{uid} 를 읽어 목적지를 결정. 문서 없으면 생성.
    private suspend fun resolveDestinationFromFirestore(uid: String): StartupState {
        return try {
            val docRef = firestore.collection(USERS_COLLECTION).document(uid)
            val snapshot = docRef.get().await()
            if (!snapshot.exists()) {
                Log.d(TAG, "users/$uid 문서 없음 → 신규 생성 후 GoToOnboarding")
                docRef.set(mapOf(FIELD_ONBOARDING to false)).await()
                StartupState.GoToOnboarding
            } else {
                val done = snapshot.getBoolean(FIELD_ONBOARDING) ?: false
                Log.d(TAG, "users/$uid onboardingCompleted=$done")
                if (done) StartupState.GoToHome else StartupState.GoToOnboarding
            }
        } catch (e: Exception) {
            Log.e(TAG, "✗ Firestore 읽기 실패: ${e::class.simpleName} — ${e.message}", e)
            StartupState.GoToOnboarding  // 실패 시 온보딩으로 폴백
        }
    }

    // ──────────────────────────────────────────────
    // 구글 로그인 — Firebase auth + Firestore 순차 처리
    // ──────────────────────────────────────────────

    fun signInWithGoogleIdToken(idToken: String) {
        Log.d(TAG, "▶ [2] signInWithGoogleIdToken — idToken 길이=${idToken.length}")
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            _signInError.value = null
            Log.d(TAG, "▶ [3] authState → Loading, Firebase signInWithCredential 시작")
            try {
                val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)
                val result = auth.signInWithCredential(firebaseCredential).await()
                val user = result.user
                if (user == null) {
                    Log.e(TAG, "✗ Firebase 인증 결과 user == null")
                    _authState.value = AuthState.LoggedOut
                    _signInError.value = "로그인에 실패했습니다. 다시 시도해 주세요."
                    return@launch
                }
                Log.d(TAG, "▶ [4] Firebase 인증 성공 — uid=${user.uid}, email=${user.email}")

                // authState를 Loading 유지하면서 Firestore 확인
                val destination = resolveDestinationFromFirestore(user.uid)

                // 모든 검사 완료 후 원자적으로 상태 업데이트
                _authState.value = AuthState.LoggedIn(user.uid, user.displayName, user.email)
                _startupState.value = destination
                Log.d(TAG, "▶ [5] authState → LoggedIn, startupState → $destination")
            } catch (e: Exception) {
                Log.e(TAG, "✗ Firebase signInWithCredential 실패: ${e::class.simpleName} — ${e.message}", e)
                _authState.value = AuthState.LoggedOut
                _signInError.value = e.message ?: "로그인 중 오류가 발생했습니다."
            }
        }
    }

    // ──────────────────────────────────────────────
    // 온보딩 완료 기록
    // ──────────────────────────────────────────────

    fun completeOnboarding() {
        val uid = auth.currentUser?.uid ?: run {
            Log.e(TAG, "✗ completeOnboarding — currentUser == null")
            return
        }
        viewModelScope.launch {
            try {
                firestore.collection(USERS_COLLECTION).document(uid)
                    .update(FIELD_ONBOARDING, true)
                    .await()
                Log.d(TAG, "onboardingCompleted → true 저장 완료 (uid=$uid)")
            } catch (e: Exception) {
                Log.e(TAG, "✗ Firestore completeOnboarding 실패: ${e::class.simpleName} — ${e.message}", e)
            }
        }
    }

    fun clearSignInError() {
        _signInError.value = null
    }

    fun signOut() {
        auth.signOut()
        _authState.value = AuthState.LoggedOut
        _startupState.value = StartupState.GoToLogin
        Log.d(TAG, "로그아웃 완료")
    }
}