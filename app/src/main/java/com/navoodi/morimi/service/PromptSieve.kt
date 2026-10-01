package com.navoodi.morimi.service

import com.navoodi.morimi.data.local.UserStatusEntity
import com.navoodi.morimi.data.model.Message
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 정형화 프레임의 슬롯. 상황이 달라도 **슬롯 집합은 고정**이라 클라우드로 나가는 블록의
 * 형태가 항상 같다(값이 없으면 "미정"으로 채워 보낸다 — 키가 사라지지 않는다).
 *
 * @property critical 이 슬롯이 비면 추천 품질이 눈에 띄게 떨어지는 것 — 결손 경고 대상
 */
enum class FrameSlot(val key: String, val label: String, val critical: Boolean = false) {
    OCCASION("occasion", "상황", critical = true),
    PURPOSE("purpose", "목적"),
    WHEN("when", "일시", critical = true),
    TIME_OF_DAY("timeOfDay", "시간대"),
    WHERE("where", "지역", critical = true),
    HEADCOUNT("headcount", "인원"),
    BUDGET("budget", "예산"),
    PREFERENCES("preferences", "선호"),
    CONSTRAINTS("constraints", "제약(배제 조건)"),
}

/**
 * 거름망 통과 결과 — 자유 텍스트가 **고정 스키마 블록**으로 찍혀 나온 것.
 *
 * @property frame       클라우드로 전송되는 정형 블록(이 문자열이 프롬프트의 입력부가 된다)
 * @property missing     값을 못 채운 슬롯 — 모델이 추측으로 메우지 않도록 블록에 명시된다
 * @property placeQuery  상황·지역에서 합성한 장소 검색어 힌트
 */
data class SievedPrompt(
    val classification: ContextClassification,
    val slots: Map<FrameSlot, String>,
    val missing: List<FrameSlot>,
    val ahp: AhpResult,
    val frame: String,
    val placeQuery: String,
) {
    val context: MeetingContext get() = classification.context
    fun slot(s: FrameSlot): String = slots[s] ?: UNSPECIFIED

    companion object {
        const val UNSPECIFIED = "미정"
    }
}

/**
 * **거름망 — 상황 인식 슬롯 프레임 정형화 게이트.**
 *
 * 사용자가 무엇을 어떻게 입력하든(채팅은 형식이 없다) 클라우드로 나가는 요청을 항상 같은
 * 틀로 찍어낸다. 벽돌 틀에 시멘트를 부으면 어떤 반죽이 들어와도 같은 규격의 블록이 나오는 것과
 * 같은 역할이다. (회의 피드백 2026-09-14: "정형화시키는 모듈이 분명히 있잖아, 그걸 드러내라")
 *
 * 방법론은 과업지향 대화 시스템의 **슬롯 필링 + 프레임 의미론**(slot filling / frame semantics)이다:
 *   1. **상황 판정** — [ContextClassifier]가 대화를 유한한 [MeetingContext] 케이스로 분류
 *   2. **슬롯 충전** — 상황별 프레임의 고정 슬롯([FrameSlot])을 결정론적 추출기로 채움.
 *      특히 "이번 주 토요일" 같은 상대 표현은 **대화 날짜 기준 절대 날짜로 온디바이스에서 환산**한다
 *      (LLM에게 날짜 계산을 맡기면 조용히 틀리는 대표적 지점 — 계산 가능한 것은 계산해서 넣는다)
 *   3. **결손 표기** — 못 채운 슬롯을 "미정"으로 남기고 목록으로 명시 →
 *      모델이 빈칸을 그럴듯한 값으로 지어내는 할루시네이션 경로를 입력단에서 차단
 *   4. **기준 가중치 부착** — 이 상황에서 무엇을 중요하게 볼지를 [AhpEngine] 가중치로 동봉
 *
 * **프라이버시**: 슬롯은 제한된 패턴에서 추출하지만 지역 접미사 등이 이름과 겹칠 수 있다.
 * [RecommendationPrompt]가 검색어·재시도 피드백을 포함한 최종 요청 전체를 스크러빙한다.
 *
 * 순수 Kotlin(안드로이드 의존성 없음) — JVM 단위 테스트 가능(CLAUDE.md 컨벤션 #6).
 */
object PromptSieve {

    /** 프레임 스키마 버전 — 블록 포맷이 바뀌면 올린다(로그·재현 추적용). */
    const val FRAME_VERSION = "v1"

    private val WEEKDAYS: Map<String, DayOfWeek> = mapOf(
        "월" to DayOfWeek.MONDAY, "화" to DayOfWeek.TUESDAY, "수" to DayOfWeek.WEDNESDAY,
        "목" to DayOfWeek.THURSDAY, "금" to DayOfWeek.FRIDAY, "토" to DayOfWeek.SATURDAY,
        "일" to DayOfWeek.SUNDAY,
    )
    private val WEEKDAY_KO = listOf("월", "화", "수", "목", "금", "토", "일")

    // 긴 표현을 먼저 소비해 "다음 주 일요일"의 맨 요일을 중복 추출하지 않는다.
    private val RE_DATE = Regex(
        """(?:(?<week>다음\s*주|담주|이번\s*주|금주|돌아오는)\s*)?(?<weekday>[월화수목금토일])요일""" +
            """|(?<month>\d{1,2})\s*월\s*(?<day>\d{1,2})\s*일|(?<relative>모레|내일|오늘)"""
    )
    // 날짜 바로 뒤의 조사·부정만 본다. "내일 술집은 말고"는 날짜를 부정한 것이 아니다.
    private val RE_DATE_RETRACTION = Regex(
        """^\s*(?:에는|으로는|으로|은|는|에|로|도|이|가)?\s*""" +
            """(?:말고|대신|빼고|제외|아니|아닌|안\s*(?:돼|되|될)|못(?:\s|가|만나)|싫어|별로)"""
    )
    // "3시간"의 시는 시각이 아니다 — 뒤에 "간"이 붙으면 시각으로 보지 않는다
    private val RE_HOUR = Regex("""(오전|오후|아침|저녁|밤)?\s*(\d{1,2})\s*시(?!간)""")
    private val RE_HEADCOUNT = Regex("""(\d{1,2})\s*명""")
    private val RE_BUDGET_MAN = Regex("""(\d{1,3})\s*만\s*원""")
    private val RE_BUDGET_WON = Regex("""(\d{1,3},?\d{3})\s*원""")

    // 지역 접미사는 이름과 겹칠 수 있으므로 최종 요청에서 명단 대조 마스킹이 필요하다.
    private val RE_AREA_SUFFIX = Regex("""([가-힣]{2,6})(역|동|구|시)(?![가-힣])""")
    private val KNOWN_AREAS = listOf(
        "홍대", "강남", "신촌", "이태원", "건대", "성수", "연남", "합정", "망원", "종로",
        "을지로", "명동", "잠실", "왕십리", "노원", "수유", "혜화", "대학로", "가로수길",
        "여의도", "영등포", "구로", "사당", "서면", "해운대", "동성로", "충장로",
    )
    // 지역 오탐 방지 — 역/동/구/시로 끝나지만 지역이 아닌 흔한 말
    private val AREA_STOPWORDS = setOf("행동", "이동", "운동", "활동", "감동", "작동", "자동", "그동", "무시", "혹시", "당시", "요구", "지구")

    private val TIME_OF_DAY = mapOf(
        "아침" to "아침", "오전" to "오전", "점심" to "점심", "낮" to "낮",
        "저녁" to "저녁", "밤" to "밤", "새벽" to "새벽",
    )

    /**
     * 거름망 통과 — 원문·요약·프로필을 하나의 정형 블록으로 찍어낸다.
     *
     * @param messages    채팅 원문. **온디바이스에서만 읽는다** — 슬롯 추출 용도로만 쓰이고
     *                    원문 자체는 결과에 실리지 않는다
     * @param safeSummary PII 스크러빙을 마친 Gemma 익명화 요약(블록의 서술부)
     * @param ahp         이 상황에 적용될 기준 가중치(학습 반영본)
     */
    fun sieve(
        messages: List<Message>,
        safeSummary: String,
        userStatus: UserStatusEntity?,
        chatDate: LocalDate,
        ahp: AhpResult,
        classification: ContextClassification = ContextClassifier.classify(messages),
    ): SievedPrompt {
        val rawText = messages.joinToString("\n") { it.content }
        // 요약문도 같이 훑는다 — 원문에서 흐릿하던 단서가 요약에 또렷하게 남는 경우가 있다
        val haystack = "$rawText\n$safeSummary"

        val context = classification.context

        val slots = linkedMapOf<FrameSlot, String>()
        slots[FrameSlot.OCCASION] = buildString {
            append(context.label)
            append(" (")
            append(context.name)
            if (classification.confidence > 0.0) {
                append(", 신뢰도 ")
                append(pct(classification.confidence))
            }
            append(")")
        }
        slots[FrameSlot.PURPOSE] = context.purpose

        // 값이 바뀌는 슬롯은 **마지막 언급**을 쓴다 (2026-09-23). 아래 lastMention 주석 참조.
        conversationDate(messages, safeSummary, chatDate)
            ?.let { slots[FrameSlot.WHEN] = it }
        // 시간대 표현은 대화 전체에서 마지막 것을 물려받는다 — 시각만 남으면 모호해진다
        val carriedMarker = lastTimeMarker(messages, safeSummary)
        lastMention(messages, safeSummary) { extractTimeOfDay(it, carriedMarker) }
            ?.let { slots[FrameSlot.TIME_OF_DAY] = it }
        // 사람 이름은 지역 후보에서 뺀다 — "강민구"(발신자·대화 속 이름)가 "…구" 패턴으로 지역이 되던 문제
        val personNames = personNamesOf(messages, userStatus)
        lastMention(messages, safeSummary) { extractArea(it, personNames) }
            ?.let { slots[FrameSlot.WHERE] = it }
        lastMention(messages, safeSummary) { extractBudget(it) }
            ?.let { slots[FrameSlot.BUDGET] = it }
        // 인원은 명시된 수가 있으면 그 마지막 언급을, 없으면 발화자 수로 채운다(전역 폴백)
        (lastMention(messages, safeSummary) { extractHeadcountExplicit(it) }
            ?: speakerHeadcount(messages))
            ?.let { slots[FrameSlot.HEADCOUNT] = it }

        val prefs = likes(userStatus?.preferences.orEmpty())
        if (prefs.isNotEmpty()) slots[FrameSlot.PREFERENCES] = prefs.joinToString(", ")
        val cons = dislikes(userStatus?.preferences.orEmpty())
        if (cons.isNotEmpty()) slots[FrameSlot.CONSTRAINTS] = cons.joinToString(", ")

        val missing = FrameSlot.entries.filter { slots[it].isNullOrBlank() }

        val placeQuery = listOfNotNull(
            slots[FrameSlot.WHERE]?.takeIf { it.isNotBlank() },
            context.placeQueryHint,
        ).joinToString(" ")

        val frame = render(slots, missing, classification, ahp, safeSummary, userStatus)

        return SievedPrompt(
            classification = classification,
            slots = slots,
            missing = missing,
            ahp = ahp,
            frame = frame,
            placeQuery = placeQuery,
        )
    }

    // ── 렌더 — "벽돌" 한 장 ──────────────────────────────────────────────────

    /** 요약문과 슬롯이 어긋날 때 슬롯이 이긴다는 것을 모델에게 명시하는 줄 (Q2). */
    internal const val SLOT_PRECEDENCE_RULE =
        "※ 날짜·시간·지역은 위 슬롯 값이 요약문 표현보다 우선한다. " +
            "요약문의 상대 표현을 다시 해석하지 말 것."

    private fun render(
        slots: Map<FrameSlot, String>,
        missing: List<FrameSlot>,
        classification: ContextClassification,
        ahp: AhpResult,
        safeSummary: String,
        userStatus: UserStatusEntity?,
    ): String = buildString {
        appendLine("[정형화 요청 블록 $FRAME_VERSION]")
        FrameSlot.entries.forEach { slot ->
            appendLine("${slot.label}: ${slots[slot]?.takeIf { it.isNotBlank() } ?: SievedPrompt.UNSPECIFIED}")
        }
        userStatus?.availability?.takeIf { it.isNotEmpty() }?.let {
            appendLine("가능 일정: ${it.joinToString(", ")}")
        }
        // 판정 근거 어휘는 **블록에 싣지 않는다** (2026-09-23 D2).
        // 근거는 사전과 일치한 원문 어휘라 이름은 실릴 수 없지만, "잘렸"·"헤어졌"처럼
        // 참가자의 민감한 사정이 그대로 클라우드로 나간다. 추천에 필요한 것은 상황 라벨이지
        // 근거가 아니므로, 근거는 디버그 패널(AssistantEvent.ContextClassified)에만 남긴다.
        appendLine()
        appendLine("[판단 기준 가중치 — AHP, 이 순서대로 중요도를 두고 고를 것]")
        ahp.ranked().forEachIndexed { i, (criterion, weight) ->
            appendLine("${i + 1}. ${criterion.label} ${pct(weight)} — ${criterion.description}")
        }
        appendLine("일관성 비율 CR=${fmt(ahp.cr, 3)} (${if (ahp.consistent) "유효" else "기각"}, 임계 ${AhpEngine.CONSISTENCY_THRESHOLD})")
        appendLine()
        appendLine("[대화 요약 — 온디바이스 익명화 결과]")
        appendLine(safeSummary.trim().ifBlank { SievedPrompt.UNSPECIFIED })
        // 슬롯 우선 규칙 (2026-09-23 Q2). 요약문은 Gemma가 생성한 자연어라 "이번 주 토요일"
        // 같은 상대 표현과 "서울 서북부" 같은 일반화가 남는다. 슬롯은 온디바이스에서 절대
        // 날짜로 환산하고 지역도 구체화해 두었는데, 모델이 요약문 쪽을 다시 해석하면 그 계산이
        // 무의미해지고 틀린 값이 툴 호출(날씨 조회)로 전파된다. 우선순위를 한 줄로 못박는다.
        appendLine(SLOT_PRECEDENCE_RULE)
        if (missing.isNotEmpty()) {
            appendLine()
            appendLine("[미확정 슬롯 — 값을 지어내지 말 것]")
            appendLine(missing.joinToString(", ") { it.label })
            appendLine("위 항목은 대화에서 확인되지 않았다. 임의로 가정하지 말고 추천 이유에서 그 사실을 전제로 다뤄라.")
        }
    }.trim()

    // ── 슬롯 추출기 ─────────────────────────────────────────────────────────

    /** 날짜를 발화 순서대로 갱신한다. 원문에 날짜가 없을 때만 요약을 사용한다. */
    private fun conversationDate(messages: List<Message>, summary: String, chatDate: LocalDate): String? {
        var state = DateState()
        messages.forEach { state = resolveDates(it.content, chatDate, state) }
        return (state.date ?: resolveDates(summary, chatDate, DateState()).date)?.let(::format)
    }

    /** 단일 문장의 마지막 비부정 날짜를 환산한다. 맨 요일만 앞선 주 한정을 물려받는다. */
    internal fun extractDate(
        text: String,
        chatDate: LocalDate,
        nextWeekWindow: Boolean = false,
    ): String? = resolveDates(
        text, chatDate,
        DateState(anchor = if (nextWeekWindow) WeekAnchor.NEXT_WEEK else WeekAnchor.BARE),
    ).date?.let(::format)

    private data class DateState(val date: LocalDate? = null, val anchor: WeekAnchor = WeekAnchor.BARE)

    private fun resolveDates(text: String, chatDate: LocalDate, initial: DateState): DateState {
        var state = initial
        val mentions = RE_DATE.findAll(text).toList()
        mentions.forEachIndexed { index, mention ->
            // 다음 날짜의 부정을 앞 날짜에 붙이지 않는다("오늘 보자. 내일은 말고").
            val end = mention.range.last + 1
            val next = mentions.getOrNull(index + 1)
            val nextStart = next?.range?.first ?: text.length
            val suffix = text.substring(end, nextStart)
            val weekday = mention.groups["weekday"]?.value
            val week = mention.groups["week"]?.value
            val anchor = when {
                week == null -> state.anchor
                week.startsWith("다음") || week == "담주" -> WeekAnchor.NEXT_WEEK
                else -> WeekAnchor.THIS_WEEK
            }
            if (RE_DATE_RETRACTION.containsMatchIn(suffix)) {
                // "다음 주 토요일 말고 일요일"은 요일만 바꾼다. 같은 문장의 맨 요일에만 전달한다.
                if (week != null && next?.groups?.get("weekday") != null &&
                    next.groups["week"] == null && suffix.none { it in ".!?\n" }) {
                    state = state.copy(anchor = anchor)
                }
                return@forEachIndexed
            }

            if (weekday != null) {
                state = DateState(nextWeekday(chatDate, WEEKDAYS.getValue(weekday), anchor), anchor)
            } else {
                val relative = mention.groups["relative"]?.value
                val date = if (relative != null) {
                    val offset = when (relative) {
                        "모레" -> 2L
                        "내일" -> 1L
                        else -> 0L
                    }
                    chatDate.plusDays(offset)
                } else {
                    val month = mention.groups["month"]!!.value.toInt()
                    val day = mention.groups["day"]!!.value.toInt()
                    val candidate = runCatching { LocalDate.of(chatDate.year, month, day) }.getOrNull()
                        ?: return@forEachIndexed
                    if (candidate.isBefore(chatDate)) candidate.plusYears(1) else candidate
                }
                // 새 절대/상대 날짜 합의는 오래된 "다음 주" 한정을 해제한다.
                state = DateState(date)
            }
        }
        return state
    }

    /**
     * 요일 표현이 어느 주를 가리키는가.
     *
     * **당일이 그 요일일 때** 셋이 갈린다. 2026-09-19(토)에 "토요일"이라고 하면:
     *  - [THIS_WEEK] "이번 주 토요일" → **오늘**. 화자가 '이번 주'라고 명시했다.
     *  - [BARE] "토요일에 보자" → **다음 주 토요일**. 오늘을 뜻했다면 "오늘"이라고 했을 것이다.
     *  - [NEXT_WEEK] "다음 주 토요일" → 다음 ISO 주의 토요일.
     *
     * (2026-09-23 팀 결정. `SieveBulkEvalTest` syn-09가 이 경우였다 — 정답셋도 다음 주로 본다)
     */
    private enum class WeekAnchor { THIS_WEEK, BARE, NEXT_WEEK }

    /**
     * [from] 이후로 [target] 요일. [anchor]가 당일 처리와 주 이동을 정한다.
     *
     * **"다음 주 X요일"은 '다음 ISO 주(월요일 시작)의 X요일'이다** — 단순히 "가장 가까운
     * X요일 + 7"이 아니다. 둘은 목표 요일이 기준일보다 주 앞쪽일 때 갈린다.
     *
     * 2026-09-23 평가에서 실제로 걸린 버그(`SieveBulkEvalTest`, syn-06):
     * 기준일 2026-09-17(목)에서 "다음 주 수요일"을 구할 때
     * `(수3 − 목4 + 7) % 7 = 6`으로 이미 09-23(다음 주 수요일)에 닿는데 여기에 7을 더해
     * **09-30이 나왔다.** 한 주가 밀린 값이 그대로 날씨 조회 같은 툴 호출로 전파된다.
     */
    private fun nextWeekday(from: LocalDate, target: DayOfWeek, anchor: WeekAnchor): LocalDate =
        when (anchor) {
            WeekAnchor.NEXT_WEEK -> {
                val nextMonday = from.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                nextMonday.plusDays((target.value - DayOfWeek.MONDAY.value).toLong())
            }
            WeekAnchor.THIS_WEEK -> {
                from.plusDays(((target.value - from.dayOfWeek.value + 7) % 7).toLong())
            }
            WeekAnchor.BARE -> {
                val delta = (target.value - from.dayOfWeek.value + 7) % 7
                from.plusDays((if (delta == 0) 7 else delta).toLong())
            }
        }

    private fun format(d: LocalDate): String = "$d (${WEEKDAY_KO[d.dayOfWeek.value - 1]})"

    /**
     * @param carriedMarker 앞선 대화에서 잡힌 시간대 표현(저녁·점심·밤…).
     *   이 문장이 **시각만 말하고 시간대를 안 말했을 때** 물려받는다.
     *
     *   마지막 언급 우선의 부작용이다(날짜의 `nextWeekWindow`와 같은 문제).
     *   "금요일 저녁" → (합의) → "금요일 7시"에서 마지막만 보면 `"7시"`만 남는데,
     *   **아침 7시인지 저녁 7시인지 모르는 값이 클라우드로 나간다.** 식당 추천이 완전히 달라진다.
     *   2026-09-23 측정에서 시간대 표현이 **9건 전부 소실**됐다(`SieveBulkEvalTest`).
     *
     *   문장이 시간대를 **직접 말하면** 그쪽이 이긴다 — 물려받지 않는다("점심 말고 저녁 7시").
     */
    internal fun extractTimeOfDay(text: String, carriedMarker: String? = null): String? {
        RE_HOUR.find(text)?.let { m ->
            val marker = m.groupValues[1].ifBlank { carriedMarker.orEmpty() }
            val hour = m.groupValues[2]
            return if (marker.isNotBlank()) "$marker ${hour}시" else "${hour}시"
        }
        return TIME_OF_DAY.entries.firstOrNull { text.contains(it.key) }?.value
    }

    /** 대화에서 **마지막으로** 언급된 시간대 표현. 시각만 남은 문장이 이걸 물려받는다. */
    private fun lastTimeMarker(messages: List<Message>, safeSummary: String): String? =
        lastMention(messages, safeSummary) { text ->
            TIME_OF_DAY.entries.lastOrNull { text.contains(it.key) }?.value
        }

    /**
     * 문장에서 지역을 뽑는다 — **부정된 것은 빼고, 남은 것 중 뒤에 나온 것**을 쓴다.
     *
     * 예전 구현은 `KNOWN_AREAS.firstOrNull { text.contains(it) }`였다. 두 가지가 틀렸다:
     *
     *  1. **사전 순서로 찾았다.** "홍대"가 목록 0번이라 `"홍대 말고 망원동"`도
     *     `"망원동 말고 홍대"`도 전부 홍대를 줬다. 문장이 무엇을 말하든 상관없이.
     *  2. **부정을 보지 않았다.** `ContextClassifier`에는 부정 처리가 있는데
     *     슬롯 추출에는 없어서, 물린 지역이 그대로 클라우드로 나갔다.
     *
     * 지금은 모든 후보를 **위치와 함께** 모으고, 부정된 것을 버리고, 마지막 것을 쓴다.
     * 마지막을 쓰는 건 `lastMention`과 같은 규칙이다 — 대화든 문장이든 나중 말이 이긴다.
     *
     * 사람 이름은 지역이 아니다(2026-10). "…구" 패턴은 실제 구 이름([PlaceMatcher.DISTRICTS])일 때만
     * 지역으로 보고("강민구"·"남자친구" 제외), [personNames]에 있는 이름은 어떤 패턴이든 버린다.
     * 지역 슬롯 값은 정형 블록·검색어로 클라우드에 나가므로, 이름이 지역으로 둔갑하면 오추천과
     * 함께 이름 누출 경로가 된다.
     */
    internal fun extractArea(text: String, personNames: Set<String> = emptySet()): String? {
        data class Hit(val at: Int, val name: String)
        val hits = ArrayList<Hit>()

        for (area in KNOWN_AREAS) {
            if (area in personNames) continue
            var i = text.indexOf(area)
            while (i >= 0) {
                hits += Hit(i, area)
                i = text.indexOf(area, i + area.length)
            }
        }
        RE_AREA_SUFFIX.findAll(text).forEach { m ->
            val whole = m.groupValues[1] + m.groupValues[2]
            val isPerson = whole in personNames || m.groupValues[1] in personNames
            val fakeDistrict = m.groupValues[2] == "구" && whole !in PlaceMatcher.DISTRICTS
            if (whole !in AREA_STOPWORDS && m.groupValues[1] !in AREA_STOPWORDS && !isPerson && !fakeDistrict) {
                // 사전에 이미 잡힌 자리와 겹치면 중복으로 세지 않는다("망원동"의 "망원")
                if (hits.none { it.at <= m.range.first && m.range.first < it.at + it.name.length }) {
                    hits += Hit(m.range.first, whole)
                }
            }
        }
        if (hits.isEmpty()) return null

        val surviving = hits.filterNot { KoTextMatch.isNegatedAfter(text, it.at + it.name.length) }
        // 전부 부정됐으면 문장이 지역을 말하지 않은 것으로 본다(억지로 고르지 않는다)
        return surviving.maxByOrNull { it.at }?.name
    }

    /**
     * 지역 후보에서 뺄 사람 이름 — 발신자·프로필 참가자(3자 이름은 이름 부분도)와,
     * 대화 본문에서 [PiiScrubber.detectNames]가 찾은 명단 밖 이름. 기기 안에서만 쓴다.
     */
    internal fun personNamesOf(messages: List<Message>, userStatus: UserStatusEntity?): Set<String> {
        val roster = (messages.map { it.senderName } + userStatus?.participants.orEmpty())
            .map { it.trim() }.filter { it.length >= 2 }
        val given = roster.filter { it.length == 3 && it.all { c -> c in '가'..'힣' } }.map { it.substring(1) }
        val detected = messages.flatMap { PiiScrubber.detectNames(it.content) }
        return (roster + given + detected).toSet()
    }

    /** 명시된 "N명"이 있으면 그것, 없으면 실제 발화자 수(2명 이상일 때만). */
    /**
     * **대화 중 값이 바뀌면 마지막 언급이 이긴다.**
     *
     * 대화는 합의 과정이라 먼저 나온 제안이 뒤에서 뒤집히는 것이 정상이다. 클라우드로 보내야
     * 할 것은 **최종 결정**이지 첫 제안이 아니다. 예전에는 대화 전체를 한 문자열로 합쳐
     * `find()`로 **첫 일치**를 잡았기 때문에, "토요일 → 일요일로 바꾸자"나
     * "홍대는 붐벼 → 망원동으로" 같은 변경을 전부 놓치고 폐기된 값을 보냈다.
     * (2026-09-23 `SieveBulkEvalTest`에서 일시 4건·지역 3건이 이 원인으로 틀렸다)
     *
     * 메시지를 **뒤에서 앞으로** 훑고, 어느 메시지에도 없으면 요약문을 본다.
     * 요약문을 마지막에 두는 이유: 요약문은 대화에서 파생된 것이라 "언급"이 아니고,
     * 원문에서 흐릿하던 단서가 요약에만 또렷한 경우를 위한 보조 수단이다.
     *
     * **한계**: "망원동 말고 홍대" 같은 부정문은 여전히 마지막에 나온 지명을 그대로 잡는다.
     * 부정 처리는 `ContextClassifier.isNegated`에만 있고 슬롯 추출에는 없다 — 별개 과제다.
     */
    private fun <T> lastMention(
        messages: List<Message>,
        safeSummary: String,
        extract: (String) -> T?,
    ): T? {
        for (i in messages.indices.reversed()) {
            extract(messages[i].content)?.let { return it }
        }
        return safeSummary.takeIf { it.isNotBlank() }?.let(extract)
    }

    /** 대화에 **명시된** 인원 수. 없으면 null — 발화자 수 폴백은 [speakerHeadcount]가 한다. */
    internal fun extractHeadcountExplicit(text: String): String? =
        RE_HEADCOUNT.find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it in 2..99 }
            ?.let { "${it}명" }

    /** 인원이 명시되지 않았을 때의 폴백 — 발화자 수는 대화 전체에서 세는 게 맞다. */
    internal fun speakerHeadcount(messages: List<Message>): String? {
        val speakers = messages.map { it.senderName.trim() }.filter { it.isNotEmpty() }.distinct().size
        return if (speakers >= 2) "${speakers}명 (대화 참여자 기준)" else null
    }

    @Deprecated("마지막 언급 우선으로 바뀌었다. extractHeadcountExplicit + speakerHeadcount를 쓸 것")
    internal fun extractHeadcount(text: String, messages: List<Message>): String? =
        extractHeadcountExplicit(text) ?: speakerHeadcount(messages)

    internal fun extractBudget(text: String): String? {
        RE_BUDGET_MAN.find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it in 1..300 }
            ?.let { return "1인 ${it}만원 내외" }
        RE_BUDGET_WON.find(text)?.groupValues?.get(1)
            ?.let { return "1인 ${it}원 내외" }
        return when {
            text.contains("가성비") || text.contains("저렴") || text.contains("싼") -> "가성비 중시"
            text.contains("비싸도") || text.contains("플렉스") -> "예산 여유 있음"
            else -> null
        }
    }

    // ── 프로필 선호/불호 분해 ────────────────────────────────────────────────

    // 접두사 규칙은 [PreferenceEntries]에 있다 — Reflection과 **같은 규칙**을 써야
    // "블록에는 제약으로 실렸는데 Reflection은 제약으로 안 보는" 모순이 안 생긴다.
    internal fun likes(preferences: List<String>): List<String> = PreferenceEntries.likes(preferences)

    internal fun dislikes(preferences: List<String>): List<String> = PreferenceEntries.dislikes(preferences)

    // ── 포맷 유틸 ────────────────────────────────────────────────────────────

    private fun pct(v: Double): String = "${fmt(v * 100, 1)}%"

    private fun fmt(v: Double, digits: Int): String {
        val factor = Math.pow(10.0, digits.toDouble())
        val rounded = Math.round(v * factor) / factor
        return if (digits == 0) rounded.toInt().toString() else rounded.toString()
    }
}
