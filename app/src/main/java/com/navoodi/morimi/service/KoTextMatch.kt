package com.navoodi.morimi.service

import com.navoodi.morimi.data.model.RecommendedPlace

/**
 * 한국어 구절 매칭 유틸 — **정밀도 우선**.
 *
 * "조용한 카페" 같은 제약/선호 구절을 내용 토큰으로 쪼개고, 한 항목 안에 그 토큰이 **모두**
 * 나타날 때만 일치로 본다. 단일 토큰 우연 일치("카페"만 걸림)로 인한 오탐을 억제한다.
 *
 * [ReflectionService](제약 위반 판정)와 [PlaceRanker](선호 적합도 채점)가 같은 규칙을 써야
 * "Reflection은 통과인데 랭킹은 위반으로 감점" 같은 모순이 생기지 않으므로 여기로 모은다.
 *
 * 순수 Kotlin — JVM 단위 테스트 가능(CLAUDE.md 컨벤션 #6).
 */
object KoTextMatch {

    /** 랭킹·자기비평·지표가 같은 장소 정보(주소 포함)를 검사하도록 한곳에서 조립한다. */
    fun placeText(place: RecommendedPlace): String = "${place.name} ${place.reason} ${place.address}"

    /** 매칭에서 제외할 일반 명사/의존명사류 — 남기면 아무 추천에나 걸려 오탐이 된다. */
    val STOPWORDS = setOf(
        "곳", "것", "데", "거", "등", "좀", "걸", "게", "수", "및", "때", "점", "건",
        "분위기", "스타일", "느낌", "같은", "정도", "쪽", "거는", "그런",
    )

    const val MIN_TOKEN_LEN = 2

    private val SPLITTER = Regex("""[\s,./·|()\[\]]+""")

    /** 구절을 매칭용 내용 토큰으로 분해 — 공백·문장부호로 나누고 불용어·단문자 제거. */
    fun contentTokens(phrase: String): List<String> =
        phrase.split(SPLITTER)
            .map { it.trim() }
            .filter { it.length >= MIN_TOKEN_LEN && it !in STOPWORDS }
            .distinct()

    /**
     * [text] 안에 [phrase]의 내용 토큰이 모두 등장하는가. 토큰이 없으면 false(빈 구절은 무시).
     *
     * **회피 표현은 일치로 보지 않는다.** "술집 대신 따뜻한 차"는 불호 '술집'을 **지킨** 문장이지
     * 어긴 문장이 아니다. 모델이 제약을 지켰다고 말하는 바로 그 문장이 위반으로 잡히면
     * 재시도가 헛돈다 — 2026-09-13 실기기 평가에서 그 일이 실제로 일어나 3회를 소진했다.
     *
     * 판정: 구절의 **마지막으로 등장한 토큰** 뒤 짧은 창에 부정 표현이 있으면 회피로 본다.
     * 마지막 토큰을 보는 이유는 한국어에서 부정이 구절 끝에 붙기 때문이다("시끄러운 술집 대신").
     */
    fun matches(text: String, phrase: String): Boolean {
        val tokens = contentTokens(phrase)
        if (tokens.isEmpty()) return false
        if (!tokens.all { text.contains(it) }) return false
        val lastEnd = tokens.maxOf { text.lastIndexOf(it) + it.length }
        return !isNegatedAfter(text, lastEnd)
    }

    /** 부정을 보지 않는 날것의 포함 검사 — 부정 자체를 세야 하는 곳에서만 쓴다. */
    fun containsAllTokens(text: String, phrase: String): Boolean {
        val tokens = contentTokens(phrase)
        return tokens.isNotEmpty() && tokens.all { text.contains(it) }
    }

    /** [phrases] 중 [text]에 일치하는 것들. */
    fun matching(text: String, phrases: List<String>): List<String> =
        phrases.filter { matches(text, it) }

    // ── 부정 표현 ────────────────────────────────────────────────────────────

    /**
     * 어떤 말 **뒤**에 붙어 그것을 물리는 한국어 표현.
     *
     * 한국어는 부정이 뒤에 온다 — "술은 안 마셔", "고기 말고", "강남은 빼고".
     * 그래서 매칭 지점 **뒤쪽** 짧은 창만 본다.
     */
    val NEGATIONS = listOf(
        "안 ", "안먹", "안 먹", "못 ", "못먹", "말고", "빼고", "제외", "싫어", "별로", "아니",
        // 회피 표현 — 2026-09-13 실기기 평가에서 "술집 대신 따뜻한 차"가 불호 '술집' 위반으로
        // 3회 연속 오탐, 재시도 소진 후 폴백했다 (docs/eval/RESULTS.md)
        // "아닌"은 "아니"로 안 잡힌다 — 닌과 니는 다른 음절이다(부분 문자열 매칭의 함정)
        "대신", "아닌", "않은", "않는", "없는",
    )

    /** 부정 표현을 찾을 창 크기(자). 길면 다음 문장의 부정까지 끌어온다. */
    const val NEGATION_WINDOW = 10

    /**
     * [text]의 [from] 위치부터 짧은 창 안에 부정 표현이 있는가.
     *
     * **여기로 모은 이유**: 상황 분류(`ContextClassifier`)와 슬롯 추출(`PromptSieve`)이
     * 서로 다른 부정 규칙을 쓰면 "분류는 술자리가 아니라고 봤는데 슬롯에는 술집이 남는"
     * 모순이 생긴다. 이 파일이 존재하는 이유와 같다.
     */
    fun isNegatedAfter(text: String, from: Int): Boolean {
        if (from >= text.length) return false
        val window = text.substring(from, minOf(from + NEGATION_WINDOW, text.length))
        return NEGATIONS.any { window.contains(it) }
    }
}
