package com.navoodi.morimi.service

import com.navoodi.morimi.data.model.Message

/**
 * 모임 상황(context) 분류 체계.
 *
 * "모임"을 하나로 뭉뚱그리면 밥 약속과 술 약속에 같은 장소가 추천된다 — 실제로는 목적이
 * 다르면 좋은 장소도, 무엇을 중요하게 볼지도 달라진다. 그래서 상황을 먼저 유한한 케이스로
 * 나누고, 케이스마다 **정형화 프레임**([PromptSieve])과 **기준 가중치**(AHP 쌍대비교 프리셋)를
 * 따로 가진다.
 *
 * 모든 상황을 다 덮을 수는 없으므로 미분류는 [GENERIC]으로 흘려보낸다(하드 실패 없음).
 *
 * @property placeQueryHint  searchPlace 검색어에 덧붙일 업종 힌트
 * @property judgments       이 상황의 AHP 쌍대비교 상삼각 판단 (i가 j보다 몇 배 중요한가)
 */
enum class MeetingContext(
    val label: String,
    val purpose: String,
    val placeQueryHint: String,
    private val judgments: Map<Pair<AhpCriterion, AhpCriterion>, Double>,
) {
    MEAL(
        label = "식사 모임",
        purpose = "함께 밥을 먹으며 이야기하는 자리",
        placeQueryHint = "식당 맛집",
        judgments = mapOf(
            (AhpCriterion.PURPOSE_FIT to AhpCriterion.PREFERENCE_FIT) to 2.0,
            (AhpCriterion.PURPOSE_FIT to AhpCriterion.CONSTRAINT_SAFETY) to 4.0,
            (AhpCriterion.PURPOSE_FIT to AhpCriterion.VERIFIED_TRUST) to 3.0,
            (AhpCriterion.PURPOSE_FIT to AhpCriterion.PAST_SATISFACTION) to 5.0,
            (AhpCriterion.PREFERENCE_FIT to AhpCriterion.CONSTRAINT_SAFETY) to 2.0,
            (AhpCriterion.PREFERENCE_FIT to AhpCriterion.VERIFIED_TRUST) to 2.0,
            (AhpCriterion.PREFERENCE_FIT to AhpCriterion.PAST_SATISFACTION) to 3.0,
            (AhpCriterion.CONSTRAINT_SAFETY to AhpCriterion.VERIFIED_TRUST) to 0.5,
            (AhpCriterion.CONSTRAINT_SAFETY to AhpCriterion.PAST_SATISFACTION) to 2.0,
            (AhpCriterion.VERIFIED_TRUST to AhpCriterion.PAST_SATISFACTION) to 3.0,
        ),
    ),
    DRINK(
        label = "술자리",
        purpose = "술을 곁들이며 편하게 이야기하는 자리",
        placeQueryHint = "술집 이자카야 포차",
        // 술자리는 못 마시는 참가자가 있으면 그 제약이 목적만큼 중요해진다 → CONSTRAINT_SAFETY 상향
        judgments = mapOf(
            (AhpCriterion.PURPOSE_FIT to AhpCriterion.PREFERENCE_FIT) to 3.0,
            (AhpCriterion.PURPOSE_FIT to AhpCriterion.CONSTRAINT_SAFETY) to 1.0,
            (AhpCriterion.PURPOSE_FIT to AhpCriterion.VERIFIED_TRUST) to 4.0,
            (AhpCriterion.PURPOSE_FIT to AhpCriterion.PAST_SATISFACTION) to 5.0,
            (AhpCriterion.PREFERENCE_FIT to AhpCriterion.CONSTRAINT_SAFETY) to 0.5,
            (AhpCriterion.PREFERENCE_FIT to AhpCriterion.VERIFIED_TRUST) to 2.0,
            (AhpCriterion.PREFERENCE_FIT to AhpCriterion.PAST_SATISFACTION) to 3.0,
            (AhpCriterion.CONSTRAINT_SAFETY to AhpCriterion.VERIFIED_TRUST) to 4.0,
            (AhpCriterion.CONSTRAINT_SAFETY to AhpCriterion.PAST_SATISFACTION) to 5.0,
            (AhpCriterion.VERIFIED_TRUST to AhpCriterion.PAST_SATISFACTION) to 2.0,
        ),
    ),
    CAFE(
        label = "카페·대화",
        purpose = "조용히 앉아 오래 이야기하는 자리",
        placeQueryHint = "카페",
        // 대화가 목적이라 시끄러움 같은 불호 위반이 치명적 → CONSTRAINT_SAFETY 최상
        judgments = mapOf(
            (AhpCriterion.PURPOSE_FIT to AhpCriterion.PREFERENCE_FIT) to 0.5,
            (AhpCriterion.PURPOSE_FIT to AhpCriterion.CONSTRAINT_SAFETY) to 1.0 / 3.0,
            (AhpCriterion.PURPOSE_FIT to AhpCriterion.VERIFIED_TRUST) to 2.0,
            (AhpCriterion.PURPOSE_FIT to AhpCriterion.PAST_SATISFACTION) to 2.0,
            (AhpCriterion.PREFERENCE_FIT to AhpCriterion.CONSTRAINT_SAFETY) to 0.5,
            (AhpCriterion.PREFERENCE_FIT to AhpCriterion.VERIFIED_TRUST) to 3.0,
            (AhpCriterion.PREFERENCE_FIT to AhpCriterion.PAST_SATISFACTION) to 3.0,
            (AhpCriterion.CONSTRAINT_SAFETY to AhpCriterion.VERIFIED_TRUST) to 4.0,
            (AhpCriterion.CONSTRAINT_SAFETY to AhpCriterion.PAST_SATISFACTION) to 4.0,
            (AhpCriterion.VERIFIED_TRUST to AhpCriterion.PAST_SATISFACTION) to 1.0,
        ),
    ),
    CELEBRATION(
        label = "축하·기념",
        purpose = "생일·합격 등을 축하하는 자리",
        placeQueryHint = "분위기 좋은 레스토랑 파티룸",
        // 축하는 전에 좋았던 곳을 다시 찾는 성향이 강함 → PAST_SATISFACTION 상향
        judgments = mapOf(
            (AhpCriterion.PURPOSE_FIT to AhpCriterion.PREFERENCE_FIT) to 3.0,
            (AhpCriterion.PURPOSE_FIT to AhpCriterion.CONSTRAINT_SAFETY) to 5.0,
            (AhpCriterion.PURPOSE_FIT to AhpCriterion.VERIFIED_TRUST) to 3.0,
            (AhpCriterion.PURPOSE_FIT to AhpCriterion.PAST_SATISFACTION) to 2.0,
            (AhpCriterion.PREFERENCE_FIT to AhpCriterion.CONSTRAINT_SAFETY) to 2.0,
            (AhpCriterion.PREFERENCE_FIT to AhpCriterion.VERIFIED_TRUST) to 1.0,
            (AhpCriterion.PREFERENCE_FIT to AhpCriterion.PAST_SATISFACTION) to 0.5,
            (AhpCriterion.CONSTRAINT_SAFETY to AhpCriterion.VERIFIED_TRUST) to 0.5,
            (AhpCriterion.CONSTRAINT_SAFETY to AhpCriterion.PAST_SATISFACTION) to 1.0 / 3.0,
            (AhpCriterion.VERIFIED_TRUST to AhpCriterion.PAST_SATISFACTION) to 0.5,
        ),
    ),
    CONSOLATION(
        label = "위로·고민 상담",
        purpose = "힘든 일을 겪은 사람을 위로하는 자리",
        placeQueryHint = "조용한 술집 조용한 식당",
        // 위로 자리에서 시끄럽거나 불편한 요소는 목적 자체를 깨뜨림 → CONSTRAINT_SAFETY 최상
        judgments = mapOf(
            (AhpCriterion.PURPOSE_FIT to AhpCriterion.PREFERENCE_FIT) to 3.0,
            (AhpCriterion.PURPOSE_FIT to AhpCriterion.CONSTRAINT_SAFETY) to 0.5,
            (AhpCriterion.PURPOSE_FIT to AhpCriterion.VERIFIED_TRUST) to 4.0,
            (AhpCriterion.PURPOSE_FIT to AhpCriterion.PAST_SATISFACTION) to 4.0,
            (AhpCriterion.PREFERENCE_FIT to AhpCriterion.CONSTRAINT_SAFETY) to 0.25,
            (AhpCriterion.PREFERENCE_FIT to AhpCriterion.VERIFIED_TRUST) to 2.0,
            (AhpCriterion.PREFERENCE_FIT to AhpCriterion.PAST_SATISFACTION) to 2.0,
            (AhpCriterion.CONSTRAINT_SAFETY to AhpCriterion.VERIFIED_TRUST) to 5.0,
            (AhpCriterion.CONSTRAINT_SAFETY to AhpCriterion.PAST_SATISFACTION) to 5.0,
            (AhpCriterion.VERIFIED_TRUST to AhpCriterion.PAST_SATISFACTION) to 1.0,
        ),
    ),
    ACTIVITY(
        label = "액티비티·나들이",
        purpose = "같이 무언가를 하며 노는 자리",
        placeQueryHint = "볼링장 보드게임카페 전시 공연",
        // 문 닫은 시설로 몰려가면 모임 자체가 무산 → VERIFIED_TRUST 최상
        judgments = mapOf(
            (AhpCriterion.PURPOSE_FIT to AhpCriterion.PREFERENCE_FIT) to 2.0,
            (AhpCriterion.PURPOSE_FIT to AhpCriterion.CONSTRAINT_SAFETY) to 3.0,
            (AhpCriterion.PURPOSE_FIT to AhpCriterion.VERIFIED_TRUST) to 0.5,
            (AhpCriterion.PURPOSE_FIT to AhpCriterion.PAST_SATISFACTION) to 4.0,
            (AhpCriterion.PREFERENCE_FIT to AhpCriterion.CONSTRAINT_SAFETY) to 2.0,
            (AhpCriterion.PREFERENCE_FIT to AhpCriterion.VERIFIED_TRUST) to 1.0 / 3.0,
            (AhpCriterion.PREFERENCE_FIT to AhpCriterion.PAST_SATISFACTION) to 2.0,
            (AhpCriterion.CONSTRAINT_SAFETY to AhpCriterion.VERIFIED_TRUST) to 0.25,
            (AhpCriterion.CONSTRAINT_SAFETY to AhpCriterion.PAST_SATISFACTION) to 2.0,
            (AhpCriterion.VERIFIED_TRUST to AhpCriterion.PAST_SATISFACTION) to 5.0,
        ),
    ),
    TRIP(
        label = "여행·1박",
        purpose = "이동·숙박을 동반한 여행",
        placeQueryHint = "숙소 펜션 관광지",
        judgments = mapOf(
            (AhpCriterion.PURPOSE_FIT to AhpCriterion.PREFERENCE_FIT) to 2.0,
            (AhpCriterion.PURPOSE_FIT to AhpCriterion.CONSTRAINT_SAFETY) to 3.0,
            (AhpCriterion.PURPOSE_FIT to AhpCriterion.VERIFIED_TRUST) to 0.5,
            (AhpCriterion.PURPOSE_FIT to AhpCriterion.PAST_SATISFACTION) to 0.5,
            (AhpCriterion.PREFERENCE_FIT to AhpCriterion.CONSTRAINT_SAFETY) to 2.0,
            (AhpCriterion.PREFERENCE_FIT to AhpCriterion.VERIFIED_TRUST) to 1.0 / 3.0,
            (AhpCriterion.PREFERENCE_FIT to AhpCriterion.PAST_SATISFACTION) to 1.0 / 3.0,
            (AhpCriterion.CONSTRAINT_SAFETY to AhpCriterion.VERIFIED_TRUST) to 0.25,
            (AhpCriterion.CONSTRAINT_SAFETY to AhpCriterion.PAST_SATISFACTION) to 0.25,
            (AhpCriterion.VERIFIED_TRUST to AhpCriterion.PAST_SATISFACTION) to 1.0,
        ),
    ),
    STUDY(
        label = "스터디·작업",
        purpose = "공부·회의처럼 집중이 필요한 자리",
        placeQueryHint = "스터디카페 조용한 카페 회의실",
        judgments = mapOf(
            (AhpCriterion.PURPOSE_FIT to AhpCriterion.PREFERENCE_FIT) to 4.0,
            (AhpCriterion.PURPOSE_FIT to AhpCriterion.CONSTRAINT_SAFETY) to 2.0,
            (AhpCriterion.PURPOSE_FIT to AhpCriterion.VERIFIED_TRUST) to 3.0,
            (AhpCriterion.PURPOSE_FIT to AhpCriterion.PAST_SATISFACTION) to 5.0,
            (AhpCriterion.PREFERENCE_FIT to AhpCriterion.CONSTRAINT_SAFETY) to 1.0 / 3.0,
            (AhpCriterion.PREFERENCE_FIT to AhpCriterion.VERIFIED_TRUST) to 0.5,
            (AhpCriterion.PREFERENCE_FIT to AhpCriterion.PAST_SATISFACTION) to 2.0,
            (AhpCriterion.CONSTRAINT_SAFETY to AhpCriterion.VERIFIED_TRUST) to 2.0,
            (AhpCriterion.CONSTRAINT_SAFETY to AhpCriterion.PAST_SATISFACTION) to 3.0,
            (AhpCriterion.VERIFIED_TRUST to AhpCriterion.PAST_SATISFACTION) to 2.0,
        ),
    ),
    GENERIC(
        label = "일반 모임",
        purpose = "목적이 아직 뚜렷하지 않은 모임",
        placeQueryHint = "모임 장소",
        // 신호가 약할 때의 기본값 — 특정 기준으로 치우치지 않게 상위 3기준을 동등하게 둔다
        judgments = mapOf(
            (AhpCriterion.PURPOSE_FIT to AhpCriterion.PREFERENCE_FIT) to 1.0,
            (AhpCriterion.PURPOSE_FIT to AhpCriterion.CONSTRAINT_SAFETY) to 1.0,
            (AhpCriterion.PURPOSE_FIT to AhpCriterion.VERIFIED_TRUST) to 2.0,
            (AhpCriterion.PURPOSE_FIT to AhpCriterion.PAST_SATISFACTION) to 3.0,
            (AhpCriterion.PREFERENCE_FIT to AhpCriterion.CONSTRAINT_SAFETY) to 1.0,
            (AhpCriterion.PREFERENCE_FIT to AhpCriterion.VERIFIED_TRUST) to 2.0,
            (AhpCriterion.PREFERENCE_FIT to AhpCriterion.PAST_SATISFACTION) to 3.0,
            (AhpCriterion.CONSTRAINT_SAFETY to AhpCriterion.VERIFIED_TRUST) to 2.0,
            (AhpCriterion.CONSTRAINT_SAFETY to AhpCriterion.PAST_SATISFACTION) to 3.0,
            (AhpCriterion.VERIFIED_TRUST to AhpCriterion.PAST_SATISFACTION) to 2.0,
        ),
    );

    /** 이 상황의 기본 쌍대비교 행렬 — 학습 보정([AhpJudgmentLearner])의 출발점. */
    fun basePairwiseMatrix(): PairwiseMatrix = PairwiseMatrix.of(judgments = judgments)

    /**
     * 이 상황과 **양립 가능한** 상황들 — 업종이 겹쳐도 목적에서 벗어난 것으로 보지 않는다.
     *
     * 상황이 서로 배타적이라고 가정하면 "생일에 파스타집"이 목적 이탈로 감점된다. 실제로는
     * 축하 자리에서 식사·술·카페가 모두 성립하고, 갈리는 건 업종이 아니라 분위기다.
     * 반대로 밥 약속에 술집, 스터디에 노래방은 명백한 이탈이라 그대로 감점한다.
     * ([PlaceRanker.purposeFit] 채점에 쓰인다)
     *
     * enum 상수를 생성자에서 서로 참조할 수 없어 함수로 둔다.
     */
    fun compatibleContexts(): Set<MeetingContext> = when (this) {
        CELEBRATION -> setOf(MEAL, DRINK, CAFE)   // 축하는 밥·술·카페 어디서든 성립
        CONSOLATION -> setOf(DRINK, MEAL, CAFE)   // 위로도 마찬가지 — 관건은 조용함이지 업종이 아니다
        TRIP -> setOf(MEAL, ACTIVITY)             // 여행 일정엔 식사와 액티비티가 섞인다
        MEAL -> setOf(CAFE)                       // 브런치카페처럼 경계가 흐린 경우
        CAFE -> setOf(MEAL)
        DRINK -> setOf(MEAL)                      // 안주 중심 식당은 술자리로도 쓰인다
        STUDY -> setOf(CAFE)                      // 스터디카페/카페 작업
        ACTIVITY -> setOf(MEAL)
        GENERIC -> entries.toSet()                // 목적이 불분명하면 무엇도 이탈로 보지 않는다
    }
}

/**
 * 상황 분류 결과.
 *
 * @property confidence 0~1. 1위 점수가 전체 신호에서 차지하는 비중(2위가 붙을수록 낮아짐).
 *                      낮으면 프레임을 강하게 밀지 않는다.
 * @property signals    분류 근거가 된 키워드 — 판정을 사람이 검증할 수 있게 남긴다(관찰 가능성).
 */
data class ContextClassification(
    val context: MeetingContext,
    val confidence: Double,
    val signals: List<String>,
    val runnerUp: MeetingContext?,
    val scores: Map<MeetingContext, Int>,
) {
    val isConfident: Boolean get() = confidence >= ContextClassifier.MIN_CONFIDENCE
}

/**
 * 결정론적 상황 분류기 — 채팅에서 모임의 **목적**을 유한한 [MeetingContext] 케이스로 판정한다.
 *
 * **온디바이스에서 원문을 읽는다.** 분류는 기기 안에서 끝나고 디바이스 경계를 넘는 것은
 * 상황 라벨 하나뿐이라 프라이버시 방화벽을 넓히지 않는다(요약문보다 오히려 정보량이 적다).
 *
 * 방식은 **가중 키워드 투표 + 부정 표현 억제**다. 온디바이스 LLM에 한 번 더 물어보는 방법도
 * 있으나, (a) 분류는 재시도·검증의 **입력**이라 비결정적이면 하네스 전체가 흔들리고,
 * (b) 압축·요약으로 이미 Gemma를 2회 호출해 지연이 누적되며, (c) 규칙은 JVM 단위 테스트로
 * 회귀를 막을 수 있다 — 그래서 v1은 결정론 규칙으로 간다.
 * (프롬프트 지시가 아니라 결정론적 게이트가 집행한다는 [PiiScrubber] 철학과 동일선상)
 *
 * 순수 Kotlin(안드로이드 의존성 없음) — JVM 단위 테스트 가능(CLAUDE.md 컨벤션 #6).
 */
object ContextClassifier {

    /** 이 미만이면 신호가 약하다고 보고 프레임을 느슨하게 적용한다. */
    const val MIN_CONFIDENCE = 0.34

    /** 1위 점수가 이 값 미만이면 아예 [MeetingContext.GENERIC]으로 떨어뜨린다. */
    private const val MIN_TOP_SCORE = 3

    /** 같은 키워드 반복으로 점수가 무한정 커지지 않게 세는 횟수를 이 값에서 포화시킨다. */
    private const val HIT_SATURATION = 2

    /** 부정 표현 탐지 창 — 키워드 뒤 이만큼 안에 부정어가 있으면 그 등장을 세지 않는다. */
    /** 상황별 키워드와 가중치. 3=결정적, 2=강함, 1=약한 방증. */
    private val LEXICON: Map<MeetingContext, Map<String, Int>> = mapOf(
        MeetingContext.DRINK to mapOf(
            "술집" to 3, "술자리" to 3, "이자카야" to 3, "포차" to 3, "호프" to 3, "소주" to 3,
            "맥주" to 3, "하이볼" to 3, "막걸리" to 3, "와인바" to 3, "회식" to 3,
            "술" to 2, "한잔" to 2, "안주" to 2, "치맥" to 2, "건배" to 2, "취하" to 2,
            "2차" to 2, "칵테일" to 2,
        ),
        MeetingContext.MEAL to mapOf(
            "맛집" to 3, "식당" to 3, "저녁 먹" to 3, "점심 먹" to 3, "밥 먹" to 3, "식사" to 3,
            "고기" to 2, "삼겹살" to 2, "파스타" to 2, "마라탕" to 2, "국밥" to 2, "초밥" to 2,
            "피자" to 2, "한식" to 2, "중식" to 2, "일식" to 2, "양식" to 2, "치킨" to 2,
            "먹으러" to 2, "밥" to 1, "배고" to 1, "메뉴" to 1,
        ),
        MeetingContext.CAFE to mapOf(
            "카페" to 3, "커피" to 3, "아메리카노" to 2, "디저트" to 2, "케이크" to 2,
            "브런치" to 2, "수다" to 2, "이야기하" to 2, "얘기하" to 2, "조용한 곳" to 2,
            "차 마시" to 2, "앉아서" to 1,
        ),
        MeetingContext.CELEBRATION to mapOf(
            "생일" to 3, "축하" to 3, "기념일" to 3, "파티" to 3, "합격" to 3, "졸업" to 3,
            "승진" to 3, "축하해" to 3, "돌잔치" to 3, "집들이" to 3, "취업" to 2, "선물" to 2,
        ),
        MeetingContext.CONSOLATION to mapOf(
            "위로" to 3, "힘들" to 3, "속상" to 3, "우울" to 3, "이별" to 3, "헤어졌" to 3,
            "차였" to 3, "불합격" to 3, "떨어졌" to 3, "퇴사" to 3, "잘렸" to 3, "시련" to 3,
            "멘탈" to 2, "고민" to 2, "상담" to 2, "달래" to 2, "울적" to 2,
        ),
        MeetingContext.ACTIVITY to mapOf(
            "볼링" to 3, "노래방" to 3, "방탈출" to 3, "보드게임" to 3, "당구" to 3, "피시방" to 3,
            "pc방" to 3, "영화" to 3, "전시" to 3, "공연" to 3, "놀이공원" to 3, "등산" to 3,
            "피크닉" to 3, "클라이밍" to 3, "야구장" to 3, "산책" to 2, "자전거" to 2, "축구" to 2,
        ),
        MeetingContext.TRIP to mapOf(
            "여행" to 3, "숙소" to 3, "펜션" to 3, "1박" to 3, "2박" to 3, "캠핑" to 3,
            "글램핑" to 3, "게스트하우스" to 3, "당일치기" to 3, "놀러가" to 2, "제주" to 2,
            "강릉" to 2, "바다" to 2, "ktx" to 2, "기차" to 1,
        ),
        MeetingContext.STUDY to mapOf(
            "스터디" to 3, "공부" to 3, "과제" to 3, "회의" to 3, "팀플" to 3, "발표" to 2,
            "프로젝트" to 2, "노트북" to 2, "콘센트" to 2, "작업" to 2, "미팅" to 2, "자료" to 1,
        ),
    )

    /**
     * 채팅 원문(온디바이스)으로 상황을 분류한다.
     * 메시지 본문만 본다 — 발신자명은 분류에 무의미하고 PII이기도 하다.
     */
    fun classify(messages: List<Message>): ContextClassification =
        classifyText(messages.joinToString("\n") { it.content })

    /** 임의 텍스트로 상황을 분류한다(요약문·후기 재분류에도 재사용). */
    fun classifyText(text: String): ContextClassification {
        if (text.isBlank()) {
            return ContextClassification(MeetingContext.GENERIC, 0.0, emptyList(), null, emptyMap())
        }
        val haystack = text.lowercase()

        val scores = mutableMapOf<MeetingContext, Int>()
        val signals = mutableMapOf<MeetingContext, MutableList<String>>()

        LEXICON.forEach { (context, lexicon) ->
            val (total, matched) = scoreContext(haystack, lexicon)
            if (total > 0) {
                scores[context] = total
                signals[context] = matched.toMutableList()
            }
        }

        if (scores.isEmpty()) {
            return ContextClassification(MeetingContext.GENERIC, 0.0, emptyList(), null, emptyMap())
        }

        val sorted = scores.entries.sortedByDescending { it.value }
        val top = sorted.first()
        val runnerUp = sorted.getOrNull(1)

        if (top.value < MIN_TOP_SCORE) {
            return ContextClassification(
                MeetingContext.GENERIC, 0.0, signals[top.key].orEmpty().distinct(), top.key, scores,
            )
        }

        // 신뢰도 = 1위 점유율 — 2위가 바짝 붙으면 자연히 낮아진다(모호한 상황을 모호하다고 표기)
        val totalScore = scores.values.sum().toDouble()
        val confidence = (top.value / totalScore).coerceIn(0.0, 1.0)

        return ContextClassification(
            context = top.key,
            confidence = confidence,
            signals = signals[top.key].orEmpty().distinct(),
            runnerUp = runnerUp?.key,
            scores = scores,
        )
    }

    /**
     * 한 상황 사전으로 점수를 매긴다 — **긴 키워드 우선 + 소비 구간 제외** (2026-09-23 D5).
     *
     * 예전에는 키워드마다 독립으로 부분 문자열을 셌다. 그래서 사전에 포함 관계가 있으면
     * ("축하"와 "축하해") `"축하해주자"` 한 어절이 **두 키워드에 모두 걸려 6점**으로 이중
     * 계상됐다. 경쟁 상황이 없을 땐 판정이 안 바뀌지만, 경쟁이 붙으면 한쪽을 과대평가한다.
     *
     * 이제 긴 키워드부터 매칭하고 이미 소비된 글자 구간과 겹치는 매칭은 버린다.
     * `"축하해주자"`는 `축하해`(더 긴 쪽)에만 걸려 3점이 된다.
     * 상황 사전끼리는 독립이다 — 다른 상황의 키워드가 이 상황의 구간을 소비하지 않는다.
     *
     * @return (점수, 실제로 걸린 키워드 목록)
     */
    private fun scoreContext(haystack: String, lexicon: Map<String, Int>): Pair<Int, List<String>> {
        val consumed = mutableListOf<IntRange>()
        var total = 0
        val matched = mutableListOf<String>()

        // 긴 키워드 우선 — 같은 길이면 사전 순서(결정론 보장)
        lexicon.entries.sortedByDescending { it.key.length }.forEach { (keyword, weight) ->
            val kw = keyword.lowercase()
            if (kw.isEmpty()) return@forEach
            var hits = 0
            var idx = haystack.indexOf(kw)
            while (idx >= 0) {
                val range = idx until idx + kw.length
                val overlaps = consumed.any { it.first <= range.last && range.first <= it.last }
                if (!overlaps && !isNegated(haystack, range.last + 1)) {
                    consumed += range
                    hits++
                }
                idx = haystack.indexOf(kw, idx + kw.length)
            }
            if (hits > 0) {
                total += weight * minOf(hits, HIT_SATURATION)
                matched += keyword
            }
        }
        return total to matched
    }

    /**
     * 매칭 직후 짧은 창에 부정 표현이 있으면 그 등장은 세지 않는다.
     * 규칙은 [KoTextMatch]에 있다 — 슬롯 추출과 **같은 부정 규칙**을 써야 판정이 어긋나지 않는다.
     */
    private fun isNegated(haystack: String, from: Int): Boolean =
        KoTextMatch.isNegatedAfter(haystack, from)

}
