package com.navoodi.morimi.service

import com.navoodi.morimi.data.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 상황 분류기 검증.
 *
 * 회의에서 직접 지목된 사례("밥 약속하고 술 약속하고 차이가 있어 없어")를 그대로 테스트로
 * 박아 둔다 — 둘을 못 가르면 뒤따르는 프레임·가중치·추천이 전부 같은 값을 쓰게 된다.
 */
class ContextClassifierTest {

    private fun msg(text: String) =
        Message(roomId = "r1", senderId = "u1", senderName = "발화자", content = text)

    @Test
    fun `밥 약속과 술 약속을 다른 상황으로 가른다`() {
        val meal = ContextClassifier.classifyText("이번 주 토요일에 홍대에서 저녁 먹자 맛집 아는 데 있어?")
        val drink = ContextClassifier.classifyText("오늘 퇴근하고 술 한잔 하자 이자카야 어때")

        assertEquals(MeetingContext.MEAL, meal.context)
        assertEquals(MeetingContext.DRINK, drink.context)
        assertNotEquals(meal.context, drink.context)
    }

    @Test
    fun `위로 자리와 축하 자리를 구분한다`() {
        assertEquals(
            MeetingContext.CONSOLATION,
            ContextClassifier.classifyText("나 오늘 여자친구랑 헤어졌어 너무 속상하다").context,
        )
        assertEquals(
            MeetingContext.CELEBRATION,
            ContextClassifier.classifyText("이번 주말이 생일인데 축하해주자 파티하자").context,
        )
    }

    @Test
    fun `스터디와 여행과 액티비티를 구분한다`() {
        assertEquals(
            MeetingContext.STUDY,
            ContextClassifier.classifyText("내일 팀플 과제 같이 하자 노트북 챙기고 콘센트 있는 데로").context,
        )
        assertEquals(
            MeetingContext.TRIP,
            ContextClassifier.classifyText("다음 달에 1박 2일로 강릉 여행 가자 숙소 알아볼게").context,
        )
        assertEquals(
            MeetingContext.ACTIVITY,
            ContextClassifier.classifyText("주말에 볼링 치고 노래방 가자").context,
        )
    }

    @Test
    fun `부정 표현은 신호로 세지 않는다`() {
        // "술은 안 마셔"는 술자리 근거가 아니다 — 세면 밥 약속이 술집으로 간다
        val c = ContextClassifier.classifyText("술은 안 마셔 그냥 밥 먹자 맛집 가고 싶어 식당 알아봐")
        assertEquals(MeetingContext.MEAL, c.context)
    }

    @Test
    fun `말고 빼고 같은 후치 부정도 억제된다`() {
        val c = ContextClassifier.classifyText("술 말고 밥 먹자 식당 맛집 추천해줘 저녁 먹을 데")
        assertEquals(MeetingContext.MEAL, c.context)
    }

    @Test
    fun `신호가 없으면 GENERIC으로 떨어진다`() {
        val c = ContextClassifier.classifyText("ㅇㅇ ㅋㅋ 그래 알겠어")
        assertEquals(MeetingContext.GENERIC, c.context)
        assertEquals(0.0, c.confidence, 1e-9)
    }

    @Test
    fun `빈 입력도 안전하게 처리된다`() {
        val c = ContextClassifier.classifyText("")
        assertEquals(MeetingContext.GENERIC, c.context)
        assertTrue(c.signals.isEmpty())
        assertTrue(c.scores.isEmpty())
    }

    @Test
    fun `약한 단서 하나로는 상황을 확정하지 않는다`() {
        // "밥" 가중치 1점 단독 → MIN_TOP_SCORE 미달
        val c = ContextClassifier.classifyText("밥은?")
        assertEquals(MeetingContext.GENERIC, c.context)
    }

    @Test
    fun `포함 관계 키워드가 이중 계상되지 않는다`() {
        // D5: 긴 키워드 우선 매칭 + 소비 구간 제외.
        // "축하해주자"는 사전의 축하(3)·축하해(3) 양쪽에 걸려 6점이던 것이 3점이 된다.
        val cheer = ContextClassifier.classifyText("축하해주자")
        assertEquals(3, cheer.scores[MeetingContext.CELEBRATION])
        assertEquals(listOf("축하해"), cheer.signals)

        val full = ContextClassifier.classifyText("이번 주 토요일이 생일인데 축하해주자")
        assertEquals(MeetingContext.CELEBRATION, full.context)
        assertEquals(6, full.scores[MeetingContext.CELEBRATION])
        assertTrue(full.signals.containsAll(listOf("생일", "축하해")))
        assertFalse("축하가 중복 계상됨", full.signals.contains("축하"))
    }

    @Test
    fun `분류 근거 키워드를 남긴다`() {
        val c = ContextClassifier.classifyText("이자카야에서 술 한잔 하자")
        assertEquals(MeetingContext.DRINK, c.context)
        assertTrue("근거가 비어 있음", c.signals.isNotEmpty())
        assertTrue(c.signals.any { it.contains("이자카야") || it.contains("술") })
    }

    @Test
    fun `메시지 목록으로 분류할 때 본문만 본다`() {
        val messages = listOf(
            msg("이번 주 토요일에 저녁 먹자"),
            msg("홍대 맛집 아는 데 있어?"),
        )
        assertEquals(MeetingContext.MEAL, ContextClassifier.classify(messages).context)
    }

    @Test
    fun `신뢰도는 0과 1 사이이고 경쟁이 붙으면 낮아진다`() {
        val clear = ContextClassifier.classifyText("스터디 공부 과제 팀플 회의")
        val mixed = ContextClassifier.classifyText("밥 먹고 술도 한잔 하고 노래방도 가자 맛집 술집 볼링")
        assertTrue(clear.confidence in 0.0..1.0)
        assertTrue(mixed.confidence in 0.0..1.0)
        assertTrue("경쟁 상황의 신뢰도가 더 높음", mixed.confidence < clear.confidence)
        assertTrue("차순위 후보가 기록되지 않음", mixed.runnerUp != null)
    }

    @Test
    fun `같은 키워드 반복으로 점수가 폭주하지 않는다`() {
        val once = ContextClassifier.classifyText("볼링")
        val many = ContextClassifier.classifyText("볼링 볼링 볼링 볼링 볼링 볼링")
        val onceScore = once.scores[MeetingContext.ACTIVITY] ?: 0
        val manyScore = many.scores[MeetingContext.ACTIVITY] ?: 0
        assertTrue(manyScore <= onceScore * 2)
    }
}
