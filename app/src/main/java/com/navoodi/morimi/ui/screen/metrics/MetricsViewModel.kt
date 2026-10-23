package com.navoodi.morimi.ui.screen.metrics

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.navoodi.morimi.MoimApp
import com.navoodi.morimi.service.MeetingContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 한 상황의 학습된 기준 가중치 한 줄. */
data class LearnedWeightRow(
    val contextLabel: String,
    val weights: List<Pair<String, Double>>,
    val cr: Double,
    val learnedCount: Int,
)

data class MetricsUiState(
    val sampleSize: Int = 0,
    val ratedCount: Int = 0,
    val satisfactionSeries: List<Double> = emptyList(),
    /** 판정 불가(null)인 실행은 계열에서 빠진다 — 0으로 채우면 API 장애가 성능 저하로 보인다 */
    val verifiedRateSeries: List<Double> = emptyList(),
    val hallucinationSeries: List<Double> = emptyList(),
    val coverageSeries: List<Double> = emptyList(),
    val complianceSeries: List<Double> = emptyList(),
    val averageSatisfaction: Double? = null,
    val averageVerifiedRate: Double? = null,
    val averageHallucination: Double? = null,
    val averageCoverage: Double? = null,
    val averageCompliance: Double? = null,
    val meetsThreshold: Boolean = false,
    val learnedWeights: List<LearnedWeightRow> = emptyList(),
)

/**
 * 품질 리포트 화면의 상태.
 *
 * 만족도 계열은 **평점이 달린 실행만** 모은다 — 미평가를 0으로 채우면 "쓸수록 나빠지는"
 * 가짜 하락 곡선이 그려진다. 표본 수는 UI에서 별도로 표기한다.
 */
class MetricsViewModel(application: Application) : AndroidViewModel(application) {

    private val metricsRepository = (application as MoimApp).metricsRepository

    private val _uiState = MutableStateFlow(MetricsUiState())
    val uiState: StateFlow<MetricsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val trend = metricsRepository.trend()
            val points = trend.points

            // 실행된 적 있는 상황만 보여준다(전 상황을 나열하면 학습 안 된 것까지 섞여 읽기 어렵다)
            val contexts: List<MeetingContext> = points.map { it.context }.distinct()
            val rows = contexts.map { ctx ->
                val learned = metricsRepository.weightsFor(ctx)
                LearnedWeightRow(
                    contextLabel = ctx.label,
                    weights = learned.result.ranked().map { (c, w) -> c.label to w },
                    cr = learned.result.cr,
                    learnedCount = learned.appliedDeltas.size,
                )
            }

            _uiState.value = MetricsUiState(
                sampleSize = trend.sampleSize,
                ratedCount = trend.ratedCount,
                satisfactionSeries = points.mapNotNull { it.satisfaction },
                verifiedRateSeries = points.mapNotNull { it.metrics.verifiedRate },
                hallucinationSeries = points.mapNotNull { it.metrics.hallucinationRate },
                coverageSeries = points.mapNotNull { it.metrics.coverage },
                complianceSeries = points.mapNotNull { it.metrics.constraintCompliance },
                averageSatisfaction = trend.averageSatisfaction,
                averageVerifiedRate = trend.averageVerifiedRate,
                averageHallucination = trend.averageHallucination,
                averageCoverage = trend.averageCoverage,
                averageCompliance = trend.averageCompliance,
                meetsThreshold = trend.meetsThreshold,
                learnedWeights = rows,
            )
        }
    }
}
