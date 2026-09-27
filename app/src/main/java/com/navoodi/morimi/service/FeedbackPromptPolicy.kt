package com.navoodi.morimi.service

/**
 * 후기 팝업을 띄울지 판정하는 규칙 — 순수 함수(CLAUDE.md 컨벤션 #6).
 *
 * **왜 바뀌었나**: 이전 조건은 `추천받은 방 && 후기가 하나도 없음`이었다. 한 방에서 추천을
 * 열 번 받아도 후기는 **평생 1건**만 수집돼, 만족도 추이 그래프가 방 개수만큼의 점밖에
 * 갖지 못했고 같은 상황에서 반복 학습도 불가능했다.
 *
 * **왜 `satisfaction IS NULL`만으로는 안 되나**: 별점은 선택 입력이라(0 = 미평가) 평점 없이
 * 후기만 쓰면 실행 행의 `satisfaction`이 NULL로 남는다. 그것만 보면 재진입할 때마다 팝업이
 * 다시 떠서, 이전 조건이 막으려던 반복 노출이 되살아난다. 그래서 **후기 작성 시각과
 * 최신 성공 실행 시각을 비교**한다.
 */
object FeedbackPromptPolicy {

    /**
     * @param wasRecommended      이 방이 추천을 받은 적 있는가 (`recommended_room`)
     * @param latestRunAt         이 방의 가장 최근 성공 실행 시각(epoch ms). 기록이 없으면 null
     * @param latestFeedbackAt    이 방의 가장 최근 후기 작성 시각(epoch ms). 후기가 없으면 null
     * @param hasAnyFeedback      이 방에 후기가 하나라도 있는가 (폴백 경로용)
     */
    fun shouldPrompt(
        wasRecommended: Boolean,
        latestRunAt: Long?,
        latestFeedbackAt: Long?,
        hasAnyFeedback: Boolean,
    ): Boolean {
        if (!wasRecommended) return false

        // v5 이전에 추천받은 방은 harness_run 기록이 없다 → 예전 조건으로 폴백.
        // (기록이 없다고 팝업을 계속 띄우면 과거 방들이 전부 다시 떠서 시끄러워진다)
        if (latestRunAt == null) return !hasAnyFeedback

        // 후기가 아예 없으면 띄운다
        val feedbackAt = latestFeedbackAt ?: return true

        // 마지막 후기가 최신 추천보다 **먼저**면 이번 추천에 대한 후기는 아직 없는 것.
        // createdAt이 0인 레거시 행(날짜 파싱 실패)도 자연히 "먼저"로 취급돼 한 번 더 뜬다.
        return feedbackAt < latestRunAt
    }
}
