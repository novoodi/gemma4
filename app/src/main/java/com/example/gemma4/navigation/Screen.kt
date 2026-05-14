package com.example.gemma4.navigation

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Calendar : Screen("calendar")
    data object Chat : Screen("chat/{roomId}") {
        fun createRoute(roomId: String) = "chat/$roomId"
    }
    data object Summary : Screen("summary/{roomId}") {
        fun createRoute(roomId: String) = "summary/$roomId"
    }
}
