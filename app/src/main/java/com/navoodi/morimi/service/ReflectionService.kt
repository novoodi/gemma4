package com.navoodi.morimi.service

import com.navoodi.morimi.data.model.RecommendedPlace

/** 발견된 제약 위반 1건 — 사용자가 싫어하는 요소가 추천의 어느 항목에 나타났는지. */
data class ConstraintViolation(
    val constraint: String,   // 불호 원문 (예: "조용한 카페")
    val matchedIn: String,    // 위반이 감지된 추천 항목 라벨 (예: "장소: 무브카페")
)

data class ReflectionResult(
    val passed: Boolean,
    val violations: List<ConstraintViolation>,
    val feedbackForRetry: String,
)

/**
 * 자기비평(Reflection) 검증 계층 — 추천이 사용자의 명시 제약(싫어요)을 위반하지 않는지
 * 결정론적으로 점검한다. [GuardrailService](장소 실존)와 나란히 하네스 재시도를 구동한다.
 *
 * 프롬프트에도 "선호/불호를 엄격히 반영"이 있지만 그것은 1차 방어일 뿐, 집행은 이 결정론적
 * 게이트가 한다 — [PiiScrubber]와 동일한 belt-and-suspenders 철학.
 *
 * 불호 신호는 Gemma 압축이 남긴 구조화 프로필(`preferences`의 "싫어요:" 접두사)에서만
 * 취한다 — 자유 텍스트 후기의 의미 판정은 오탐이 크므로 v1 범위에서 제외(향후 온디바이스
 * LLM 시맨틱 판정으로 확장 가능). 자유 후기는 여전히 Gemini 프롬프트에 소프트 가이드로 주입됨.
 *
 * 매칭은 [KoTextMatch]에 위임한다 — **정밀도 우선**: 불호 구절의 내용 토큰이 한 추천 항목
 * (장소명+이유+주소, 활동) 안에 모두 나타날 때만 위반으로 본다. [PlaceRanker]의 제약 채점과
 * 같은 규칙을 써야 "Reflection은 통과인데 랭킹은 감점" 같은 모순이 생기지 않는다. 부정문("시끄럽지 않은")·우연한 단일 토큰 일치로 인한
 * 오탐을 억제한다(재현율보다 정밀도 — 오탐은 불필요한 재시도·총 실패를 유발하므로).
 *
 * 순수 Kotlin(안드로이드 의존성 없음) — JVM 단위 테스트 가능(컨벤션 #6).
 */
object ReflectionService {

    // Gemma 압축 포맷의 불호 접두사 (LlmService 프롬프트 참조: "싫어요:" 등)

    /**
     * @param places      추천 장소 (이름·이유·주소가 매칭 대상)
     * @param activities  추천 활동
     * @param preferences 사용자 성향 프로필(userStatus.preferences) — "싫어요:" 항목만 사용
     *
     * 검사 대상은 **실제 추천 항목**(장소명+이유+주소, 활동)뿐이다. 요약문 같은 설명 산문은
     * 제외한다 — "술집은 제외했어요"처럼 회피한 항목을 서술할 때 토큰이 걸려 오탐이 되기 때문
     * (부정문 문제). 준수한 재시도가 서술 때문에 영영 통과 못 하는 상황을 막는다.
     */
    fun reflect(
        places: List<RecommendedPlace>,
        activities: List<String>,
        preferences: List<String>,
    ): ReflectionResult {
        val dislikes = extractDislikes(preferences)
        if (dislikes.isEmpty()) {
            return ReflectionResult(passed = true, violations = emptyList(), feedbackForRetry = "")
        }

        val segments = segmentsOf(places, activities)

        val violations = mutableListOf<ConstraintViolation>()
        for (dislike in dislikes) {
            val hit = segments.firstOrNull { (_, text) -> KoTextMatch.matches(text, dislike) }
            if (hit != null) {
                violations.add(ConstraintViolation(constraint = dislike, matchedIn = hit.first))
            }
        }

        val passed = violations.isEmpty()
        val feedback = if (passed) "" else buildString {
            append("사용자가 싫어하는 요소가 추천에 포함됐습니다. 아래 항목을 반드시 제외하고 다시 추천하세요:\n")
            violations.forEach { v ->
                appendLine("- 불호 '${v.constraint}' 가 ${v.matchedIn} 에서 감지됨")
            }
        }.trim()

        return ReflectionResult(passed = passed, violations = violations, feedbackForRetry = feedback)
    }

    /**
     * 추천을 라벨링된 항목 단위로 분해 — 위반은 "한 항목 안에서 모든 토큰 동시 등장"으로 판정.
     * `label to 매칭 대상 텍스트`.
     */
    private fun segmentsOf(
        places: List<RecommendedPlace>,
        activities: List<String>,
    ): List<Pair<String, String>> = buildList {
        places.forEach { p -> add("장소: ${p.name}" to KoTextMatch.placeText(p)) }
        activities.forEach { a -> add("활동" to a) }
    }

    /**
     * **제약 준수율 지표 전용** — 불호를 하나라도 건드린 **항목의 수**(중복 제거).
     *
     * [reflect]가 세는 [ReflectionResult.violations]는 "불호 구절당 최대 1건"이라 분자의 단위가
     * *구절*인데, 지표의 분모는 *항목 수*(장소+활동)다. 단위가 다르면 비율이 의미를 잃는다
     * — 한 항목이 불호 3개를 어기면 항목은 1개인데 위반은 3건이 되어 준수율이 음수 방향으로 튄다.
     * 그래서 지표는 이 함수(항목 단위)를 쓰고, [reflect]의 재시도 판정·피드백 문구는 그대로 둔다.
     *
     * 매칭 규칙은 [reflect]와 동일한 [KoTextMatch] 정밀도 우선 규칙을 재사용한다 —
     * 두 곳이 다른 규칙을 쓰면 "Reflection은 통과인데 지표는 위반"이 생긴다.
     */
    fun violatingItemCount(
        places: List<RecommendedPlace>,
        activities: List<String>,
        preferences: List<String>,
    ): Int {
        val dislikes = extractDislikes(preferences)
        if (dislikes.isEmpty()) return 0
        return segmentsOf(places, activities)
            .count { (_, text) -> dislikes.any { KoTextMatch.matches(text, it) } }
    }

    /**
     * preferences에서 불호 항목의 제약 텍스트를 추출.
     * 규칙은 [PreferenceEntries]에 있다 — 거름망과 같은 접두사 목록을 써야 판정이 어긋나지 않는다.
     */
    private fun extractDislikes(preferences: List<String>): List<String> =
        PreferenceEntries.dislikes(preferences)
}
