package com.navoodi.morimi.service

import com.navoodi.morimi.data.local.UserStatusEntity
import com.navoodi.morimi.data.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 명단 밖 이름 마스킹 (DEFECT_TEST_2026-10 S1-8·S5-7) — 정상 / 비정상(누출 경로) / 경계 / 오탐 방지.
 *
 * 오탐 방지 코퍼스([NAME_FREE])는 회귀 게이트다: 이름이 없는 모임 대화·요약·프롬프트 문장에서
 * 이름 마스크가 하나라도 생기면 실패한다(지역·가게·일반명사를 가리면 추천 품질이 떨어진다).
 */
class PiiUnlistedNameTest {

    private fun scrub(text: String, known: List<String> = emptyList()) = PiiScrubber.scrub(text, known)
    private fun nameMasks(text: String) = scrub(text).byCategory[PiiScrubber.CATEGORY_NAME] ?: 0

    // ══ 결함 S1-8: 대화 속 제3자 이름 ═══════════════════════════════════════

    @Test
    fun `S1-8 정상 - 받침 이름 + 이도 (지훈이도 온대)`() {
        assertEquals("[이름]이도 온대", scrub("지훈이도 온대").text)
    }

    @Test
    fun `S1-8 정상 - 받침 없는 이름 + 가·도 (지호가, 지호도)`() {
        val out = scrub("토요일에 지호도 데려와도 돼? 지호가 거기 좋아해").text
        assertFalse(out, out.contains("지호"))
    }

    @Test
    fun `S1-8 정상 - 호격(수빈아, 민수야)`() {
        assertEquals("고마워 [이름]", scrub("고마워 수빈아").text.replace("[이름]아", "[이름]"))
        assertFalse(scrub("민수야 너도 와").text.contains("민수"))
    }

    @Test
    fun `S1-8 정상 - 한 번 잡힌 이름은 같은 텍스트의 맨 이름까지 지운다`() {
        val out = scrub("지호도 온대. 지호 오랜만이다. 지호 번호 알아?").text
        assertFalse(out, out.contains("지호"))
    }

    @Test
    fun `S1-8 정상 - 성 + 이름(강민구가, 박지훈이랑)과 이름 부분`() {
        val out = scrub("강민구가 늦는대. 민구 기다리자. 박지훈이랑 같이 와").text
        assertFalse(out, out.contains("민구") || out.contains("지훈"))
    }

    @Test
    fun `S1-8 비정상(누출 경로) - 원문에서 찾은 이름을 명단에 넣으면 요약의 맨 이름도 지운다`() {
        val names = PiiScrubber.detectNames("토요일 강남 저녁, 지훈이도 온대").toList()
        assertEquals(listOf("지훈"), names)
        assertEquals("[이름] 포함 4명이 강남에서 저녁", scrub("지훈 포함 4명이 강남에서 저녁", names).text)
    }

    @Test
    fun `S1-8 경계 - 명단의 given-name 앞에 다른 성이 붙어도 지운다(이민수)`() {
        assertEquals("[이름]도 온대", scrub("이민수도 온대", known = listOf("김민수")).text)
    }

    @Test
    fun `S1-8 경계 - 받침과 조사가 어긋나면 이름 신호가 아니다(지훈야, 민수아)`() {
        assertTrue(PiiScrubber.detectNames("지훈야 민수아").isEmpty())
    }

    @Test
    fun `S1-8 경계 - 신호 없는 맨 이름은 스스로 잡지 않는다(오탐 회피 설계)`() {
        assertTrue(PiiScrubber.detectNames("지호 오랜만이다").isEmpty())
        assertTrue(PiiScrubber.detectNames("강민구 왔어").isEmpty())
    }

    // ══ 결함 S5-7: 다른 방 후기 속 이름 ═════════════════════════════════════

    @Test
    fun `S5-7 정상 - 후기의 '지훈이랑'을 지운다`() {
        assertEquals("[이름]이랑 갔던 미미식당 너무 시끄러웠어요", scrub("지훈이랑 갔던 미미식당 너무 시끄러웠어요").text)
    }

    @Test
    fun `S5-7 정상 - 후기의 '서연이네', '유진이한테'`() {
        val out = scrub("서연이네 근처 카페 좋았음. 유진이한테 추천받은 곳").text
        assertFalse(out, out.contains("서연") || out.contains("유진"))
    }

    @Test
    fun `S5-7 비정상(누출 경로) - 최종 Gemini 프롬프트의 RAG 섹션에서 지워진다`() {
        val sieved = PromptSieve.sieve(
            messages = listOf(msg("김민수", "토요일 강남에서 저녁", 1)),
            safeSummary = "토요일 강남 저녁 모임",
            userStatus = null,
            chatDate = LocalDate.of(2026, 10, 1),
            ahp = AhpJudgmentLearner.resolve(MeetingContext.MEAL, emptyList()).result,
        )
        val prompt = RecommendationPrompt.build(
            sieved, LocalDate.of(2026, 10, 1),
            ragContext = "- [2026-09-20] (만족도 1/5) 지훈이랑 갔던 미미식당 너무 시끄러웠어요",
            knownNames = listOf("김민수"),
        )
        assertFalse(prompt.contains("지훈"))
        assertTrue(prompt.contains("미미식당 너무 시끄러웠어요"))
    }

    @Test
    fun `S5-7 경계 - 후기 속 가게명은 남긴다(미미식당이랑, 소담식당이)`() {
        val out = scrub("미미식당이랑 소담식당이 비슷했어요").text
        assertEquals("미미식당이랑 소담식당이 비슷했어요", out)
    }

    @Test
    fun `S5-7 경계 - 존칭 백스톱·직함 예외는 그대로(교수님 유지, 지훈씨 마스킹)`() {
        val out = scrub("교수님이랑 지훈씨랑 갔어요").text
        assertTrue(out.startsWith("교수님이랑"))
        assertFalse(out.contains("지훈"))
    }

    // ══ 강민구 — 이름을 지역으로 오인 ═══════════════════════════════════════

    @Test
    fun `강민구 - 사람 이름이 구 패턴으로 지역이 되지 않는다`() {
        assertNull(PromptSieve.extractArea("강민구 왔어"))
        assertNull(PromptSieve.extractArea("남자친구 데려가도 돼?"))
    }

    @Test
    fun `강민구 - 실제 구 이름은 여전히 지역`() {
        assertEquals("서초구", PromptSieve.extractArea("서초구 어때"))
        assertEquals("강남", PromptSieve.extractArea("강남구 어때")) // 알려진 상권명이 있으면 그 이름(기존 규칙)
        assertEquals("마포구", PromptSieve.extractArea("마포구 쪽으로 하자"))
    }

    @Test
    fun `강민구 - 발신자 이름은 동·역 패턴이어도 지역에서 뺀다`() {
        assertNull(PromptSieve.extractArea("한결동 왔어", personNames = setOf("한결동")))
        assertEquals("역삼역", PromptSieve.extractArea("강민구 역삼역 근처", personNames = setOf("강민구")))
    }

    @Test
    fun `강민구 - 거름망 전체 흐름에서 지역 슬롯이 이름이 아니다`() {
        val msgs = listOf(msg("강민구", "토요일 저녁 먹자", 1), msg("이지영", "강민구 오면 출발", 2), msg("박서준", "망원동 어때", 3))
        val s = PromptSieve.sieve(msgs, "", null, LocalDate.of(2026, 10, 1), AhpJudgmentLearner.resolve(MeetingContext.MEAL, emptyList()).result)
        assertEquals("망원", s.slot(FrameSlot.WHERE))
    }

    @Test
    fun `강민구 - 지역 없이 이름만 있으면 지역 미정`() {
        val msgs = listOf(msg("이지영", "강민구 오면 출발하자", 1), msg("박서준", "ㅇㅋ", 2))
        val s = PromptSieve.sieve(msgs, "", null, LocalDate.of(2026, 10, 1), AhpJudgmentLearner.resolve(MeetingContext.MEAL, emptyList()).result)
        assertEquals(SievedPrompt.UNSPECIFIED, s.slot(FrameSlot.WHERE))
    }

    // ══ 오탐 방지 ═════════════════════════════════════════════════════════

    @Test
    fun `오탐 방지 - 지역명·구 이름에 조사가 붙어도 이름이 아니다`() {
        listOf("건대도 괜찮아", "강남구도 좋고", "중구랑 가까워", "성수가 더 나아", "잠실이랑 건대", "홍대야 거기", "신촌이도")
            .forEach { assertEquals(it, 0, nameMasks(it)) }
    }

    @Test
    fun `오탐 방지 - 가게·업종명은 이름이 아니다`() {
        listOf("카페가 좋아", "한식이랑 중식", "보드게임 카페도", "치킨이랑 맥주", "소주랑 삼겹살")
            .forEach { assertEquals(it, 0, nameMasks(it)) }
    }

    @Test
    fun `오탐 방지 - 이름 음절로 된 일반어(일정·인원·미정·하나·정도)`() {
        listOf("일정이랑 장소 정하자", "인원이 몇 명이야", "장소 미정이야", "하나도 안 비싸", "어느 정도가 좋을까", "동기랑 갈게")
            .forEach { assertEquals(it, 0, nameMasks(it)) }
    }

    @Test
    fun `오탐 방지 - 형용사·동사 어미(괜찮아, 먹어야, 조용한, 신선한, 선호하고)`() {
        listOf("다들 괜찮아", "일찍 먹어야 해", "조용한 카페 가자", "신선한 회 먹자", "좋잖아", "그래야지", "조용한 곳을 선호하고")
            .forEach { assertEquals(it, 0, nameMasks(it)) }
    }

    @Test
    fun `오탐 방지 - 이름 없는 모임 대화·요약 코퍼스 전체에서 이름 마스크 0`() {
        val hits = NAME_FREE.map { it to scrub(it).text }.filter { (a, b) -> a != b }
        assertTrue("오탐: $hits", hits.isEmpty())
    }

    @Test
    fun `오탐 방지 - 실제 Gemini 프롬프트(이름 없음)는 한 글자도 바뀌지 않는다`() {
        val msgs = listOf(msg("김민수", "토요일 저녁 강남에서 조용한 식당 가자", 1), msg("이지영", "좋아 4명이야", 2))
        val status = UserStatusEntity("r", preferences = listOf("좋아요: 조용한 곳", "싫어요: 시끄러운 술집"))
        val ahp = AhpJudgmentLearner.resolve(MeetingContext.MEAL, emptyList()).result
        val summary = "이번 토요일에 서울 남부(강남 인근)에서 식사 모임을 진행할 예정입니다. 구체적인 시간과 인원은 조율 중이며, 날씨 및 장소 추천이 필요한 상태입니다."
        val sieved = PromptSieve.sieve(msgs, summary, status, LocalDate.of(2026, 10, 1), ahp)
        val feedback = "다음 장소는 지도 검색에서 같은 이름의 가게를 찾을 수 없어 존재하지 않는 것으로 판단됩니다: 유령식당."
        val prompt = RecommendationPrompt.build(sieved, LocalDate.of(2026, 10, 1), "", listOf("김민수", "이지영"), feedback)
        // 프롬프트 안에 이름이 없으니 명단 밖 탐지가 무엇도 찾지 말아야 하고, 마스크도 없어야 한다
        assertTrue(PiiScrubber.detectNames(prompt).toString(), PiiScrubber.detectNames(prompt).isEmpty())
        assertFalse(prompt.contains(PiiScrubber.NAME_MASK))
    }

    @Test
    fun `오탐 방지 - 명단 밖 탐지를 꺼도 기존 동작과 같다(전후 비교 기준)`() {
        val t = "김민수랑 지훈이도 온대. 교수님도 오셔"
        assertEquals("[이름]랑 지훈이도 온대. 교수님도 오셔", PiiScrubber.scrub(t, listOf("김민수"), detectUnlisted = false).text)
    }

    companion object {
        private fun msg(sender: String, content: String, t: Long) =
            Message(id = "$sender@$t", roomId = "r", senderId = sender, senderName = sender, content = content, timestamp = t)

        /** 이름이 하나도 없는 모임 대화·요약·피드백 문장 — 오탐 회귀 게이트 */
        val NAME_FREE = listOf(
            "토요일에 강남에서 저녁 먹자", "홍대 말고 망원동으로 하자", "건대입구역 2번 출구에서 만나", "성수동 브런치 카페 어때",
            "잠실 좋아", "일요일 오후 3시 괜찮아?", "다음 주 금요일 밤에 뭐 할까", "노래방 vs 영화", "영화 보고 싶어 조용히",
            "보드게임 카페 갈래? 나 보드게임 좋아해", "1인 2만원 내외로 하자", "가성비 좋은 데로", "비 오면 실내로 가자",
            "우산 챙겨", "예약은 내가 할게", "4명이니까 예약해야겠다", "시끄러운 술집은 싫어", "조용한 곳이 좋아",
            "회 먹고 싶다", "고기 먹자 삼겹살", "마라탕 어때", "파스타 먹을래", "초밥 콜", "디저트는 케이크",
            "주말에 다들 시간 돼?", "평일 저녁은 힘들어", "인원이 많으면 룸 있는 데로", "주차 되는 곳이면 좋겠다",
            "역에서 가까운 데", "분위기 좋은 와인바", "야식은 치킨이지", "커피 마시면서 수다 떨자", "한강 공원 산책",
            "날씨 좋으면 야외도 좋아", "실내 데이트 코스", "생일이니까 케이크 준비하자", "위로해 주는 자리라 조용한 데",
            "스터디 카페 예약 가능?", "볼링 치고 밥 먹자", "방탈출 해보자", "전시회 보고 카페", "망원 한강공원 피크닉",
            "해운대 바다 보면서", "서면에서 고기", "동성로 맛집", "연남동 이자카야", "을지로 노가리 골목",
            "이번 토요일에 서울 서북부(홍대 인근)에서 식사 모임을 진행할 예정입니다.",
            "구체적인 시간과 인원은 조율 중이며, 날씨 및 장소 추천이 필요한 상태입니다.",
            "참가자들은 조용한 분위기를 선호하고 시끄러운 술집은 피하고 싶어 한다.",
            "다음 주 수요일 저녁 신촌에서 회식, 예산은 1인 3만원.",
            "미미식당 너무 시끄러웠어요", "소담식당 최고였어요", "주차가 불편했음", "가격이 비쌌지만 맛있었다",
            "직원이 친절했고 음식이 빨리 나왔다", "다음엔 예약하고 가야겠다", "자리가 좁았어요",
            "장소 미정이야", "일정이랑 장소 정하자", "하나도 안 비싸", "어느 정도가 좋을까", "결정이 필요해",
            "사람이랑 차가 너무 많아", "음식이랑 서비스 다 좋았어", "영화도 보고 밥도 먹자", "카페가 조용해",
            "선생님이랑 상담 후에", "부모님도 같이 가도 돼?", "교수님 추천 맛집", "사장님이 친절함",
            "괜찮아 다들", "먹어야 힘이 나지", "그래야 늦지 않지", "조용한 카페", "신선한 해산물",
            "주소가 어디야", "후기가 좋더라", "숙소가 넓어", "대기가 길대", "정보가 없네", "선호하고 싶은 메뉴",
        )
    }
}
