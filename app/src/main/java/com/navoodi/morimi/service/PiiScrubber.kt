package com.navoodi.morimi.service

/**
 * 결정론적 온디바이스 PII 스크러버 — 프라이버시 방화벽의 마지막 검증 게이트.
 *
 * Gemma의 [익명화 요약]이 "이름을 언급하지 마"라는 프롬프트 지시를 지키지 못해
 * 개인정보를 흘리더라도, 클라우드(Gemini) 전송 **직전** 이 스크러버가
 * 결정론적 규칙(정규식 + 참가자 명단 대조)으로 마스킹한다.
 *
 * LLM의 선의에만 의존하던 요약을 실증 가능한 방화벽으로 승격시키는 belt-and-suspenders 층.
 * 순수 함수 — Android 의존성 없음(JVM 단위 테스트 가능).
 */
object PiiScrubber {

    const val NAME_MASK = "[이름]"
    const val PHONE_MASK = "[연락처]"
    const val EMAIL_MASK = "[이메일]"

    const val CATEGORY_NAME = "name"
    const val CATEGORY_PHONE = "phone"
    const val CATEGORY_EMAIL = "email"

    data class Result(
        val text: String,
        val redactions: Int,
        val byCategory: Map<String, Int>,
    ) {
        val hadPii: Boolean get() = redactions > 0
    }

    // 휴대전화(010-1234-5678, 01012345678) 및 지역번호 유선(02-123-4567, 031-123-4567).
    // 앞뒤가 숫자/하이픈이면 더 긴 숫자열의 일부이므로 제외한다.
    private val phoneRegex =
        Regex("""(?<![\d-])(01[016789]|0\d{1,2})[-.\s]?\d{3,4}[-.\s]?\d{4}(?![\d-])""")

    private val emailRegex =
        Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")

    // 이름 뒤에 흔히 붙는 조사(첫 음절) — "김민수가"·"민수랑"은 이름으로 인정하되
    // "민수동"(합성어)처럼 조사가 아닌 한글이 이어지면 소거하지 않는다.
    //
    // **호격 조사 아/야와 친족 호칭이 빠져 있었다** (2026-09-23 추가).
    // 2026-09-13 실기기 평가에서 명단 내 이름 미탐의 주원인으로 지목된 것들이다 —
    // 이름 뒤 `아` 77회, `야` 54회, `언(니)` 13, `형` 7, `쌤` 5 (docs/eval/RESULTS.md).
    // 채팅에서 이름을 부르는 가장 흔한 형태("민수야", "지영아")를 통째로 놓치고 있었다.
    private const val JOSA =
        "은|는|이|가|을|를|와|과|랑|도|만|의|에|로|께|한|부|보|처|밖|조|마|언|형|쌤"

    // 호격 조사 아/야("민수야", "지영아")는 위 JOSA와 분리한다. "아"·"야"는 "아파트"·"야구"처럼
    // 흔한 단어의 첫 음절이기도 해서, 뒤에 한글이 더 이어지면(예: "지영아파트") 조사가 아니라
    // 새 단어의 시작일 가능성이 높다 — 뒤에 한글이 없을 때만 조사로 인정한다.
    private const val VOCATIVE_JOSA = "아|야"

    // 이름 + 호칭 백스톱: "민수님", "지영씨", "홍길동씨". 명단에 없는 이름을 보수적으로 포착.
    // 존칭 앞에 2자 이상 한글이 와야 매칭되므로 "손님"(손+1자)·"김씨"(김+1자)는 애초에 걸리지 않는다.
    private val honorificRegex = Regex("""[가-힣]{2,4}(님|씨)""")
    // 존칭이 붙는 흔한 비(非)이름 단어 — 오탐 방지.
    // 교수님·팀장님 등 직함 호칭은 대량 평가에서 오탐의 67%를 차지한 최대 원인이었다
    // (RESULTS.md 2026-09-13: 호칭 대조군 356건 중 239건 소실 — 교수님 18·팀장님 14·과장님 13·
    // 원장님 8·사모님 6·상무님 5).
    private val honorificStopwords = setOf(
        "선생님", "고객님", "사장님", "부모님", "어머님", "아버님", "할머님", "아주머님",
        "아저씨", "아가씨", "교수님", "팀장님", "과장님", "원장님", "사모님", "상무님",
    )

    /**
     * @param text       스크러빙 대상(주로 Gemma 익명화 요약문)
     * @param knownNames 채팅 참가자 실명 등 확정된 이름 목록(senderName·프로필 참가자)
     * @param detectUnlisted 명단에 없는 이름도 [detectNames]로 찾아 함께 소거한다(기본 켜짐).
     *   false면 2026-10 이전 동작(명단 + 존칭 백스톱만)과 같다 — 평가에서 전후 비교용.
     */
    fun scrub(text: String, knownNames: List<String> = emptyList(), detectUnlisted: Boolean = true): Result {
        if (text.isBlank()) return Result(text, 0, emptyMap())
        val names = if (detectUnlisted) knownNames + detectNames(text) else knownNames

        var out = text
        val counts = linkedMapOf<String, Int>()

        fun bump(category: String, n: Int) {
            if (n > 0) counts[category] = (counts[category] ?: 0) + n
        }

        // 1) 이메일·전화 — 언어 무관하게 안전한 결정론적 패턴 우선
        var n = 0
        out = emailRegex.replace(out) { n++; EMAIL_MASK }
        bump(CATEGORY_EMAIL, n)

        n = 0
        out = phoneRegex.replace(out) { n++; PHONE_MASK }
        bump(CATEGORY_PHONE, n)

        // 2) 참가자 명단 대조 — 가장 높은 신뢰도. 전체 이름 + (3자 한국어 이름의) 이름 부분.
        //    긴 토큰부터 치환해야 "김민수"가 "민수"보다 먼저 소거된다.
        for (token in buildNameTokens(names)) {
            // 한글엔 \b 단어경계가 없다. 앞은 한글이 아니어야 하고(부분매칭 방지),
            // 뒤는 문장부호·공백·문장끝이거나 조사여야 한다(합성어 오소거 방지).
            // 2자 이름(given-name)은 앞에 성 한 글자가 붙은 형태도 함께 지운다 — 명단의 "김민수"에서
            // 나온 "민수"가 "이민수"로 적히면 예전에는 앞 글자가 한글이라 통째로 남았다.
            val surname =
                if (detectUnlisted && token.length == 2 && token.all { it in '가'..'힣' }) "(?:[$SURNAMES])?" else ""
            val re = Regex(
                """(?<![가-힣])$surname${Regex.escape(token)}(?=${'$'}|[^가-힣]|$JOSA|(?:$VOCATIVE_JOSA)(?![가-힣]))"""
            )
            var hit = 0
            out = re.replace(out) { hit++; NAME_MASK }
            bump(CATEGORY_NAME, hit)
        }

        // 3) 호칭 백스톱 — 명단에 없는 이름을 존칭 패턴으로 포착
        var honorificN = 0
        out = honorificRegex.replace(out) { m ->
            if (m.value in honorificStopwords) m.value
            else { honorificN++; NAME_MASK }
        }
        bump(CATEGORY_NAME, honorificN)

        return Result(out, counts.values.sum(), counts)
    }

    // ── 명단 밖 이름 탐지 (2026-10, DEFECT_TEST S1-8·S5-7) ─────────────────────────
    //
    // 명단(발신자·프로필)과 존칭만 보던 시절에는 "지훈이도 온대"(대화 속 제3자),
    // 다른 방 후기의 "지훈이랑 갔던" 같은 이름이 그대로 Gemini로 나갔다.
    //
    // 원칙: **강한 문법 신호가 있을 때만** 이름으로 본다. 한국어에서 사람 이름에만 붙는 형태가 있다.
    //   1) 호격: 받침 있는 이름 + 아("지훈아"), 받침 없는 이름 + 야("민수야") — 단어 끝일 때만
    //   2) 애칭 '이' + 조사: 받침 있는 이름 + 이 + 가/도/는/를/랑/한테/하고/네/…("지훈이도", "유진이가").
    //      일반 명사에는 '이'가 끼지 않는다("사람이도"는 비문). 단 "이랑"은 일반 명사에도 붙으므로
    //      아래 음절·불용어 필터가 받친다
    //   3) 받침 없는 이름 + 가/도/랑/한테/하고/네("지호가", "지호도")
    //   4) 성 + 이름 2자 + 위 형태들("강민구가", "박지훈이랑")
    // 그리고 오탐 필터:
    //   - 이름 두 글자가 모두 흔한 이름 음절이어야 한다([GIVEN_SYLLABLES]) — "괜찮아", "먹어야",
    //     "사람이랑", "영화도"가 여기서 걸러진다
    //   - 지역명·구 이름·업종 일반명사가 아니어야 한다("건대도", "강남구도", "카페가")
    //   - 이름 음절로만 된 흔한 단어 불용어([NAME_STOPWORDS]) — "일정이랑", "하나도", "인원이랑"
    // 성만 붙은 맨 이름("강민구 온대")처럼 신호가 없는 형태는 스스로 잡지 않는다. "조용한"·"신선한"처럼
    // 이름 음절로 된 형용사가 너무 많아 추천 품질을 해친다. 대신 한 번 잡힌 이름은 같은 텍스트의
    // 맨 이름까지 전부 지운다(명단과 같은 토큰 경로).

    /** 흔한 한국인 성(1자). 성 + 이름 2자 형태 탐지와 2자 이름 앞 성 흡수에 쓴다. */
    internal const val SURNAMES =
        "김이박최정강조윤장임한오서신권황안송류전홍고문양손배백허유남심노하곽성차주우구민진나지엄채원천방공현함변염여추도소석선설마길연위표명기반왕금옥육인맹제모탁국어은편용예경봉사부"

    /** 이름에 흔히 쓰이는 음절. 두 글자 모두 여기 있어야 이름 후보가 된다. */
    private val GIVEN_SYLLABLES: Set<Char> = (
        "가건겸결경규균근기나남다담대도동라란래려련령록률린명문미민범별병보봄빈빛상새서석선설섭성세소솔송" +
            "수숙순슬승시신아안애연열영예옥온완요용우욱운웅원유윤율은의인일재정제종주준중지진찬창채철청초춘" +
            "태택하학한해혁현협형혜호홍환효후훈휘희구"
        ).toSet()

    /** 이름 음절로만 이뤄졌지만 모임 대화에 흔한 말 — 이름으로 보지 않는다. */
    private val NAME_STOPWORDS: Set<String> = setOf(
        "일정", "인원", "미정", "예정", "결정", "선정", "지정", "확정", "주문", "운동", "영상", "정식", "한식", "중식",
        "일식", "대학", "인기", "성인", "하나", "지도", "미소", "호수", "자주", "아주", "우주", "지하", "정도", "수다",
        "동기", "한우", "우동", "순대", "우유", "소주", "후보", "기준", "주민", "시민", "도시", "연애", "제일", "세상",
        "정원", "주인", "손님", "가운", "운영", "영업", "정리", "준비", "수정", "진행", "이번", "다음", "요일", "주중",
        "연휴", "연말", "연초", "시간", "장소", "근처", "동네", "주변", "정문", "후문", "입구", "출구", "명소", "명당",
        "주소", "후기", "숙소", "정보", "대기", "의도", "수도", "기도", "시도", "도보", "가수", "연기", "동선", "요소",
    )

    // 받침 있는 이름 + 이 + 조사 / 받침 없는 이름 + 조사
    private val AFTER_I = setOf(
        "가", "도", "는", "를", "랑", "랑은", "랑도", "한테", "한테도", "하고", "네", "의", "만", "와", "에게", "는요", "가요", "도요",
    )
    // "하고"는 뺀다 — 받침 없는 어간에 붙으면 "선호하고"·"수리하고"처럼 동사 활용과 구별되지 않는다.
    // (받침 이름은 "지훈이하고"처럼 '이'가 끼어 동사와 구별되므로 AFTER_I에는 둔다)
    private val AFTER_VOWEL = setOf("가", "도", "랑", "랑은", "랑도", "한테", "한테도", "네", "가요", "도요")
    private val HANGUL_RUN = Regex("[가-힣]+")

    /**
     * 텍스트에서 명단 밖 사람 이름을 찾는다(순수·결정론). 이름 그 자체("지훈", "강민구")를 돌려주고,
     * 실제 소거는 [scrub]이 명단과 같은 경로로 한다. 오케스트레이터는 기기 안의 채팅 원문에서도
     * 이 함수로 이름을 모아 명단에 더한다 — 원문은 나가지 않지만, 요약·정형 블록에 맨 이름으로
     * 옮겨 적힌 경우까지 지우기 위해서다.
     */
    fun detectNames(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        val found = linkedSetOf<String>()
        for (m in HANGUL_RUN.findAll(text)) {
            val word = m.value
            // 성 + 이름 2자 형태를 먼저 본다("강민구가" → 강민구)
            if (word.length >= 4 && word[0] in SURNAMES) {
                val full = word.substring(0, 3)
                if (isNameLike(full.substring(1)) && !isPlaceOrCommon(full) && nameSuffixOk(full, word.substring(3))) {
                    found += full
                    continue
                }
            }
            if (word.length >= 3) {
                val given = word.substring(0, 2)
                if (isNameLike(given) && nameSuffixOk(given, word.substring(2))) found += given
            }
        }
        return found
    }

    private fun isNameLike(given: String): Boolean =
        given.length == 2 && given.all { it in GIVEN_SYLLABLES } && !isPlaceOrCommon(given)

    private fun isPlaceOrCommon(s: String): Boolean =
        s in NAME_STOPWORDS || PlaceMatcher.isPlaceName(s) || PlaceMatcher.isGenericOnly(s)

    /** [name] 뒤의 글자들이 사람 이름에만 붙는 형태인가. 단어 끝(빈 꼬리)은 신호가 아니다. */
    private fun nameSuffixOk(name: String, rest: String): Boolean {
        if (rest.isEmpty()) return false
        val batchim = (name.last().code - 0xAC00) % 28 != 0
        return if (batchim) {
            rest == "아" || (rest.startsWith("이") && rest.substring(1) in AFTER_I)
        } else {
            rest == "야" || rest in AFTER_VOWEL
        }
    }

    /** 전체 이름 + 3자 한국어 이름의 given-name(성 제외). 긴 토큰이 먼저 오도록 정렬. */
    private fun buildNameTokens(knownNames: List<String>): List<String> {
        val tokens = linkedSetOf<String>()
        for (raw in knownNames) {
            val name = raw.trim()
            if (name.length < 2) continue           // 1자 이름/닉네임은 과잉 매칭 위험 → 제외
            tokens += name
            // 전형적 3자 한국어 이름(1자 성 + 2자 이름)의 이름 부분도 포착: 김민수 → 민수
            if (name.length == 3 && name.all { it in '가'..'힣' }) {
                tokens += name.substring(1)
            }
        }
        return tokens.sortedByDescending { it.length }
    }
}
