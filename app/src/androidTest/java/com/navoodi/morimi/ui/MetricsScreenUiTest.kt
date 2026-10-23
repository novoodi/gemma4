package com.navoodi.morimi.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.navoodi.morimi.ui.screen.metrics.LearnedWeightRow
import com.navoodi.morimi.ui.screen.metrics.MetricsContent
import com.navoodi.morimi.ui.screen.metrics.MetricsUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **품질 리포트 화면 계측 테스트 (2026-09-23 B4, 에뮬레이터 API 36 x86_64).**
 *
 * D1·D8 결정이 화면에 실제로 반영됐는지는 그동안 코드로만 확인했다. 여기서는 상태를
 * 주입해 **사람이 보게 될 문구**를 검증한다 — 특히 두 가지:
 *
 *  1. **표본 수가 먼저 보인다.** 표본이 적은 상태에서 비율만 크게 띄우면 일반화된 성능으로
 *     읽힌다(측정·주장 원칙).
 *  2. **판정 불가와 0%가 구분된다.** 장소 API 키가 없으면 전부 UNVERIFIED가 되는데,
 *     그때 "0%"로 보이면 모델이 나쁜 것처럼 읽힌다. D1이 막으려던 바로 그 오독이다.
 *
 * 화면이 ViewModel을 직접 만들던 구조라 테스트가 불가능했다 → `MetricsContent`로
 * 상태를 받는 본문을 분리했다(P5, 동작 불변).
 */
@RunWith(AndroidJUnit4::class)
class MetricsScreenUiTest {

    @get:Rule
    val compose = createComposeRule()

    /** 정상 값 한 벌 — 세 번째 경우에서 쓴다. */
    private fun normalState() = MetricsUiState(
        sampleSize = 4,
        ratedCount = 3,
        satisfactionSeries = listOf(0.4, 0.6, 0.8),
        verifiedRateSeries = listOf(1.0, 0.5, 1.0),
        hallucinationSeries = listOf(0.0, 0.5, 0.0),
        coverageSeries = listOf(1.0, 0.667, 1.0),
        complianceSeries = listOf(1.0, 0.667, 1.0),
        averageSatisfaction = 0.6,
        averageVerifiedRate = 0.833,
        averageHallucination = 0.167,
        averageCoverage = 0.889,
        averageCompliance = 0.889,
        meetsThreshold = false,
        learnedWeights = listOf(
            LearnedWeightRow(
                contextLabel = "식사",
                weights = listOf("목적 적합" to 0.427, "취향 적합" to 0.234),
                cr = 0.0177,
                learnedCount = 1,
            ),
        ),
    )

    @Test
    fun 표본이_0건이면_빈_상태를_보여주고_비율을_띄우지_않는다() {
        compose.setContent { MetricsContent(ui = MetricsUiState(), onBack = {}) }

        // 표본이 없으면 그럴듯한 0%를 그리지 않는다 — 없는 성능을 말하지 않기 위해서다
        compose.onAllNodesWithText("0%", substring = true).fetchSemanticsNodes().let {
            assertTrue("표본 0건인데 비율을 그렸다", it.isEmpty())
        }
    }

    @Test
    fun 전부_판정불가면_0퍼센트가_아니라_판정_불가로_표시된다() {
        // 카카오 키가 없을 때의 상태: 판정 가능분이 0건이라 비율이 null이다
        val allUnverified = MetricsUiState(
            sampleSize = 2,
            ratedCount = 1,
            satisfactionSeries = listOf(0.6),
            averageSatisfaction = 0.6,
            averageVerifiedRate = null,     // 판정 불가
            averageHallucination = null,    // 판정 불가
            averageCoverage = 0.0,          // 확인률은 0%가 맞다 (분모는 후보 수)
            averageCompliance = 1.0,
        )
        compose.setContent { MetricsContent(ui = allUnverified, onBack = {}) }

        compose.onAllNodesWithText("판정 불가", substring = true).fetchSemanticsNodes().let {
            assertTrue("판정 불가 표시가 없다 — 0%로 보이면 D1이 막으려던 오독이 난다", it.isNotEmpty())
        }
    }

    @Test
    fun 새_지표_이름이_보이고_옛_이름은_없다() {
        compose.setContent { MetricsContent(ui = normalState(), onBack = {}) }

        // D8에서 정리한 이름. 범례와 설명문 양쪽에 나오므로 "정확히 1개"를 요구하지 않는다
        listOf("실존 확인율", "할루시네이션율", "확인률", "제약 준수율").forEach { label ->
            compose.onAllNodesWithText(label, substring = true).fetchSemanticsNodes().let {
                assertTrue("새 지표 이름이 화면에 없다: $label", it.isNotEmpty())
            }
        }
        // 옛 이름은 남아 있으면 안 된다. "적합도"는 AHP 기준명(목적 적합·취향 적합)과 혼동된다
        listOf("정확도", "적합도").forEach { old ->
            compose.onAllNodesWithText(old, substring = false).fetchSemanticsNodes().let {
                assertTrue("옛 지표 이름이 남아 있다: $old", it.isEmpty())
            }
        }
    }

    @Test
    fun 표본_수가_화면에_표기된다() {
        compose.setContent { MetricsContent(ui = normalState(), onBack = {}) }
        // 측정·주장 원칙: 지표를 쓸 때는 표본 수를 함께 표기한다
        compose.onAllNodesWithText("4", substring = true).fetchSemanticsNodes().let {
            assertTrue("표본 수(4)가 화면에 없다", it.isNotEmpty())
        }
    }
}
