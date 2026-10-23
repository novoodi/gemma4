package com.navoodi.morimi.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PII 스크러버 — 프라이버시 방화벽의 결정론적 마지막 게이트 검증.
 * "온디바이스 방화벽" 주장을 실증하는 belt-and-suspenders 층의 회귀 방지.
 */
class PiiScrubberTest {

    // ── 참가자 명단 대조 (최고 신뢰도) ────────────────────────────────────────

    @Test
    fun `명단의 전체 이름을 마스킹한다`() {
        val r = PiiScrubber.scrub("김민수가 토요일에 오기로 했습니다.", knownNames = listOf("김민수"))
        assertEquals("[이름]가 토요일에 오기로 했습니다.", r.text)
        assertEquals(1, r.redactions)
        assertEquals(1, r.byCategory[PiiScrubber.CATEGORY_NAME])
    }

    @Test
    fun `3자 한국어 이름의 given-name도 마스킹한다`() {
        // 명단엔 풀네임만 있지만 대화에선 "민수"로 불릴 수 있어야 한다
        val r = PiiScrubber.scrub("민수랑 지영이 참석합니다.", knownNames = listOf("김민수", "이지영"))
        assertEquals("[이름]랑 [이름]이 참석합니다.", r.text)
        assertEquals(2, r.redactions)
    }

    @Test
    fun `풀네임과 given-name이 함께 있어도 중복없이 각각 마스킹한다`() {
        val r = PiiScrubber.scrub("김민수와 민수는 동일인입니다.", knownNames = listOf("김민수"))
        assertEquals("[이름]와 [이름]는 동일인입니다.", r.text)
        assertEquals(2, r.redactions)
    }

    @Test
    fun `이름의 부분 문자열이 다른 단어에 포함돼도 과잉 소거하지 않는다`() {
        // "민수"가 명단에 없고 풀네임 "김민수"만 있을 때, "수민"·"민수동" 같은 것을 오소거하면 안 됨
        // 명단 대조만(2026-10 이전 동작): 아무것도 지우지 않는다
        val r = PiiScrubber.scrub("수민이는 민수동 카페를 좋아합니다.", knownNames = listOf("박수현"), detectUnlisted = false)
        assertEquals("수민이는 민수동 카페를 좋아합니다.", r.text)
        assertEquals(0, r.redactions)
        // 명단 밖 탐지 켜짐(기본): "수민이는"은 이름 형태(받침 이름 + 이 + 는)라 지우지만, 합성어 "민수동"은 남긴다
        assertEquals("[이름]이는 민수동 카페를 좋아합니다.", PiiScrubber.scrub("수민이는 민수동 카페를 좋아합니다.", listOf("박수현")).text)
    }

    // ── 전화번호 ──────────────────────────────────────────────────────────────

    @Test
    fun `휴대전화 번호를 형식과 무관하게 마스킹한다`() {
        val a = PiiScrubber.scrub("연락처는 010-1234-5678 입니다.")
        assertEquals("연락처는 [연락처] 입니다.", a.text)

        val b = PiiScrubber.scrub("01098765432 로 연락 주세요.")
        assertEquals("[연락처] 로 연락 주세요.", b.text)
    }

    @Test
    fun `유선 지역번호도 마스킹한다`() {
        val r = PiiScrubber.scrub("가게 전화 02-123-4567 확인.")
        assertEquals("가게 전화 [연락처] 확인.", r.text)
    }

    @Test
    fun `날짜나 일반 숫자는 전화번호로 오인하지 않는다`() {
        val r = PiiScrubber.scrub("2026-07-12에 5명이 모입니다.")
        assertEquals("2026-07-12에 5명이 모입니다.", r.text)
        assertEquals(0, r.redactions)
    }

    // ── 이메일 ────────────────────────────────────────────────────────────────

    @Test
    fun `이메일 주소를 마스킹한다`() {
        val r = PiiScrubber.scrub("문의는 hong.gildong@example.com 으로.")
        assertEquals("문의는 [이메일] 으로.", r.text)
        assertEquals(1, r.byCategory[PiiScrubber.CATEGORY_EMAIL])
    }

    // ── 호칭 백스톱 (명단에 없는 이름) ────────────────────────────────────────

    @Test
    fun `명단에 없어도 존칭이 붙은 이름을 포착한다`() {
        val r = PiiScrubber.scrub("영수님이 예약했고 지영씨도 옵니다.", knownNames = emptyList())
        assertEquals("[이름]이 예약했고 [이름]도 옵니다.", r.text)
        assertEquals(2, r.redactions)
    }

    @Test
    fun `존칭이 붙는 흔한 비이름 단어는 마스킹하지 않는다`() {
        val r = PiiScrubber.scrub("선생님과 사장님, 아저씨가 왔습니다.", knownNames = emptyList())
        assertEquals("선생님과 사장님, 아저씨가 왔습니다.", r.text)
        assertEquals(0, r.redactions)
    }

    // ── 정상 경로 / 경계 조건 ─────────────────────────────────────────────────

    @Test
    fun `이미 익명화된 요약은 그대로 통과시킨다`() {
        val clean = "이번 토요일 오후 홍대 인근에서 카페 모임을 진행할 예정입니다."
        val r = PiiScrubber.scrub(clean, knownNames = listOf("김민수", "이지영"))
        assertEquals(clean, r.text)
        assertFalse(r.hadPii)
    }

    @Test
    fun `여러 종류의 PII가 섞여 있으면 모두 마스킹하고 카테고리별로 집계한다`() {
        val r = PiiScrubber.scrub(
            "김민수(010-1111-2222, minsu@test.com)가 예약했습니다.",
            knownNames = listOf("김민수"),
        )
        assertTrue(r.hadPii)
        assertEquals(3, r.redactions)
        assertEquals(1, r.byCategory[PiiScrubber.CATEGORY_NAME])
        assertEquals(1, r.byCategory[PiiScrubber.CATEGORY_PHONE])
        assertEquals(1, r.byCategory[PiiScrubber.CATEGORY_EMAIL])
    }

    @Test
    fun `빈 문자열은 그대로 반환한다`() {
        val r = PiiScrubber.scrub("", knownNames = listOf("김민수"))
        assertEquals("", r.text)
        assertEquals(0, r.redactions)
    }

    @Test
    fun `한 글자 이름은 과잉매칭 위험으로 대조에서 제외한다`() {
        val r = PiiScrubber.scrub("차를 마시러 갑니다.", knownNames = listOf("차"))
        assertEquals("차를 마시러 갑니다.", r.text)
        assertEquals(0, r.redactions)
    }

    /**
     * **호격 조사** — 채팅에서 이름을 부르는 가장 흔한 형태인데 조사 목록에 없었다.
     * 2026-09-13 실기기 평가에서 명단 내 이름 미탐의 주원인이었다
     * (`아` 77회, `야` 54회, `언(니)` 13, `형` 7, `쌤` 5 — `docs/eval/RESULTS.md`).
     */
    @Test
    fun `이름을 부르는 형태도 마스킹한다`() {
        val names = listOf("김민수", "박지영")
        listOf(
            "민수야 어디야",
            "지영아 오늘 시간 돼?",
            "고마워 민수야",
            "지영언니 같이 가자",
            "민수형 내일 봬요",
            "지영쌤 안녕하세요",
        ).forEach { text ->
            val r = PiiScrubber.scrub(text, names)
            assertFalse("이름이 남았다: [$text] -> [${r.text}]", r.text.contains("민수") || r.text.contains("지영"))
        }
    }

    /**
     * 호격 조사 아/야로 시작하는 합성어는 조사로 보지 않는다 — "지영아파트"·"민수야구단"처럼
     * 이름 뒤에 한글이 더 이어지면 조사가 아니라 새 단어의 시작이다.
     */
    @Test
    fun `아 야로 시작하는 합성어는 호격 조사로 오인해 소거하지 않는다`() {
        val r = PiiScrubber.scrub(
            "지영아파트 앞에서 민수야구단 사람들과 만나기로 했습니다.",
            knownNames = listOf("김민수", "이지영"),
        )
        assertEquals("지영아파트 앞에서 민수야구단 사람들과 만나기로 했습니다.", r.text)
        assertEquals(0, r.redactions)
    }

    @Test
    fun `직함 호칭은 존칭 백스톱에서 마스킹하지 않는다`() {
        // RESULTS.md 2026-09-13: 호칭 오탐 상위(교수님 18·팀장님 14·과장님 13·원장님 8·사모님 6·상무님 5)
        val r = PiiScrubber.scrub(
            "교수님과 팀장님, 과장님, 원장님, 사모님, 상무님이 참석합니다.",
            knownNames = emptyList(),
        )
        assertEquals("교수님과 팀장님, 과장님, 원장님, 사모님, 상무님이 참석합니다.", r.text)
        assertEquals(0, r.redactions)
    }

    /** 조사가 아닌 한글이 이어지는 합성어는 여전히 건드리지 않는다 — 기존 동작 회귀 확인. */
    @Test
    fun `조사가 아닌 글자가 이어지면 소거하지 않는다`() {
        val r = PiiScrubber.scrub("민수동 주민센터", listOf("김민수"))
        assertTrue("합성어를 오소거했다: [${r.text}]", r.text.contains("민수동"))
    }
}
