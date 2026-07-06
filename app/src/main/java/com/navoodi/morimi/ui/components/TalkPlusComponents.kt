package com.navoodi.morimi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.navoodi.morimi.ui.theme.MoColors

enum class TpTab { Home, Calendar, ChatList, Profile }

private data class TabSpec(
    val tab: TpTab,
    val filled: ImageVector,
    val outlined: ImageVector,
    val label: String,
)

private val TAB_SPECS = listOf(
    TabSpec(TpTab.Home, Icons.Filled.Home, Icons.Outlined.Home, "홈"),
    TabSpec(TpTab.Calendar, Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth, "캘린더"),
    TabSpec(TpTab.ChatList, Icons.Filled.ChatBubble, Icons.Outlined.ChatBubbleOutline, "채팅"),
    TabSpec(TpTab.Profile, Icons.Filled.Person, Icons.Outlined.Person, "프로필"),
)

@Composable
fun TalkPlusTabBar(active: TpTab, onTab: (TpTab) -> Unit) {
    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MoColors.surfaceBase)
    ) {
        HorizontalDivider(thickness = 1.dp, color = MoColors.border)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TAB_SPECS.forEach { spec ->
                TabItem(
                    spec = spec,
                    selected = active == spec.tab,
                    onClick = { onTab(spec.tab) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TabItem(
    spec: TabSpec,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (selected) MoColors.brand else MoColors.textTertiary
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clickable(
                interactionSource = androidx.compose.runtime.remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = if (selected) spec.filled else spec.outlined,
            contentDescription = spec.label,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = spec.label,
            color = tint,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
fun TpAvatar(
    initials: String,
    bg: Color = MoColors.brandSubtle,
    color: Color = MoColors.brand,
    size: Int = 40,
) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            color = color,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
