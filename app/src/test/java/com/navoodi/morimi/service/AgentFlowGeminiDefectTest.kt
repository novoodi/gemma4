package com.navoodi.morimi.service

import com.navoodi.morimi.data.local.UserStatusEntity
import com.navoodi.morimi.data.model.VerificationStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 결함 탐색 — 시나리오 6(Gemini 비정상 응답과 Guardrail·재시도·최종 실패 처리).
 * 실패한 케이스는 @Ignore("결함: …")로 남겨 둔다(증거). 표: docs/eval/DEFECT_TEST_2026-10.md
 */
class AgentFlowGeminiDefectTest {

    private val dinner = listOf(
        msg("김민수", "토요일 강남에서 저녁 먹자", 1),
        msg("이지영", "좋아 밥 먹자", 2),
    )

    private fun failed(r: FlowRun) = r.result as? OrchestratorResult.Failed

    @Test
    fun `S6-1 비정상 - 존재하지 않는 장소만 계속 주면 3회 후 실패, 재시도 프롬프트에 피드백 누적`() {
        val r = AgentFlow.run(dinner, ScriptedGemini.of(Gem.final(listOf("유령식당"))))
        assertEquals(3, failed(r)!!.attempts)
        assertTrue(failed(r)!!.reason.contains("최대 재시도"))
        assertEquals(3, r.gemini.requests.size)
        assertTrue(r.prompts[1].contains("유령식당") && r.prompts[1].contains("존재하지 않는"))
        assertFalse(r.prompts[0].contains("시스템 검증 피드백"))
    }

    @Test
    fun `S6-2 정상 - 첫 시도 가짜 장소, 두 번째 실존 장소면 2회 만에 성공`() {
        val gemini = ScriptedGemini.of(Gem.final(listOf("유령식당")), Gem.final(listOf("미미식당")))
        val r = AgentFlow.run(dinner, gemini)
        assertEquals(2, r.success!!.attempts)
        assertEquals(VerificationStatus.VERIFIED, r.places.single().verification)
    }

    @Test
    fun `S6-3 정상 - 도구로 지역(서울)을 알린 뒤 부산 가게를 주면 지역 밖으로 재시도`() {
        val gemini = ScriptedGemini.of(
            Gem.weather("서울"), Gem.final(listOf("해운대횟집")),
            Gem.weather("서울"), Gem.final(listOf("미미식당")),
        )
        val r = AgentFlow.run(dinner, gemini)
        assertEquals(2, r.success!!.attempts)
        assertTrue(r.guardrails.first().feedback.contains("모임 지역(서울)"))
        assertFalse(r.guardrails.first().feedback.contains("존재하지 않는"))
    }

    @Test
    fun `S6-4 비정상 - 도구 호출 없이 다른 지역 가게를 줘도 대화의 지역(강남)으로 걸러야 한다`() {
        val gemini = ScriptedGemini.of(Gem.final(listOf("해운대횟집")), Gem.final(listOf("미미식당")))
        val r = AgentFlow.run(dinner, gemini)
        assertFalse("부산 가게가 강남 모임 추천으로 통과", r.guardrails.first().passed)
    }

    @Test
    fun `S6-5 비정상 - 깨진 JSON이 계속 오면 3회 후 파싱 실패로 종료`() {
        val r = AgentFlow.run(dinner, ScriptedGemini.of(Gem.text("{\"summary\": \"요약\", \"recommendedPlaces\": [\"미미")))
        assertEquals(3, failed(r)!!.attempts)
        assertTrue(failed(r)!!.reason.contains("파싱"))
    }

    @Test
    fun `S6-6 근접 오류 - 깨진 JSON 한 번 뒤 정상 응답이면 복구`() {
        val gemini = ScriptedGemini.of(Gem.text("not json at all"), Gem.final(listOf("미미식당")))
        val r = AgentFlow.run(dinner, gemini)
        assertEquals(2, r.success!!.attempts)
    }

    @Test
    fun `S6-7 비정상 - 빈 응답(candidates 없음)은 실패로 끝난다`() {
        val r = AgentFlow.run(dinner, ScriptedGemini.of(Gem.noCandidates()))
        assertEquals(3, failed(r)!!.attempts)
        assertTrue(failed(r)!!.reason.contains("비어"))
    }

    @Test
    fun `S6-8 이상 - 빈 JSON 객체는 추천 0곳 성공이 아니라 재시도·실패여야 한다`() {
        val r = AgentFlow.run(dinner, ScriptedGemini.of(Gem.text("{}")))
        assertTrue("추천 0곳이 성공 처리됨: ${r.result}", r.success == null || r.places.isNotEmpty())
    }

    @Test
    fun `S6-9 이상 - 장소 배열이 비면 성공이 아니라 재시도·실패여야 한다`() {
        val r = AgentFlow.run(dinner, ScriptedGemini.of(Gem.final(emptyList())))
        assertTrue("추천 0곳이 성공 처리됨: ${r.result}", r.success == null || r.places.isNotEmpty())
    }

    @Test
    fun `S6-10 근접 오류 - Reflection만 실패한 폴백이 있으면 마지막 시도 예외로 잃지 않는다`() {
        val status = UserStatusEntity(AgentFlow.ROOM, preferences = listOf("싫어요: 시끄러운 곳"))
        val noisy = Gem.final(listOf("미미식당 — 시끄러운 곳이지만 맛있음"))
        val gemini = ScriptedGemini.of(noisy, noisy, Gem.text("broken"))
        val r = AgentFlow.run(dinner, gemini, userStatus = status)
        assertNotNull("Guardrail을 통과한 폴백이 있는데 Failed: ${r.result}", r.success)
    }

    @Test
    fun `S6-11 정상 - Reflection만 계속 실패하면 3회 후 폴백 성공`() {
        val status = UserStatusEntity(AgentFlow.ROOM, preferences = listOf("싫어요: 시끄러운 곳"))
        val r = AgentFlow.run(dinner, ScriptedGemini.of(Gem.final(listOf("미미식당 — 시끄러운 곳이지만 맛있음"))), userStatus = status)
        assertEquals(3, r.success!!.attempts)
    }

    @Test
    fun `S6-12 이상 - 25자 넘는 지어낸 장소명이 검증 없이 통과하면 안 된다`() {
        val fake = "강남역 앞 아주 오래된 전통 한정식 코스 요리 전문점 별관"
        val r = AgentFlow.run(dinner, ScriptedGemini.of(Gem.final(listOf(fake))))
        assertFalse("검증 안 된 긴 이름이 성공 결과에 포함", r.placeNames.contains(fake))
    }

    @Test
    fun `S6-13 이상 - 6번째 장소도 검증돼야 한다(지어낸 이름 통과 금지)`() {
        val places = listOf("미미식당", "소담식당", "서울식당", "달빛술집", "조용한찻집", "유령식당")
        val r = AgentFlow.run(dinner, ScriptedGemini.of(Gem.final(places)))
        assertFalse("검증 안 된 6번째 장소가 통과", r.placeNames.contains("유령식당"))
    }

    @Test
    fun `S6-14 서버 장애 - 장소 검색 프록시가 전부 실패하면 UNKNOWN으로 통과(재시도 없음)`() {
        val r = AgentFlow.run(
            dinner, ScriptedGemini.of(Gem.final(listOf("미미식당", "소담식당"))),
            search = { PlaceSearchResult.Failed("UNAVAILABLE") },
        )
        assertEquals(1, r.success!!.attempts)
        assertTrue(r.places.all { it.verification == VerificationStatus.UNVERIFIED })
        assertEquals(2, r.guardrails.single().unknownCount)
    }

    @Test
    fun `S6-14b 서버 장애 - 검색 '결과 없음'은 장애와 달리 재시도 대상`() {
        val r = AgentFlow.run(
            dinner, ScriptedGemini.of(Gem.final(listOf("미미식당"))),
            search = { PlaceSearchResult.Found(emptyList()) },
        )
        assertEquals(3, failed(r)!!.attempts)
    }

    @Test
    fun `S6-15 서버 장애 - Gemini 프록시가 계속 실패하면 3회 후 실패`() {
        val r = AgentFlow.run(dinner, ScriptedGemini.failing())
        assertEquals(3, failed(r)!!.attempts)
        assertEquals(3, r.gemini.requests.size)
    }

    @Test
    fun `S6-16 서버 장애 - 모델이 도구만 계속 호출해도 왕복 상한으로 끝난다`() {
        val r = AgentFlow.run(dinner, ScriptedGemini.of(Gem.weather("서울")))
        assertNotNull(failed(r))
        assertEquals(3 * 9, r.gemini.requests.size) // 시도당 최초 1 + 도구 왕복 8
    }

    @Test
    fun `S6-17 근접 오류 - 이름과 이유를 ASCII 하이픈으로 구분해도 장소명만 남아야 한다`() {
        val r = AgentFlow.run(dinner, ScriptedGemini.of(Gem.final(listOf("미미식당 - 조용하고 맛있음"))))
        assertEquals("미미식당", r.placeNames.single())
    }

    @Test
    fun `S6-18 이상 - 장소 배열에 문자열이 아닌 원소가 섞여도 나머지는 살린다`() {
        val body = JSONObject()
            .put("summary", "요약")
            .put("recommendedPlaces", org.json.JSONArray().put("미미식당").put(123).put(JSONObject().put("x", 1)))
            .put("recommendedActivities", org.json.JSONArray().put("산책"))
            .put("itemsToBring", org.json.JSONArray().put("우산"))
        val r = AgentFlow.run(dinner, ScriptedGemini.of(Gem.text(body.toString())))
        assertTrue(r.placeNames.toString(), r.placeNames.contains("미미식당"))
    }
}
