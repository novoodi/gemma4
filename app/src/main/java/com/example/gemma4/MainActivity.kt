package com.example.gemma4

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.gemma4.navigation.Screen
import com.example.gemma4.ui.screen.calendar.CalendarScreen
import com.example.gemma4.ui.screen.chat.ChatScreen
import com.example.gemma4.ui.screen.home.HomeScreen
import com.example.gemma4.ui.screen.summary.SummaryScreen
import com.example.gemma4.ui.theme.Gemma4Theme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Gemma4Theme {
                val navController = rememberNavController()
                NavHost(
                    navController = navController,
                    startDestination = Screen.Home.route
                ) {
                    composable(Screen.Home.route) {
                        HomeScreen(navController = navController)
                    }
                    composable(Screen.Calendar.route) {
                        CalendarScreen(navController = navController)
                    }
                    composable(
                        route = Screen.Chat.route,
                        arguments = listOf(navArgument("roomId") { type = NavType.StringType })
                    ) {
                        ChatScreen(navController = navController)
                    }
                    composable(
                        route = Screen.Summary.route,
                        arguments = listOf(navArgument("roomId") { type = NavType.StringType })
                    ) {
                        SummaryScreen(navController = navController)
                    }
                }
            }
        }
    }
}
