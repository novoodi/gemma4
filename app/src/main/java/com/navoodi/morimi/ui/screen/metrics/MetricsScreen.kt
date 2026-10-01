package com.navoodi.morimi.ui.screen.metrics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.navoodi.morimi.service.AhpCriterion
import com.navoodi.morimi.service.HarnessMetrics
import com.navoodi.morimi.ui.theme.MoColors
import com.navoodi.morimi.ui.theme.Pretendard

/**
 * 하네스 품질 대시보드 — 만족도·실존 확인율·할루시네이션율 추이와 임계선.
 *
 * 회의 피드백(2026-09-14)에 대한 답: "80%"를 감으로 말하지 않으려면 실행마다 측정치가 남고,
 * 그 추이가 임계선과 함께 보여야 한다. 표본 수를 항상 같이 표기해 **표본이 적다는 사실을
 * 숨기지 않는다** — 적은 표본으로 성능을 주장하지 않는 것이 이 화면의 목적 중 하나다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetricsScreen(
    navController: NavController,
    viewModel: MetricsViewModel = viewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    MetricsContent(ui = ui, onBack = { navController.popBackStack() })
}

/**
 * 상태를 **주입받는** 화면 본문 (2026-09-23 B4).
 *
 * [MetricsScreen]은 ViewModel을 기본 인자로 직접 만들기 때문에 계측 테스트에서 상태를
 * 꾸며 넣을 수 없었다. 동작은 그대로 두고 상태·콜백만 밖으로 뺀 순수 리팩터링이다
 * (`MetricsScreenUiTest`가 표본 0건·판정 불가·정상값 세 경우를 여기로 검증한다).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MetricsContent(
    ui: MetricsUiState,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MoColors.surfaceBase)
            .statusBarsPadding(),
    ) {
        TopAppBar(
            title = { Text("추천 품질 리포트", fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MoColors.surfaceBase),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            if (ui.sampleSize == 0) {
                EmptyState()
                return@Column
            }

            SampleBanner(ui.sampleSize, ui.ratedCount)
            Spacer(Modifier.height(16.dp))

            // ── 만족도 추이 + 임계선 ─────────────────────────────────────────
            SectionCard(
                title = "만족도 추이",
                subtitle = "후기 평점 ${'★'}1~5를 0~1로 환산. 점선은 만족 임계 " +
                    "${pct(HarnessMetrics.SATISFACTION_THRESHOLD)} (5점 척도 4점 = 만족)",
            ) {
                if (ui.satisfactionSeries.isEmpty()) {
                    Text(
                        "아직 평점이 달린 후기가 없습니다. 모임 후 후기 팝업에서 별점을 남기면 여기에 추이가 그려집니다.",
                        fontFamily = Pretendard, fontSize = 13.sp, color = MoColors.textTertiary,
                    )
                } else {
                    TrendChart(
                        series = ui.satisfactionSeries,
                        lineColor = MoColors.brand,
                        threshold = HarnessMetrics.SATISFACTION_THRESHOLD,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "평균 ${pct(ui.averageSatisfaction ?: 0.0)} · " +
                            if (ui.meetsThreshold) "임계 도달 ✓" else "임계 미달",
                        fontFamily = Pretendard, fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (ui.meetsThreshold) MoColors.place else MoColors.warningText,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 실존 확인율 / 할루시네이션율 / 확인률 / 제약 준수율 (D8 이름) ──────
            SectionCard(
                title = "검증 지표 추이",
                subtitle = "실존 확인율·할루시네이션율의 분모는 **판정 가능분**(검증+미발견)이다. " +
                    "확인불가는 어느 쪽에도 넣지 않고 확인률로만 드러낸다 — 장소 API 장애가 " +
                    "모델 성능 저하로 보이지 않게 하기 위해서다.",
            ) {
                TrendChart(
                    series = ui.verifiedRateSeries,
                    lineColor = MoColors.place,
                    secondarySeries = ui.hallucinationSeries,
                    secondaryColor = MoColors.warn,
                    tertiarySeries = ui.complianceSeries,
                    tertiaryColor = MoColors.activity,
                )
                Spacer(Modifier.height(10.dp))
                LegendRow("실존 확인율", MoColors.place, ui.averageVerifiedRate)
                LegendRow("할루시네이션율", MoColors.warn, ui.averageHallucination)
                LegendRow("제약 준수율", MoColors.activity, ui.averageCompliance)
                LegendRow("확인률", MoColors.brand, ui.averageCoverage)
            }

            Spacer(Modifier.height(12.dp))

            // ── 학습된 기준 가중치 ──────────────────────────────────────────
            SectionCard(
                title = "학습된 판단 기준 (AHP)",
                subtitle = "상황별 기준 가중치. CR은 판단의 일관성 비율 — 0.10 이상이면 그 학습은 기각된다",
            ) {
                if (ui.learnedWeights.isEmpty()) {
                    Text(
                        "아직 학습된 상황이 없습니다. 후기 평점이 쌓이면 상황마다 기준 가중치가 조정됩니다.",
                        fontFamily = Pretendard, fontSize = 13.sp, color = MoColors.textTertiary,
                    )
                } else {
                    ui.learnedWeights.forEach { row ->
                        WeightRow(row)
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── 구성 요소 ───────────────────────────────────────────────────────────────

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("📊", fontSize = 40.sp)
        Spacer(Modifier.height(12.dp))
        Text("아직 기록이 없습니다", fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "채팅방에서 ‘이야기 정리’로 추천을 받으면\n실행마다 품질 지표가 기록됩니다.",
            fontFamily = Pretendard, fontSize = 13.sp, color = MoColors.textTertiary,
        )
    }
}

/** 표본 수를 항상 먼저 보여준다 — 적은 표본으로 성능을 주장하지 않기 위해. */
@Composable
private fun SampleBanner(sampleSize: Int, ratedCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MoColors.brandSubtle)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("ℹ", fontSize = 16.sp, color = MoColors.brand)
        Spacer(Modifier.width(8.dp))
        Text(
            "표본 ${sampleSize}회 실행 (평점 있는 건 ${ratedCount}회). " +
                "표본이 적어 아직 일반화된 성능 수치로 읽을 수 없습니다.",
            fontFamily = Pretendard, fontSize = 12.sp, color = MoColors.textSecondary,
        )
    }
}

@Composable
private fun SectionCard(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MoColors.surfaceSubtle)
            .padding(14.dp),
    ) {
        Text(title, fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MoColors.textPrimary)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, fontFamily = Pretendard, fontSize = 11.sp, color = MoColors.textTertiary, lineHeight = 15.sp)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun LegendRow(label: String, color: Color, average: Double?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(7.dp))
        Text(label, fontFamily = Pretendard, fontSize = 12.sp, color = MoColors.textSecondary)
        Spacer(Modifier.weight(1f))
        Text(
            if (average == null) "판정 불가" else "평균 ${pct(average)}",
            fontFamily = Pretendard, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun WeightRow(row: LearnedWeightRow) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.contextLabel, fontFamily = Pretendard, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text(
                "CR ${"%.3f".format(row.cr)}" + if (row.learnedCount > 0) " · 학습 ${row.learnedCount}건" else "",
                fontFamily = Pretendard, fontSize = 11.sp,
                color = if (row.cr < 0.10) MoColors.textTertiary else MoColors.warningText,
            )
        }
        Spacer(Modifier.height(6.dp))
        row.weights.forEach { (label, weight) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                Text(label, fontFamily = Pretendard, fontSize = 11.sp, color = MoColors.textSecondary,
                    modifier = Modifier.width(66.dp))
                Box(
                    Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MoColors.border),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(weight.toFloat().coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(MoColors.brand),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(pct(weight), fontFamily = Pretendard, fontSize = 11.sp, modifier = Modifier.width(44.dp))
            }
        }
    }
}

/**
 * 0~1 값의 시계열 꺾은선. 최대 3계열 + 선택적 임계선.
 *
 * 차트 라이브러리를 새로 들이지 않고 Canvas로 직접 그린다 — 필요한 건 0~1 고정 축의
 * 꺾은선 하나뿐이라 의존성을 추가할 이유가 없다(CLAUDE.md: 의존성은 근거가 있을 때만).
 */
@Composable
private fun TrendChart(
    series: List<Double>,
    lineColor: Color,
    threshold: Double? = null,
    secondarySeries: List<Double> = emptyList(),
    secondaryColor: Color = Color.Transparent,
    tertiarySeries: List<Double> = emptyList(),
    tertiaryColor: Color = Color.Transparent,
) {
    val gridColor = MoColors.border
    val thresholdColor = MoColors.warningText

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp),
    ) {
        val w = size.width
        val h = size.height

        // 0 / 0.5 / 1.0 격자
        listOf(0f, 0.5f, 1f).forEach { level ->
            val y = h - h * level
            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }

        threshold?.let { t ->
            val y = h - h * t.toFloat()
            drawLine(
                color = thresholdColor,
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
            )
        }

        drawSeries(tertiarySeries, tertiaryColor, w, h)
        drawSeries(secondarySeries, secondaryColor, w, h)
        drawSeries(series, lineColor, w, h)
    }
}

/** 점이 하나뿐이면 선이 그려지지 않으므로 점만 찍는다(첫 실행에서도 뭔가 보이도록). */
private fun DrawScope.drawSeries(values: List<Double>, color: Color, w: Float, h: Float) {
    if (values.isEmpty() || color == Color.Transparent) return

    fun pointAt(i: Int): Offset {
        val x = if (values.size == 1) w / 2f else w * i / (values.size - 1).toFloat()
        val y = h - h * values[i].toFloat().coerceIn(0f, 1f)
        return Offset(x, y)
    }

    if (values.size >= 2) {
        val path = Path().apply {
            moveTo(pointAt(0).x, pointAt(0).y)
            for (i in 1 until values.size) lineTo(pointAt(i).x, pointAt(i).y)
        }
        drawPath(path, color, style = Stroke(width = 3f))
    }
    values.indices.forEach { i -> drawCircle(color, radius = 4f, center = pointAt(i)) }
}

private fun pct(v: Double): String = "${Math.round(v * 1000) / 10.0}%"

@Preview(showBackground = true)
@Composable
private fun WeightRowPreview() {
    WeightRow(
        LearnedWeightRow(
            contextLabel = "식사 모임",
            weights = AhpCriterion.entries.map { it.label to 0.2 },
            cr = 0.018,
            learnedCount = 2,
        )
    )
}
