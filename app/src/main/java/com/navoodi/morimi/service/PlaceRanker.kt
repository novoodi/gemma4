package com.navoodi.morimi.service

import com.navoodi.morimi.data.model.RecommendedPlace
import com.navoodi.morimi.data.model.VerificationStatus

/**
 * 후보 1곳의 AHP 종합 점수와 그 내역.
 *
 * [score]는 **대안 쌍대비교가 아니라** 기준별 0~1 결정론 루브릭의 가중합이다
 * (절대 평가 방식 — 근거는 [AhpEngine.synthesize] KDoc).
 *
 * [breakdown]을 함께 들고 다니는 이유는 "왜 이 집이 1등인가"를 사람이 검증할 수 있어야 하기
 * 때문이다 — 점수만 남기면 또 하나의 블랙박스가 된다.
 */
data class RankedPlace(
    val place: RecommendedPlace,
    val score: Double,
    val breakdown: Map<AhpCriterion, Double>,
    /**
     * 실제로 갈 수 있는 후보인가. 실존이 부정된 장소(NOT_FOUND)는 false.
     *
     * AHP 종합점수는 **실행 가능한 대안들 사이의 비교**로만 의미가 있다 — 존재하지 않는
     * 가게는 다른 기준을 아무리 잘 만족해도 대안이 아니므로, 점수로 겨루게 두지 않고
     * 먼저 걸러낸다(AHP의 사전 선별 단계). 그래서 정렬 키는 (실행가능, 점수) 순이다.
     */
    val feasible: Boolean = true,
) {
    /** "목적 적합 1.00 × 42.7%" 형태의 사람이 읽는 근거 문자열. */
    fun explain(ahp: AhpResult): String = ahp.ranked().joinToString(", ") { (c, w) ->
        "${c.label} ${fmt2(breakdown[c] ?: 0.0)}×${fmt1(w * 100)}%"
    }

    private fun fmt2(v: Double) = (Math.round(v * 100) / 100.0).toString()
    private fun fmt1(v: Double) = (Math.round(v * 10) / 10.0).toString()
}

/**
 * 과거 모임 후기 1건과 그 만족도 평점 — [AhpCriterion.PAST_SATISFACTION] 채점의 입력.
 *
 * 평점이 없는 후기(rating 0)는 좋았는지 나빴는지 알 수 없으므로 어느 쪽 증거로도 쓰지 않는다.
 */
data class PastImpression(val text: String, val rating: Int) {
    val isPositive: Boolean
        get() = rating > 0 && HarnessMetrics.satisfactionFromRating(rating) >= HarnessMetrics.SATISFACTION_THRESHOLD

    /** 명백한 불만만 부정 증거로 — 3점(보통)은 중립으로 남긴다. */
    val isNegative: Boolean get() = rating in 1..2
}

/**
 * **AHP 종합(synthesis) 단계** — 상황별 기준 가중치로 추천 후보를 정량 랭킹한다.
 *
 * 여기가 회의에서 지적된 "개인 선호를 평균 내면 의미가 없다"에 대한 답이다. 참가자 선호는
 * 5개 기준 중 하나([AhpCriterion.PREFERENCE_FIT])로만 들어가고, 최종 순위는 **그날 모임의
 * 목적**이 최상위 기준인 가중합으로 결정된다. 사람마다 취향이 갈려도 "오늘은 위로하는
 * 자리"라는 상황 기준이 남아 있어 결정이 가능해진다.
 *
 * 기준별 점수는 전부 **결정론적으로** 매긴다(LLM에게 점수를 물어보지 않는다) — 채점자가
 * 곧 채점 대상이면 검증이 성립하지 않기 때문. [PiiScrubber]·[GuardrailService]와 같은
 * "집행은 규칙 게이트가 한다" 계열.
 *
 * 순수 Kotlin — JVM 단위 테스트 가능(CLAUDE.md 컨벤션 #6).
 */
object PlaceRanker {

    /** 판단 근거가 아예 없을 때의 중립 점수 — 0으로 깎으면 근거 없음이 곧 벌점이 되어 왜곡된다. */
    private const val NEUTRAL = 0.5

    /**
     * 상황별 "이 업종이면 목적에 맞다" 토큰. 부분 문자열로 본다(카카오 카테고리 표기가 제각각).
     *
     * **한 글자 토큰은 쓰지 않는다** — "바"는 "바로"·"바다"에, "면"은 "반면"·"…이면"에 걸려
     * 아무 후보나 업종이 맞는 것처럼 만든다(부분 문자열 매칭의 함정).
     * 또 분위기 형용사("조용한", "아늑한")도 넣지 않는다 — 그건 업종이 아니라 취향이라
     * [AhpCriterion.PREFERENCE_FIT]이 볼 몫이다. 여기 섞으면 두 기준이 같은 신호를 이중으로 센다.
     */
    private val PURPOSE_TOKENS: Map<MeetingContext, List<String>> = mapOf(
        MeetingContext.MEAL to listOf("식당", "맛집", "레스토랑", "국밥", "고기", "삼겹", "파스타", "초밥", "마라", "국수", "라면", "밥집", "분식", "한식", "중식", "일식", "양식"),
        MeetingContext.DRINK to listOf("술집", "이자카야", "포차", "호프", "펍", "와인바", "맥주", "요리주점", "주점", "칵테일"),
        MeetingContext.CAFE to listOf("카페", "커피", "로스터", "디저트", "베이커리", "티룸", "브런치"),
        MeetingContext.CELEBRATION to listOf("레스토랑", "다이닝", "파티룸", "루프탑", "오마카세", "코스요리", "케이크", "브런치"),
        MeetingContext.CONSOLATION to listOf("포차", "술집", "식당", "펍", "주점", "이자카야"),
        MeetingContext.ACTIVITY to listOf("볼링", "노래", "방탈출", "보드게임", "당구", "영화", "전시", "공연", "클라이밍", "공원", "체험"),
        MeetingContext.TRIP to listOf("펜션", "숙소", "호텔", "리조트", "게스트하우스", "캠핑", "관광", "해변", "전망"),
        MeetingContext.STUDY to listOf("스터디", "스터디카페", "회의실", "라운지", "도서", "코워킹"),
        MeetingContext.GENERIC to emptyList(),
    )

    /**
     * 후보들을 AHP 가중합 점수 내림차순으로 정렬한다.
     *
     * @param places       Gemini가 낸 추천 장소(Guardrail 검증 상태가 병합된 상태여야 의미가 있다)
     * @param context      분류된 모임 상황
     * @param ahp          이 상황의 기준 가중치(학습 반영본)
     * @param preferences  성향 프로필 원본("좋아요:"/"싫어요:" 접두사 포함)
     * @param pastImpressions 과거 후기와 그 평점(RAG 회수분) — 과거 만족 기준의 근거
     */
    fun rank(
        places: List<RecommendedPlace>,
        context: MeetingContext,
        ahp: AhpResult,
        preferences: List<String>,
        pastImpressions: List<PastImpression> = emptyList(),
    ): List<RankedPlace> {
        if (places.isEmpty()) return emptyList()

        val likes = PromptSieve.likes(preferences)
        val dislikes = PromptSieve.dislikes(preferences)

        return places
            .map { place ->
                val text = KoTextMatch.placeText(place)
                val breakdown = mapOf(
                    AhpCriterion.PURPOSE_FIT to purposeFit(text, context),
                    AhpCriterion.PREFERENCE_FIT to preferenceFit(text, likes),
                    AhpCriterion.CONSTRAINT_SAFETY to constraintSafety(text, dislikes),
                    AhpCriterion.VERIFIED_TRUST to verifiedTrust(place.verification),
                    AhpCriterion.PAST_SATISFACTION to pastSatisfaction(text, pastImpressions),
                )
                RankedPlace(
                    place = place,
                    score = AhpEngine.synthesize(breakdown, ahp),
                    breakdown = breakdown,
                    feasible = place.verification != VerificationStatus.NOT_FOUND,
                )
            }
            // 실행 불가(실존 부정)는 무조건 뒤로 — 가중치 조합으로 되살아나지 못하게 한다.
            // 동점이면 원래 순서를 유지해야 결과가 실행마다 흔들리지 않는다(sortedWith는 안정 정렬)
            .sortedWith(compareByDescending<RankedPlace> { it.feasible }.thenByDescending { it.score })
    }

    /** 실제로 갈 수 있는 후보만 — 존재하지 않는 장소를 제외한 랭킹. */
    fun feasibleOnly(ranked: List<RankedPlace>): List<RankedPlace> = ranked.filter { it.feasible }

    /** 랭킹 결과를 [RecommendedPlace] 목록으로 되돌린다(요약 모델에 반영할 때). */
    fun reorder(ranked: List<RankedPlace>): List<RecommendedPlace> = ranked.map { it.place }

    // ── 기준별 채점 (전부 0.0~1.0) ───────────────────────────────────────────

    /** 목적 적합 — 업종 토큰이 맞으면 1.0, 다른 상황의 전용 업종이면 감점, 판단 불가면 중립. */
    internal fun purposeFit(text: String, context: MeetingContext): Double {
        val own = PURPOSE_TOKENS[context].orEmpty()
        if (own.isEmpty()) return NEUTRAL
        if (own.any { text.contains(it) }) return 1.0

        // 양립 가능한 상황의 업종은 이탈이 아니다(생일에 파스타집) — 중립으로 둔다.
        // 명백히 다른 목적의 업종만 감점한다(밥 약속에 술집, 스터디에 노래방).
        //
        // 양립 상황의 토큰은 **면제 목록**으로 따로 빼야 한다: "식당"은 CONSOLATION에도 있지만
        // MEAL에도 있어 축하 자리와 양립하므로, 어느 한쪽에 있다는 이유로 감점되면 안 된다.
        val compatible = context.compatibleContexts()
        val exempt = (own + compatible.flatMap { PURPOSE_TOKENS[it].orEmpty() }).toSet()
        val foreign = PURPOSE_TOKENS.entries
            .filter { it.key != context && it.key != MeetingContext.GENERIC && it.key !in compatible }
            .flatMap { it.value }
            .filter { it !in exempt }
        return if (foreign.any { text.contains(it) }) 0.2 else NEUTRAL
    }

    /** 취향 적합 — 좋아요 구절 중 몇 개가 이 후보에 나타나는가. 좋아요가 없으면 중립. */
    internal fun preferenceFit(text: String, likes: List<String>): Double {
        if (likes.isEmpty()) return NEUTRAL
        val hit = KoTextMatch.matching(text, likes).size
        // 하나도 못 맞히면 0이 아니라 하한 0.2 — 좋아요 미언급이 곧 나쁜 후보는 아니다
        return if (hit == 0) 0.2 else (hit.toDouble() / likes.size).coerceIn(0.2, 1.0)
    }

    /** 제약 준수 — 위반이면 0.0, 아니면 1.0. 가중합 감점이며 후보 제외 조건은 아니다. */
    internal fun constraintSafety(text: String, dislikes: List<String>): Double {
        if (dislikes.isEmpty()) return 1.0
        return if (KoTextMatch.matching(text, dislikes).isEmpty()) 1.0 else 0.0
    }

    /** 검증 신뢰 — 팩트 체크 결과. UNVERIFIED는 "검증됨"과 구분해 중간값(fail-open 금지). */
    internal fun verifiedTrust(status: VerificationStatus): Double = when (status) {
        VerificationStatus.VERIFIED -> 1.0
        VerificationStatus.UNVERIFIED -> NEUTRAL
        VerificationStatus.NOT_FOUND -> 0.0
    }

    /**
     * 과거 만족 — **좋았다고 평가한** 후기와 닮으면 가점, **나빴다고 평가한** 후기와 닮으면 감점.
     *
     * 평점을 보지 않고 후기 전체와의 유사도만 쓰면 "주차가 최악이었어" 같은 불만 후기가
     * 비슷한 장소의 점수를 **올려버린다** — 후기를 많이 쌓을수록 추천이 나빠지는 역설.
     * 그래서 만족 임계([HarnessMetrics.SATISFACTION_THRESHOLD]) 이상만 긍정 증거로,
     * 명백한 불만(2점 이하)만 부정 증거로 쓰고, 미평가·중립(3점)은 어느 쪽도 아니다.
     *
     * 평가된 후기가 없으면 중립(0.5) — 데이터가 없다는 사실이 벌점이 되면 안 된다.
     */
    internal fun pastSatisfaction(text: String, pastImpressions: List<PastImpression>): Double {
        if (pastImpressions.isEmpty()) return NEUTRAL
        val placeTokens = KoTextMatch.contentTokens(text).toSet()
        if (placeTokens.isEmpty()) return NEUTRAL

        val positives = pastImpressions.filter { it.isPositive }
        val negatives = pastImpressions.filter { it.isNegative }
        if (positives.isEmpty() && negatives.isEmpty()) return NEUTRAL

        fun bestOverlap(items: List<PastImpression>): Double =
            items.maxOfOrNull { jaccard(placeTokens, KoTextMatch.contentTokens(it.text).toSet()) } ?: 0.0

        // 자카드는 값이 작게 나오는 척도라 배율을 줘서 중립 위아래로 벌린다
        // (겹침 0 → 0.5 유지, 겹침 0.25 이상이면 상·하한에 도달)
        val delta = (bestOverlap(positives) - bestOverlap(negatives)) * 2.0
        return (NEUTRAL + delta).coerceIn(0.0, 1.0)
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { it in b }.toDouble()
        val union = (a.size + b.size - inter)
        return if (union <= 0.0) 0.0 else inter / union
    }
}
