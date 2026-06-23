package com.example.gemma4.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.gemma4.ui.theme.*

enum class TpTab { Home, Calendar, ChatList, Profile }

@Composable
fun TalkPlusTabBar(active: TpTab, onTab: (TpTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(82.dp)
            .background(White)
            .padding(bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TabItem(
            filledIcon   = Icons.Filled.Home,
            outlinedIcon = Icons.Outlined.Home,
            selected     = active == TpTab.Home,
            onClick      = { onTab(TpTab.Home) },
            modifier     = Modifier.weight(1f)
        )
        TabItem(
            filledIcon   = Icons.Filled.CalendarMonth,
            outlinedIcon = Icons.Outlined.CalendarMonth,
            selected     = active == TpTab.Calendar,
            onClick      = { onTab(TpTab.Calendar) },
            modifier     = Modifier.weight(1f)
        )
        TabItem(
            filledIcon   = Icons.Filled.ChatBubble,
            outlinedIcon = Icons.Outlined.ChatBubbleOutline,
            selected     = active == TpTab.ChatList,
            onClick      = { onTab(TpTab.ChatList) },
            modifier     = Modifier.weight(1f)
        )
        TabItem(
            filledIcon   = Icons.Filled.Person,
            outlinedIcon = Icons.Outlined.Person,
            selected     = active == TpTab.Profile,
            onClick      = { onTab(TpTab.Profile) },
            modifier     = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TabItem(
    filledIcon: ImageVector,
    outlinedIcon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier        = modifier
            .fillMaxHeight()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector  = if (selected) filledIcon else outlinedIcon,
            contentDescription = null,
            tint         = if (selected) Blue600 else Gray400,
            modifier     = Modifier.size(24.dp)
        )
    }
}

@Composable
fun TpAvatar(
    initials: String,
    bg: Color       = Blue100,
    color: Color    = Blue700,
    size: Int       = 40,
) {
    Box(
        modifier        = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Text(
            text  = initials,
            color = color,
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
        )
    }
}