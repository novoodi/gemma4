package com.navoodi.morimi.ui.screen.vote

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.navoodi.morimi.ui.theme.*

private data class VoteOption(val id: Int, val name: String, val type: String, val dist: String)

private val places = listOf(
    VoteOption(1, "카페 보라", "카페 · 디저트", "0.3km"),
    VoteOption(2, "인사옥", "한식 · 전통", "0.6km"),
    VoteOption(3, "통인시장 기름떡볶이", "분식 · 전통시장", "1.1km"),
)

private val fakeVotes = mapOf(1 to 2, 2 to 1, 3 to 0)

@Composable
fun VotingScreen(navController: NavController) {
    var myVote by remember { mutableStateOf<Int?>(null) }
    var submitted by remember { mutableStateOf(false) }

    val extraVote = if (myVote != null) 1 else 0
    val total = fakeVotes.values.sum() + extraVote

    Column(modifier = Modifier.fillMaxSize().background(White).statusBarsPadding()) {
        // Nav bar
        Box(
            modifier = Modifier.fillMaxWidth().height(44.dp).background(White).padding(horizontal = 8.dp),
        ) {
            IconButton(onClick = { navController.popBackStack() }, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로", tint = Gray900)
            }
            Text("Vote on a Spot", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Gray900, modifier = Modifier.align(Alignment.Center))
        }
        HorizontalDivider(color = Gray100)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
        ) {
            Text(
                if (submitted) "✓ Your vote was recorded! Waiting for others…"
                else "Choose your preferred meeting spot. 3 members haven't voted yet.",
                fontSize = 14.sp, color = Gray500,
                modifier = Modifier.padding(bottom = 20.dp),
            )

            places.forEach { p ->
                val count = (fakeVotes[p.id] ?: 0) + if (myVote == p.id) 1 else 0
                val pct   = if (total > 0) count.toFloat() / total else 0f
                val animPct by animateFloatAsState(targetValue = pct, animationSpec = tween(400), label = "vote")
                val isSelected = myVote == p.id

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(White)
                        .border(2.dp, if (isSelected) Blue600 else Gray200, RoundedCornerShape(14.dp))
                        .clickable(enabled = !submitted) { myVote = p.id }
                        .padding(16.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(p.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Gray900)
                            Text("${p.type} · ${p.dist}", fontSize = 12.sp, color = Gray500)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (isSelected) {
                                Box(
                                    modifier = Modifier.size(20.dp).clip(CircleShape).background(Blue600),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = White, modifier = Modifier.size(12.dp))
                                }
                            }
                            Text(
                                "$count vote${if (count != 1) "s" else ""}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) Blue600 else Gray600,
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // Progress bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(Gray100),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(animPct)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(99.dp))
                                .background(if (isSelected) Blue600 else Gray400),
                        )
                    }

                    Spacer(Modifier.height(4.dp))
                    Text("${(pct * 100).toInt()}%", fontSize = 11.sp, color = Gray400)
                }
            }
        }

        // Bottom button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(White)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .navigationBarsPadding(),
        ) {
            if (!submitted) {
                Button(
                    onClick = { if (myVote != null) submitted = true },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    enabled = myVote != null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (myVote != null) Blue600 else Gray200,
                        disabledContainerColor = Gray200,
                    ),
                ) {
                    Text("Submit Vote", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        color = if (myVote != null) White else Gray400)
                }
            } else {
                Button(
                    onClick = { navController.popBackStack() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Success600),
                ) {
                    Text("Back to Chat ✓", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}