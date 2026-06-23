package com.example.gemma4.navigation

sealed class Screen(val route: String) {
    data object Splash      : Screen("splash")
    data object Onboarding  : Screen("onboarding")
    data object Login       : Screen("login")
    data object Home        : Screen("home")
    data object ChatList    : Screen("chatlist")
    data object Calendar    : Screen("calendar")
    data object Profile     : Screen("profile")
    data object ProfileEdit : Screen("profile-edit")

    data object Chat : Screen("chat/{roomId}") {
        fun createRoute(roomId: String) = "chat/$roomId"
    }
    data object AILoading : Screen("ai-loading/{roomId}") {
        fun createRoute(roomId: String) = "ai-loading/$roomId"
    }
    data object AIReport : Screen("ai-report/{roomId}") {
        fun createRoute(roomId: String) = "ai-report/$roomId"
    }
    data object Vote : Screen("vote/{roomId}") {
        fun createRoute(roomId: String) = "vote/$roomId"
    }
    data object Summary : Screen("summary/{roomId}") {
        fun createRoute(roomId: String) = "summary/$roomId"
    }
}