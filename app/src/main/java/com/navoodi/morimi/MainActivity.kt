package com.navoodi.morimi

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.navoodi.morimi.data.repository.FcmRepository
import com.navoodi.morimi.navigation.Screen
import com.navoodi.morimi.ui.components.TpTab
import com.navoodi.morimi.ui.screen.ailoading.AILoadingScreen
import com.navoodi.morimi.ui.screen.aireport.AIReportScreen
import com.navoodi.morimi.ui.screen.calendar.CalendarScreen
import com.navoodi.morimi.ui.screen.chat.ChatScreen
import com.navoodi.morimi.ui.screen.chat.ChatViewModel
import com.navoodi.morimi.ui.screen.chatlist.ChatListScreen
import com.navoodi.morimi.ui.screen.home.HomeScreen
import com.navoodi.morimi.ui.screen.login.StartupState
import com.navoodi.morimi.ui.screen.login.AuthViewModel
import com.navoodi.morimi.ui.screen.login.LoginScreen
import com.navoodi.morimi.ui.screen.onboarding.OnboardingScreen
import com.navoodi.morimi.ui.screen.profile.MyPageScreen
import com.navoodi.morimi.ui.screen.profile.ProfileEditScreen
import com.navoodi.morimi.ui.screen.splash.SplashScreen
import com.navoodi.morimi.ui.screen.joinroom.JoinRoomScreen
import com.navoodi.morimi.ui.screen.modeldownload.ModelDownloadScreen
import com.navoodi.morimi.ui.screen.summary.SummaryScreen
import com.navoodi.morimi.ui.screen.vote.VotingScreen
import com.navoodi.morimi.ui.theme.Gemma4Theme

class MainActivity : ComponentActivity() {

    // 알림 탭으로 진입 시 이동할 roomId / 목적지 — Compose State이므로 변경 시 자동 recompose
    private var pendingRoomId by mutableStateOf<String?>(null)
    private var pendingNavTo  by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 앱이 종료된 상태에서 알림 탭 → onCreate Intent에 roomId/navTo 포함
        pendingRoomId = intent.getStringExtra("roomId")
        pendingNavTo  = intent.getStringExtra("navTo")
        enableEdgeToEdge()
        setContent {
            Gemma4Theme {
                val context = LocalContext.current
                val navController = rememberNavController()
                var activeTab by remember { mutableStateOf(TpTab.Home) }
                val authViewModel: AuthViewModel = viewModel()
                val startupState by authViewModel.startupState.collectAsStateWithLifecycle()

                // SplashScreen 애니메이션 완료 여부
                var splashDone by remember { mutableStateOf(false) }
                // 스플래시 완료 후 최초 네비게이션이 끝났는지 — 알림 딥링크 처리 주체를 구분해 레이스 방지
                var initialNavDone by remember { mutableStateOf(false) }

                // 로그인 완료 후 모델 유무에 따라 Home 또는 ModelDownload로 분기
                fun homeOrDownload() =
                    if ((context.applicationContext as MoimApp).llmService.isModelAvailable)
                        Screen.Home.route
                    else
                        Screen.ModelDownload.route

                // POST_NOTIFICATIONS 런타임 권한 요청 (Android 13+)
                val notifPermLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { /* granted 여부에 따른 별도 처리 없음 — FCM 토큰 저장은 권한 무관 */ }
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val state = ContextCompat.checkSelfPermission(
                            context, android.Manifest.permission.POST_NOTIFICATIONS
                        )
                        if (state != PackageManager.PERMISSION_GRANTED) {
                            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }

                // 애니메이션 + Firestore 결과가 모두 준비됐을 때 단 한 번만 이동
                // → SplashScreen이 로딩 화면 역할을 자연스럽게 대신함 (깜빡임 없음)
                LaunchedEffect(splashDone, startupState) {
                    if (!splashDone || startupState == StartupState.Checking) return@LaunchedEffect
                    val destination = when (startupState) {
                        StartupState.GoToLogin      -> Screen.Login.route
                        StartupState.GoToOnboarding -> Screen.Onboarding.route
                        StartupState.GoToHome       -> homeOrDownload()
                        else                        -> return@LaunchedEffect
                    }
                    // 로그인 상태 확정 시 FCM 토큰 발급 & 저장
                    // (앱 시작 시 이미 로그인 + 로그인 성공 + 온보딩 완료 세 경우 모두 커버)
                    if (startupState == StartupState.GoToHome) {
                        FcmRepository.fetchAndSaveToken()
                    }
                    navController.navigate(destination) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                    // 알림 콜드스타트: 딥링크를 이 곳에서 함께 처리해 백스택을 [home, chat]으로 구성.
                    // (별도 알림 effect가 splash 위에 chat을 쌓았다가 이 popUpTo에 함께 pop되는 레이스 방지)
                    val id = pendingRoomId
                    if (startupState == StartupState.GoToHome && id != null) {
                        val deepLink = if (pendingNavTo == "aiReport")
                            Screen.AIReport.createRoute(id)
                        else
                            Screen.Chat.createRoute(id)
                        navController.navigate(deepLink) { launchSingleTop = true }
                        clearPendingNav()
                    }
                    initialNavDone = true
                }

                fun goTab(tab: TpTab) {
                    activeTab = tab
                    val route = when (tab) {
                        TpTab.Home     -> Screen.Home.route
                        TpTab.Calendar -> Screen.Calendar.route
                        TpTab.ChatList -> Screen.ChatList.route
                        TpTab.Profile  -> Screen.Profile.route
                    }
                    navController.navigate(route) {
                        // graph.id는 NavGraph 컨테이너 자체 — 백스택 최하단에 항상 존재.
                        // Home이 백스택에 없는 경우(알림 직접 진입 등)에도 안전하게 동작.
                        // 탭 이동은 항상 해당 탭의 루트로 리셋 — saveState/restoreState로 스택을
                        // 보존하면 탭 복귀 시 저장된 채팅방/AIReport가 함께 되살아난다(실기기 확인).
                        // 탭 화면들은 저장소 Flow에서 재구성되므로 보존이 필요한 화면 상태가 없다.
                        popUpTo(navController.graph.id)
                        launchSingleTop = true
                    }
                }

                // 앱 실행 중 알림 탭(onNewIntent) → 현재 백스택 위에 목적지 push.
                // 콜드스타트 딥링크는 위 splash effect가 담당하므로, 여기선 최초 네비 완료 후에만 동작.
                // navTo=="aiReport" 이면 AIReport 화면, 그 외엔 기존 채팅방 이동
                LaunchedEffect(pendingRoomId, startupState, initialNavDone) {
                    val id = pendingRoomId
                    if (initialNavDone && id != null && startupState == StartupState.GoToHome) {
                        val destination = if (pendingNavTo == "aiReport")
                            Screen.AIReport.createRoute(id)
                        else
                            Screen.Chat.createRoute(id)
                        navController.navigate(destination) { launchSingleTop = true }
                        clearPendingNav()
                    }
                }

                NavHost(
                    navController    = navController,
                    startDestination = Screen.Splash.route,
                ) {
                    // Splash: 애니메이션이 끝나면 splashDone = true.
                    // 실제 이동은 위의 LaunchedEffect에서 startupState가 확정되면 처리.
                    composable(Screen.Splash.route) {
                        SplashScreen(onDone = { splashDone = true })
                    }

                    // 온보딩: 계정에 묶인 최초 1회. 완료 시 Firestore 업데이트 후 Home으로.
                    composable(Screen.Onboarding.route) {
                        OnboardingScreen(onDone = {
                            authViewModel.completeOnboarding()
                            navController.navigate(homeOrDownload()) {
                                popUpTo(Screen.Onboarding.route) { inclusive = true }
                            }
                        })
                    }

                    // 로그인: 성공 후 startupState에 따라 온보딩 or 메인으로 분기
                    composable(Screen.Login.route) {
                        LoginScreen(
                            authViewModel = authViewModel,
                            onGoToOnboarding = {
                                navController.navigate(Screen.Onboarding.route) {
                                    popUpTo(Screen.Login.route) { inclusive = true }
                                }
                            },
                            onGoToHome = {
                                navController.navigate(homeOrDownload()) {
                                    popUpTo(Screen.Login.route) { inclusive = true }
                                }
                            },
                        )
                    }

                    // Tab bar destinations
                    composable(Screen.Home.route) {
                        LaunchedEffect(Unit) { activeTab = TpTab.Home }
                        HomeScreen(
                            onOpenChat = { roomId ->
                                navController.navigate(Screen.Chat.createRoute(roomId))
                            },
                            onTab = ::goTab,
                            activeTab = activeTab,
                        )
                    }
                    composable(Screen.ChatList.route) {
                        LaunchedEffect(Unit) { activeTab = TpTab.ChatList }
                        ChatListScreen(
                            onOpenChat = { roomId ->
                                navController.navigate(Screen.Chat.createRoute(roomId))
                            },
                            onNewRoom = {},
                            onJoinRoom = { navController.navigate(Screen.JoinRoom.route) },
                            onTab = ::goTab,
                            activeTab = activeTab,
                        )
                    }
                    composable(Screen.Calendar.route) {
                        LaunchedEffect(Unit) { activeTab = TpTab.Calendar }
                        CalendarScreen(
                            navController = navController,
                            onTab = ::goTab,
                            activeTab = activeTab,
                        )
                    }
                    composable(Screen.Profile.route) {
                        LaunchedEffect(Unit) { activeTab = TpTab.Profile }
                        MyPageScreen(
                            onEdit = { navController.navigate(Screen.ProfileEdit.route) },
                            onTab = ::goTab,
                            activeTab = activeTab,
                            onLogout = {
                                authViewModel.signOut()
                                // 전체 백스택 초기화 — 뒤로가기로 메인 화면 재진입 불가
                                navController.navigate(Screen.Login.route) {
                                    popUpTo(0) { inclusive = true }
                                }
                            },
                            onModelDownload = {
                                navController.navigate(Screen.ModelDownload.route)
                            },
                        )
                    }
                    composable(Screen.ProfileEdit.route) {
                        ProfileEditScreen(
                            onBack = { navController.popBackStack() },
                            onSave = { navController.popBackStack() },
                        )
                    }

                    composable(Screen.JoinRoom.route) {
                        JoinRoomScreen(navController = navController)
                    }

                    composable(Screen.ModelDownload.route) {
                        ModelDownloadScreen(navController = navController)
                    }

                    // Chat flow
                    composable(
                        route     = Screen.Chat.route,
                        arguments = listOf(navArgument("roomId") { type = NavType.StringType }),
                    ) {
                        ChatScreen(navController = navController)
                    }
                    composable(
                        route     = Screen.AILoading.route,
                        arguments = listOf(navArgument("roomId") { type = NavType.StringType }),
                    ) { backStackEntry ->
                        val roomId = backStackEntry.arguments?.getString("roomId") ?: ""
                        val chatEntry = remember(backStackEntry) {
                            navController.getBackStackEntry(Screen.Chat.createRoute(roomId))
                        }
                        val chatViewModel: ChatViewModel = viewModel(chatEntry)
                        val agentProgress by chatViewModel.agentProgress.collectAsStateWithLifecycle()
                        val debugLog by chatViewModel.debugLog.collectAsStateWithLifecycle()
                        AILoadingScreen(
                            navController = navController,
                            agentProgress = agentProgress,
                            debugLog = debugLog,
                        )
                    }
                    composable(
                        route     = Screen.AIReport.route,
                        arguments = listOf(navArgument("roomId") { type = NavType.StringType }),
                    ) {
                        AIReportScreen(navController = navController)
                    }
                    composable(
                        route     = Screen.Vote.route,
                        arguments = listOf(navArgument("roomId") { type = NavType.StringType }),
                    ) {
                        VotingScreen(navController = navController)
                    }
                    composable(
                        route     = Screen.Summary.route,
                        arguments = listOf(navArgument("roomId") { type = NavType.StringType }),
                    ) {
                        SummaryScreen(navController = navController)
                    }
                }
            }
        }
    }

    // 앱이 실행 중일 때 알림 탭 → onNewIntent로 roomId/navTo 수신
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val roomId = intent.getStringExtra("roomId")
        if (roomId != null) {
            pendingRoomId = roomId
            pendingNavTo  = intent.getStringExtra("navTo")
        }
    }

    // 딥링크 소비 후 pending 상태와 intent extra를 함께 제거.
    // extra를 남겨두면 회전·프로세스 재생성 시 onCreate가 같은 intent에서 다시 읽어 과거 방으로 재이동함.
    private fun clearPendingNav() {
        pendingRoomId = null
        pendingNavTo  = null
        intent.removeExtra("roomId")
        intent.removeExtra("navTo")
    }
}