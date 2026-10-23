package com.navoodi.morimi.ui.components

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.navoodi.morimi.ui.theme.Gemma4Theme
import com.navoodi.morimi.ui.theme.MoColors

/** Firebase 설정 없이 공통 하단 메뉴의 크기와 터치를 확인한다. */
class TabBarPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Gemma4Theme {
                var selectedTab by remember { mutableStateOf(TpTab.Home) }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MoColors.surfaceBase)
                        .statusBarsPadding(),
                ) {
                    Column(modifier = Modifier.weight(1f).padding(24.dp)) {
                        Text("오늘", style = MaterialTheme.typography.displayMedium)
                        Spacer(Modifier.height(32.dp))
                        Text("오늘 일정", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(24.dp))
                        Text("오늘 일정이 없어요", color = MoColors.textTertiary)
                    }
                    TalkPlusTabBar(active = selectedTab, onTab = { selectedTab = it })
                }
            }
        }
    }
}
