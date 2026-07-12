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
    private const val JOSA = "은|는|이|가|을|를|와|과|랑|도|만|의|에|로|께|한|부|보|처|밖|조|마"

    // 이름 + 호칭 백스톱: "민수님", "지영씨", "홍길동씨". 명단에 없는 이름을 보수적으로 포착.
    // 존칭 앞에 2자 이상 한글이 와야 매칭되므로 "손님"(손+1자)·"김씨"(김+1자)는 애초에 걸리지 않는다.
    private val honorificRegex = Regex("""[가-힣]{2,4}(님|씨)""")
    // 존칭이 붙는 흔한 비(非)이름 단어 — 오탐 방지
    private val honorificStopwords = setOf(
        "선생님", "고객님", "사장님", "부모님", "어머님", "아버님", "할머님", "아주머님",
        "아저씨", "아가씨",
    )

    /**
     * @param text       스크러빙 대상(주로 Gemma 익명화 요약문)
     * @param knownNames 채팅 참가자 실명 등 확정된 이름 목록(senderName·프로필 참가자)
     */
    fun scrub(text: String, knownNames: List<String> = emptyList()): Result {
        if (text.isBlank()) return Result(text, 0, emptyMap())

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
        for (token in buildNameTokens(knownNames)) {
            // 한글엔 \b 단어경계가 없다. 앞은 한글이 아니어야 하고(부분매칭 방지),
            // 뒤는 문장부호·공백·문장끝이거나 조사여야 한다(합성어 오소거 방지).
            val re = Regex("""(?<![가-힣])${Regex.escape(token)}(?=${'$'}|[^가-힣]|$JOSA)""")
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
