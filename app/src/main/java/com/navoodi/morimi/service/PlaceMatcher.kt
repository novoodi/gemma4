package com.navoodi.morimi.service

/**
 * Guardrail 장소 실존 판정 — 추천 장소명과 카카오 검색 결과를 대조한다 (순수 Kotlin, Android 의존 없음).
 *
 * 과거에는 검색 total_count > 0 이면 실존으로 봤기 때문에, 모델이 지어낸 이름도
 * 비슷한 가게가 하나라도 검색되면 통과했다. 이제는 "이름 일치" + "모임과 같은 시·도"인
 * 검색 결과가 있을 때만 실존으로 판정한다.
 *
 * 이름 일치 규칙:
 * - 공백·기호·대소문자 무시
 * - 끝의 "~점" 지점명 토큰 무시 ("스타벅스 홍대입구역점" ≈ "스타벅스")
 * - 추천명 맨 앞의 지역명 토큰 제거 ("홍대 미미식당" ≈ "미미식당")
 * - 위 정규화 후 포함 관계 허용, 단 짧은 쪽이 [MIN_CONTAIN_LEN]자 이상일 때만
 * - 단, 지역 토큰을 뗀 핵심 이름이 업종 일반명사뿐이면("서울 식당" → "식당") 포함 관계를
 *   허용하지 않고 원래 추천명 전체와의 완전 일치만 인정한다 ([GENERIC_NOUNS] 참조)
 *
 * 지역 규칙: 시·도 단위로만 비교한다(인접 동네 추천은 정상). 모임 도시가 "미정"이거나
 * 시·도로 환원할 수 없으면 지역 검사를 생략한다.
 */
object PlaceMatcher {

    enum class Outcome {
        /** 이름이 일치하고 지역도 맞는(또는 지역 검사 생략) 결과가 있음 */
        MATCHED,
        /** 이름이 일치하는 결과가 없음 — 지어낸 장소로 간주 */
        NOT_FOUND,
        /** 이름은 일치하지만 모두 모임과 다른 시·도에 있음 */
        OUT_OF_REGION,
    }

    internal const val MIN_CONTAIN_LEN = 2
    private const val UNDECIDED_CITY = "미정"

    private val NON_WORD = Regex("[^\\p{L}\\p{N}]")
    private val TOKEN_SPLIT = Regex("[\\s,/·()\\[\\]{}<>-]+")
    private val ADMIN_SUFFIX = Regex("(특별자치시|특별자치도|특별시|광역시|역|시|도|구|군|동|읍|면)$")

    /**
     * 업종 일반명사 — 이것만으로는 특정 가게를 가리키지 못하는 단어들.
     *
     * 모델이 "서울 식당"·"강남 카페"처럼 지역 + 업종으로 장소를 지어내면, 지역 토큰을 뗀 핵심
     * 이름("식당")이 거의 모든 검색 결과 이름에 포함돼 포함 관계 매칭이 무조건 통과한다.
     * 그래서 핵심 이름이 이 단어들(의 이어붙임, 예: "카페바")로만 이루어져 있으면 포함 관계를
     * 막고, 원래 추천명 전체가 정규화 기준으로 완전히 같을 때만 인정한다 — 실제 상호가
     * "서울식당"인 가게는 통과, "미미식당"은 불통과. 같은 이유로 포함 관계의 짧은 쪽이
     * 일반명사뿐인 경우("식당"이라는 검색 결과 ⊂ "미미식당")도 막는다.
     * "카페 모모"처럼 고유명사가 섞이면 일반명사뿐이 아니므로 기존 규칙 그대로다.
     * 정규화([canonical]) 후 형태(소문자, 공백·기호 없음)로 둔다.
     */
    internal val GENERIC_NOUNS: Set<String> = setOf(
        "식당", "음식점", "밥집", "맛집", "레스토랑", "한식", "한식당", "중식", "중식당", "일식", "일식당",
        "양식", "분식", "분식집", "고깃집", "횟집", "국밥", "치킨", "피자", "뷔페", "카페", "커피",
        "커피숍", "커피전문점", "디저트", "베이커리", "빵집", "제과점", "브런치", "술집", "주점", "호프",
        "포차", "포장마차", "이자카야", "펍", "바", "와인", "노래방",
        "restaurant", "cafe", "coffee", "bakery", "pub", "bar",
    )
    private val MAX_GENERIC_LEN = GENERIC_NOUNS.maxOf { it.length }

    /** 시·도 정식/약식 명칭 → 정규 키 */
    private val PROVINCES: Map<String, String> = buildMap {
        fun add(key: String, vararg aliases: String) {
            put(key, key); aliases.forEach { put(it, key) }
        }
        add("서울", "서울시", "서울특별시")
        add("부산", "부산시", "부산광역시")
        add("대구", "대구시", "대구광역시")
        add("인천", "인천시", "인천광역시")
        add("광주", "광주시", "광주광역시")
        add("대전", "대전시", "대전광역시")
        add("울산", "울산시", "울산광역시")
        add("세종", "세종시", "세종특별자치시")
        add("경기", "경기도")
        add("강원", "강원도", "강원특별자치도")
        add("충북", "충청북도")
        add("충남", "충청남도")
        add("전북", "전라북도", "전북특별자치도")
        add("전남", "전라남도")
        add("경북", "경상북도")
        add("경남", "경상남도")
        add("제주", "제주도", "제주시", "제주특별자치도")
    }

    /**
     * 모임 도시로 자주 쓰이는 시·군·동네 → 시·도. 여러 시·도에 같은 이름이 있는 지명
     * (중구·강서 등)은 일부러 넣지 않는다 — 모르면 지역 검사를 생략하는 편이 안전하다.
     */
    private val LOCALITIES: Map<String, String> = buildMap {
        fun add(province: String, vararg names: String) = names.forEach { put(it, province) }
        add(
            "서울", "홍대", "홍대입구", "홍익대", "신촌", "강남", "잠실", "성수", "이태원", "명동", "종로", "을지로",
            "여의도", "건대", "압구정", "신사", "가로수길", "망원", "연남", "합정", "상수", "혜화",
            "대학로", "용산", "한남", "서촌", "북촌", "익선", "광화문", "노원", "영등포", "마포",
            "송파", "서초", "동대문", "성북", "관악", "신림", "서울대입구", "왕십리", "문래", "삼성",
            "역삼", "선릉", "청담", "목동", "구로", "서대문", "은평", "도봉", "강북", "중랑", "광진",
        )
        add("부산", "해운대", "광안리", "광안", "서면", "남포", "기장", "센텀", "전포", "송정")
        add("대구", "동성로", "수성")
        add("인천", "송도", "월미도", "부평", "청라")
        add("대전", "둔산", "유성")
        add(
            "경기", "수원", "성남", "분당", "판교", "일산", "고양", "용인", "부천", "안양", "의정부",
            "파주", "김포", "화성", "동탄", "평택", "광명", "하남", "남양주", "가평", "양평", "과천",
        )
        add("강원", "춘천", "강릉", "속초", "양양", "원주", "평창")
        add("충북", "청주", "충주", "제천")
        add("충남", "천안", "아산", "공주", "보령", "태안")
        add("전북", "전주", "군산", "익산")
        add("전남", "여수", "순천", "목포")
        add("경북", "경주", "포항", "안동")
        add("경남", "창원", "김해", "통영", "거제", "진주", "남해")
        add("제주", "서귀포", "애월")
    }

    /** 추천명·검색 결과 이름을 비교용 키로 정규화한다. */
    internal fun normalizeName(raw: String): String {
        val tokens = tokens(raw).toMutableList()
        // 끝의 "~점" 지점명 토큰 제거 — 단독 토큰("본점"만 있는 이름 등)은 지우지 않는다
        if (tokens.size >= 2 && tokens.last().endsWith("점")) tokens.removeAt(tokens.lastIndex)
        return canonical(tokens.joinToString(""))
    }

    /** 추천명에서 맨 앞 지역명 토큰을 뗀 변형 (지역 토큰이 없거나 남는 게 없으면 null). */
    internal fun withoutLeadingRegion(raw: String): String? {
        val tokens = tokens(raw)
        if (tokens.size < 2 || provinceOfToken(tokens.first()) == null) return null
        return tokens.drop(1).joinToString(" ")
    }

    /**
     * 추천 문자열에서 장소명만 뗀다 — "미미식당 - 분위기 좋음", "미미식당 — 이유", "미미식당 (강남구 …)".
     *
     * 구분자: em/en 대시(—, –, ―), 콜론, 세로선, 공백으로 둘러싼 ASCII 하이픈(" - "),
     * 공백 뒤 여는 괄호(" ("). 공백 없는 하이픈·괄호는 이름의 일부일 수 있어 자르지 않는다
     * ("W-카페", "미미식당(본점)"). 잘라서 남는 게 없으면 원문을 그대로 쓴다.
     */
    fun placeNameOf(raw: String): String {
        val s = raw.trim()
        val cut = PLACE_NAME_SEPARATOR.find(s)?.range?.first ?: return s
        return s.substring(0, cut).trim().ifEmpty { s }
    }

    private val PLACE_NAME_SEPARATOR = Regex("""[—–―:|]|\s-\s|\s-$|\s\(""")

    /**
     * 자유 텍스트(후기 등)가 이 장소를 언급하는가 — 공백·기호·조사와 무관하게("미미식당은", "미미식당이랑").
     * 이유·지점명·앞 지역 토큰을 뗀 핵심 이름이 정규화된 텍스트에 들어 있으면 언급으로 본다.
     * 핵심 이름이 업종 일반명사뿐("카페")이거나 2자 미만이면 언급으로 보지 않는다 — 아무 후기에나 걸린다.
     * 지점이 달라도 같은 상호면 언급이다("미미식당 강남점" 후기 ↔ "미미식당 역삼점").
     */
    fun mentionedIn(text: String, placeName: String): Boolean {
        val hay = canonical(text)
        if (hay.isEmpty()) return false
        val raw = placeNameOf(placeName)
        val cores = listOfNotNull(raw, withoutLeadingRegion(raw)).map { normalizeName(it) }.distinct()
        return cores.any { core -> core.length >= MIN_CONTAIN_LEN && !isGenericOnly(core) && hay.contains(core) }
    }

    /** 추천명과 검색 결과 이름이 같은 가게를 가리키는가 */
    fun nameMatches(recommended: String, candidate: String): Boolean {
        val cand = normalizeName(candidate)
        val full = normalizeName(recommended)
        if (cand.isEmpty() || full.isEmpty()) return false
        val core = withoutLeadingRegion(recommended)?.let { normalizeName(it) }?.ifEmpty { null } ?: full
        // 핵심 이름이 업종 일반명사뿐이면 원래 추천명 전체의 완전 일치만 인정
        if (isGenericOnly(core)) return full == cand
        return listOf(full, core).distinct().any { rec ->
            if (rec == cand) return@any true
            val (short, long) = if (rec.length <= cand.length) rec to cand else cand to rec
            short.length >= MIN_CONTAIN_LEN && !isGenericOnly(short) && long.contains(short)
        }
    }

    /** 정규화된 이름이 업종 일반명사(들의 이어붙임)로만 이루어져 있는가 ("카페", "카페바", "호프주점") */
    internal fun isGenericOnly(normalized: String): Boolean {
        if (normalized.isEmpty()) return false
        // reachable[i] = normalized[0, i)가 일반명사들로 분해 가능
        val reachable = BooleanArray(normalized.length + 1).also { it[0] = true }
        for (i in normalized.indices) {
            if (!reachable[i]) continue
            for (len in 1..minOf(MAX_GENERIC_LEN, normalized.length - i)) {
                if (normalized.substring(i, i + len) in GENERIC_NOUNS) reachable[i + len] = true
            }
        }
        return reachable[normalized.length]
    }

    /** 모임 도시 문자열 → 시·도 키. "미정"·빈 값·모르는 지역은 null(지역 검사 생략). */
    fun provinceOfCity(city: String?): String? {
        val c = city?.trim().orEmpty()
        if (c.isEmpty() || c == UNDECIDED_CITY) return null
        return tokens(c).firstNotNullOfOrNull { provinceOfToken(it) }
    }

    /** 카카오 주소("서울 마포구 서교동 …")의 첫 토큰 → 시·도 키. 해석 불가면 null. */
    fun provinceOfAddress(address: String?): String? =
        address?.let { tokens(it).firstOrNull() }?.let { PROVINCES[canonical(it)] }

    /**
     * 검색 결과 목록에서 추천 장소의 실존·지역 일치를 판정한다.
     * 주소로 시·도를 알 수 없는 결과는 지역 불일치로 단정하지 않는다(이름 일치만으로 인정).
     */
    fun evaluate(recommended: String, results: List<KakaoPlace>, city: String?): Outcome {
        if (results.none { nameMatches(recommended, it.name) }) return Outcome.NOT_FOUND
        return if (bestMatch(recommended, results, city) != null) Outcome.MATCHED else Outcome.OUT_OF_REGION
    }

    /**
     * [evaluate]가 MATCHED라고 본 근거 가게 하나 — 주소·지도 링크·좌표를 옮겨 담을 대상.
     * 이름 일치 + 모임 시·도(모르면 생략, 주소로 시·도를 알 수 없는 결과는 인정)인 결과 중에서
     * 정규화 이름이 완전히 같은 것을 먼저, 없으면 검색 순위가 가장 높은 것을 고른다.
     * Guardrail과 오케스트레이터가 같은 규칙으로 "이 추천 = 이 가게"를 정하게 하기 위함이다
     * (예전 오케스트레이터는 단순 포함 비교라 "카페"에 "카페 모모"가 붙었다).
     */
    fun bestMatch(recommended: String, results: List<KakaoPlace>, city: String?): KakaoPlace? {
        val target = provinceOfCity(city)
        val candidates = results.filter { place ->
            nameMatches(recommended, place.name) && (target == null || inProvince(place, target))
        }
        val key = normalizeName(recommended)
        return candidates.firstOrNull { normalizeName(it.name) == key } ?: candidates.firstOrNull()
    }

    private fun inProvince(place: KakaoPlace, target: String): Boolean {
        val p = provinceOfAddress(place.address) ?: provinceOfAddress(place.roadAddress)
        return p == null || p == target
    }

    private fun tokens(raw: String): List<String> =
        raw.trim().split(TOKEN_SPLIT).filter { canonical(it).isNotEmpty() }

    private fun canonical(s: String): String = s.lowercase().replace(NON_WORD, "")

    /**
     * 실제 자치구·일반구 이름. "구"로 끝나는 말 중 진짜 지역만 가려내는 데 쓴다 —
     * "강민구"(사람 이름)·"남자친구"를 지역으로 오인하지 않게 하고(PromptSieve),
     * "강남구도"·"중구랑"을 사람 이름으로 오인하지 않게 한다(PiiScrubber).
     */
    internal val DISTRICTS: Set<String> = setOf(
        // 서울 25구
        "종로구", "중구", "용산구", "성동구", "광진구", "동대문구", "중랑구", "성북구", "강북구", "도봉구",
        "노원구", "은평구", "서대문구", "마포구", "양천구", "강서구", "구로구", "금천구", "영등포구", "동작구",
        "관악구", "서초구", "강남구", "송파구", "강동구",
        // 광역시
        "서구", "동구", "남구", "북구", "영도구", "부산진구", "동래구", "해운대구", "사하구", "금정구", "연제구",
        "수영구", "사상구", "수성구", "달서구", "미추홀구", "연수구", "남동구", "부평구", "계양구", "광산구",
        "유성구", "대덕구",
        // 경기·기타 일반구
        "분당구", "수정구", "중원구", "장안구", "권선구", "팔달구", "영통구", "일산동구", "일산서구", "덕양구",
        "수지구", "기흥구", "처인구", "만안구", "동안구", "상록구", "단원구", "원미구", "소사구", "오정구",
        "덕진구", "완산구", "상당구", "서원구", "흥덕구", "청원구", "동남구", "서북구", "의창구", "성산구",
        "마산합포구", "마산회원구", "진해구", "남구", "북구",
    )

    /**
     * 지역명(시·도, 알려진 동네·시군, 실제 구 이름)인가. 사람 이름 탐지에서 지역을 빼는 데 쓴다.
     * 순수 함수 — 정규화 후 비교하므로 공백·기호는 무시된다.
     */
    internal fun isPlaceName(token: String): Boolean {
        val t = canonical(token)
        return t.isNotEmpty() && (t in DISTRICTS || provinceOfToken(t) != null)
    }

    private fun provinceOfToken(token: String): String? {
        val t = canonical(token)
        if (t.isEmpty()) return null
        PROVINCES[t]?.let { return it }
        LOCALITIES[t]?.let { return it }
        val stem = t.replace(ADMIN_SUFFIX, "")
        if (stem.length < 2 || stem == t) return null
        return PROVINCES[stem] ?: LOCALITIES[stem]
    }
}
