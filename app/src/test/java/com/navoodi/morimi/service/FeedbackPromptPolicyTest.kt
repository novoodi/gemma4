package com.navoodi.morimi.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 후기 팝업 조건 검증 (2026-09-23 C7).
 *
 * 핵심 회귀 방지 두 가지:
 *  - 추천을 두 번째로 받으면 후기를 **다시** 받을 수 있어야 한다(예전엔 방당 평생 1건)
 *  - 평점 없이 후기만 써도 팝업이 **반복되지 않아야** 한다(`satisfaction IS NULL`만 보면 반복된다)
 */
class FeedbackPromptPolicyTest {

    private val t0 = 1_000_000L   // 1회차 추천
    private val t1 = 2_000_000L   // 후기 작성
    private val t2 = 3_000_000L   // 2회차 추천

    @Test
    fun `추천 1회 후기 없음 - 표시`() {
        assertTrue(
            FeedbackPromptPolicy.shouldPrompt(
                wasRecommended = true, latestRunAt = t0,
                latestFeedbackAt = null, hasAnyFeedback = false,
            )
        )
    }

    @Test
    fun `추천 1회 후기 작성 - 미표시`() {
        assertFalse(
            FeedbackPromptPolicy.shouldPrompt(
                wasRecommended = true, latestRunAt = t0,
                latestFeedbackAt = t1, hasAnyFeedback = true,
            )
        )
    }

    @Test
    fun `후기 후 두 번째 추천 - 다시 표시`() {
        assertTrue(
            FeedbackPromptPolicy.shouldPrompt(
                wasRecommended = true, latestRunAt = t2,
                latestFeedbackAt = t1, hasAnyFeedback = true,
            )
        )
    }

    @Test
    fun `두 번째 추천 후 평점 없이 후기만 써도 반복되지 않는다`() {
        // 평점이 없어도 후기 작성 시각이 최신 추천보다 뒤면 이번 건은 받은 것으로 본다.
        // (satisfaction IS NULL만 보면 여기서 팝업이 계속 떴다)
        assertFalse(
            FeedbackPromptPolicy.shouldPrompt(
                wasRecommended = true, latestRunAt = t2,
                latestFeedbackAt = t2 + 1, hasAnyFeedback = true,
            )
        )
    }

    @Test
    fun `v5 이전 방 - harness_run 기록 없으면 예전 조건으로 폴백`() {
        // 후기 없음 → 표시
        assertTrue(
            FeedbackPromptPolicy.shouldPrompt(
                wasRecommended = true, latestRunAt = null,
                latestFeedbackAt = null, hasAnyFeedback = false,
            )
        )
        // 후기 있음 → 미표시 (기록이 없다고 계속 띄우면 과거 방들이 전부 다시 뜬다)
        assertFalse(
            FeedbackPromptPolicy.shouldPrompt(
                wasRecommended = true, latestRunAt = null,
                latestFeedbackAt = t1, hasAnyFeedback = true,
            )
        )
    }

    @Test
    fun `추천받은 적 없는 방은 어떤 경우에도 미표시`() {
        assertFalse(
            FeedbackPromptPolicy.shouldPrompt(
                wasRecommended = false, latestRunAt = t2,
                latestFeedbackAt = null, hasAnyFeedback = false,
            )
        )
    }

    @Test
    fun `createdAt이 0인 레거시 후기는 한 번 더 뜬다`() {
        // 마이그레이션에서 날짜 파싱에 실패해 0으로 남은 행 — 최신 추천보다 '먼저'로 취급된다
        assertTrue(
            FeedbackPromptPolicy.shouldPrompt(
                wasRecommended = true, latestRunAt = t2,
                latestFeedbackAt = 0L, hasAnyFeedback = true,
            )
        )
    }
}
