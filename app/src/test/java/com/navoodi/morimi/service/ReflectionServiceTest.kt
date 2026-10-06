package com.navoodi.morimi.service

import com.navoodi.morimi.data.model.RecommendedPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflectionServiceTest {

    private fun place(name: String, reason: String = "") =
        RecommendedPlace(name = name, reason = reason)

    @Test
    fun `불호 항목 없으면 통과`() {
        val r = ReflectionService.reflect(
            places = listOf(place("무브카페", "조용한 분위기의 카페입니다")),
            activities = listOf("보드게임"),
            preferences = listOf("좋아요: 조용한 카페", "선호: 이탈리안"),
        )
        assertTrue(r.passed)
        assertTrue(r.violations.isEmpty())
    }

    @Test
    fun `싫어요 제약이 장소 이유에서 감지되면 위반`() {
        val r = ReflectionService.reflect(
            places = listOf(place("무브카페", "조용한 분위기의 아늑한 카페입니다")),
            activities = emptyList(),
            preferences = listOf("싫어요: 조용한 카페"),
        )
        assertFalse(r.passed)
        assertEquals(1, r.violations.size)
        assertEquals("조용한 카페", r.violations.first().constraint)
        assertTrue(r.violations.first().matchedIn.contains("무브카페"))
        assertTrue(r.feedbackForRetry.contains("조용한 카페"))
    }

    @Test
    fun `모든 토큰이 한 항목에 모여야 위반 - 부분 일치는 통과`() {
        // "조용한"은 이유에, "술집"은 장소명에 흩어져 있어도 한 항목 내 동시 등장이 아니면 통과
        val r = ReflectionService.reflect(
            places = listOf(
                place("시끌벅적 술집", "활기찬 곳"),
                place("고요 카페", "조용한 분위기"),
            ),
            activities = emptyList(),
            preferences = listOf("싫어요: 조용한 술집"),
        )
        assertTrue("서로 다른 항목의 토큰은 위반 아님", r.passed)
    }

    @Test
    fun `활동에서 제약 위반 감지`() {
        val r = ReflectionService.reflect(
            places = emptyList(),
            activities = listOf("근처 노래방에서 노래 부르기"),
            preferences = listOf("싫어요: 노래방"),
        )
        assertFalse(r.passed)
        assertEquals("활동", r.violations.first().matchedIn)
    }

    @Test
    fun `요약은 검사하지 않음 - 회피 서술로 인한 오탐 방지`() {
        // 실제 추천(장소·활동)에는 불호 요소가 없고, 요약에만 "술집은 제외" 같은 서술이
        // 있어도 통과해야 한다(요약은 매칭 대상 아님).
        val r = ReflectionService.reflect(
            places = listOf(place("무브카페", "밝고 활기찬 공간")),
            activities = listOf("보드게임 즐기기"),
            preferences = listOf("싫어요: 술집"),
        )
        assertTrue(r.passed)
    }

    @Test
    fun `여러 접두사 형태와 콜론 유무를 모두 인식`() {
        val prefsVariants = listOf(
            listOf("싫어요: 노래방"),
            listOf("싫어함:노래방"),
            listOf("불호: 노래방"),
            listOf("싫음 노래방"),
        )
        prefsVariants.forEach { prefs ->
            val r = ReflectionService.reflect(
                places = listOf(place("코인노래방")),
                activities = emptyList(),
                preferences = prefs,
            )
            assertFalse("접두사 형태 $prefs 인식 실패", r.passed)
        }
    }

    @Test
    fun `좋아요 항목은 제약으로 취급하지 않음`() {
        val r = ReflectionService.reflect(
            places = listOf(place("코인노래방")),
            activities = emptyList(),
            preferences = listOf("좋아요: 노래방"),
        )
        assertTrue(r.passed)
    }

    @Test
    fun `여러 불호 중 하나만 걸려도 위반이고 걸린 것만 보고`() {
        val r = ReflectionService.reflect(
            places = listOf(place("조용한 도서관 카페", "책 읽기 좋은 조용한 공간")),
            activities = emptyList(),
            preferences = listOf("싫어요: 노래방", "싫어요: 조용한 공간"),
        )
        assertFalse(r.passed)
        assertEquals(1, r.violations.size)
        assertEquals("조용한 공간", r.violations.first().constraint)
    }

    @Test
    fun `불용어만 남는 제약은 무시`() {
        // "그런 곳"은 토큰이 전부 불용어/단문자 → 매칭 불가, 오탐 방지
        val r = ReflectionService.reflect(
            places = listOf(place("아무 곳", "그런 곳입니다")),
            activities = emptyList(),
            preferences = listOf("싫어요: 그런 곳"),
        )
        assertTrue(r.passed)
    }

    /**
     * **회피 표현을 위반으로 읽던 오탐** — 팀원의 2026-09-13 실기기 평가에서 실제로 걸렸다.
     *
     * > syn-13에서 "술집 대신 따뜻한 차를 마시며"라는 활동 설명이 불호 '술집'으로
     * > 3회 연속 위반 판정되어 재시도 소진 후 폴백 성공. (`docs/eval/RESULTS.md`)
     *
     * 모델이 **제약을 지켰다고 말하는 문장**이 제약 위반으로 잡혀서 재시도를 3번 태웠다.
     * 한 번의 재시도가 Gemini 왕복 20초 안팎이다.
     */
    @Test
    fun `회피 표현은 위반이 아니다`() {
        val prefs = listOf("싫어요: 술집")

        val avoided = ReflectionService.reflect(
            places = emptyList(),
            activities = listOf("술집 대신 따뜻한 차를 마시며 이야기하기"),
            preferences = prefs,
        )
        assertTrue("회피 표현을 위반으로 읽었다: ${avoided.violations}", avoided.passed)

        // 다른 회피 어휘들도 같다
        listOf(
            "술집 말고 조용한 찻집에서",
            "술집은 빼고 카페 위주로",
            "술집이 아닌 브런치 가게",
        ).forEach { activity ->
            val r = ReflectionService.reflect(emptyList(), listOf(activity), prefs)
            assertTrue("회피 표현을 위반으로 읽었다: [$activity] ${r.violations}", r.passed)
        }
    }

    /** 회피 처리가 **진짜 위반까지** 놓치면 안 된다 — 게이트가 무력해진다. */
    @Test
    fun `진짜 위반은 여전히 잡는다`() {
        val prefs = listOf("싫어요: 술집")
        listOf(
            "술집에서 한잔하기",
            "근처 술집 투어",
        ).forEach { activity ->
            val r = ReflectionService.reflect(emptyList(), listOf(activity), prefs)
            assertFalse("진짜 위반을 놓쳤다: [$activity]", r.passed)
        }
    }
}
