package com.navoodi.morimi.service

import com.navoodi.morimi.data.local.UserStatusEntity
import com.navoodi.morimi.data.model.Message
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class RecommendationPromptTest {
    private val date = LocalDate.of(2026, 9, 22)

    private fun sieve(text: String, status: UserStatusEntity? = null): SievedPrompt = PromptSieve.sieve(
        messages = listOf(Message(roomId = "r", senderId = "u", senderName = "김민구", content = text)),
        safeSummary = "식사 모임", userStatus = status, chatDate = date,
        ahp = AhpEngine.solve(MeetingContext.MEAL.basePairwiseMatrix()),
    )

    @Test
    fun `지역으로 오인한 참가자 이름은 검색어에도 남지 않는다`() {
        // 2026-10: "…구"는 실제 구 이름일 때만 지역이다 — 이름이 지역 슬롯·검색어로 들어가는 경로 자체가 막혔다
        val sieved = sieve("김민구 내일 밥 먹자")
        assertFalse(sieved.placeQuery.contains("김민구"))
        assertEquals(SievedPrompt.UNSPECIFIED, sieved.slot(FrameSlot.WHERE))
        val prompt = RecommendationPrompt.build(sieved, date, "", listOf("김민구"))
        assertFalse(prompt.contains("김민구"))
        assertTrue(prompt.contains("2026-09-23"))
    }

    @Test
    fun `프로필과 후기와 재시도 피드백의 개인정보도 최종 요청에서 마스킹한다`() {
        val status = UserStatusEntity(
            roomId = "r", preferences = listOf("좋아요: 김민구와 조용한 식사"),
            availability = listOf("010-1234-5678 연락"),
        )
        val prompt = RecommendationPrompt.build(
            sieve("내일 강남에서 밥 먹자", status), date,
            "김민구와 식사 test@example.com", listOf("김민구"),
            "김민구에게 010-9876-5432로 물어보세요", // 검증 단계에서 뒤늦게 유입된 자유 텍스트
        )
        listOf("김민구", "010-1234-5678", "010-9876-5432", "test@example.com").forEach {
            assertFalse(it, prompt.contains(it))
        }
        assertTrue(prompt.contains("[시스템 검증 피드백"))
        assertTrue(prompt.contains("강남"))
    }

    @Test
    fun `마스킹해도 정상 지역과 날짜와 도구 지시는 유지한다`() {
        val prompt = RecommendationPrompt.build(sieve("내일 홍대에서 밥 먹자"), date, "", emptyList())
        listOf("홍대", "2026-09-23", "searchPlace", "getWeather", "[정형화 요청 블록").forEach {
            assertTrue(it, prompt.contains(it))
        }
        assertFalse(prompt.contains("[시스템 검증 피드백"))
    }
}
