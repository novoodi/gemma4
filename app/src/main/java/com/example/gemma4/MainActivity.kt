package com.example.gemma4

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.gemma4.navigation.Screen
import com.example.gemma4.ui.components.TpTab
import com.example.gemma4.ui.screen.ailoading.AILoadingScreen
import com.example.gemma4.ui.screen.aireport.AIReportScreen
import com.example.gemma4.ui.screen.calendar.CalendarScreen
import com.example.gemma4.ui.screen.chat.ChatScreen
import com.example.gemma4.ui.screen.chatlist.ChatListScreen
import com.example.gemma4.ui.screen.home.HomeScreen
import com.example.gemma4.ui.screen.login.LoginScreen
import com.example.gemma4.ui.screen.onboarding.OnboardingScreen
import com.example.gemma4.ui.screen.profile.MyPageScreen
import com.example.gemma4.ui.screen.profile.ProfileEditScreen
import com.example.gemma4.ui.screen.splash.SplashScreen
import com.example.gemma4.ui.screen.summary.SummaryScreen
import com.example.gemma4.ui.screen.vote.VotingScreen
import com.example.gemma4.ui.theme.Gemma4Theme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Gemma4Theme {
                val navController = rememberNavController()
                var activeTab by remember { mutableStateOf(TpTab.Home) }

                fun goTab(tab: TpTab) {
                    activeTab = tab
                    val route = when (tab) {
                        TpTab.Home     -> Screen.Home.route
                        TpTab.Calendar -> Screen.Calendar.route
                        TpTab.ChatList -> Screen.ChatList.route
                        TpTab.Profile  -> Screen.Profile.route
                    }
                    navController.navigate(route) {
                        popUpTo(Screen.Home.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }

                NavHost(
                    navController    = navController,
                    startDestination = Screen.Splash.route,
                ) {
                    // Onboarding flow
                    composable(Screen.Splash.route) {
                        SplashScreen(onDone = {
                            navController.navigate(Screen.Login.route) {
                                popUpTo(Screen.Splash.route) { inclusive = true }
                            }
                        })
                    }
                    composable(Screen.Onboarding.route) {
                        OnboardingScreen(onDone = {
                            navController.navigate(Screen.Home.route) {
                                popUpTo(Screen.Onboarding.route) { inclusive = true }
                            }
                        })
                    }
                    composable(Screen.Login.route) {
                        LoginScreen(onLogin = {
                            navController.navigate(Screen.Onboarding.route) {
                                popUpTo(Screen.Login.route) { inclusive = true }
                            }
                        })
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
                        )
                    }
                    composable(Screen.ProfileEdit.route) {
                        ProfileEditScreen(
                            onBack = { navController.popBackStack() },
                            onSave = { navController.popBackStack() },
                        )
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
                    ) {
                        AILoadingScreen(navController = navController)
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
}