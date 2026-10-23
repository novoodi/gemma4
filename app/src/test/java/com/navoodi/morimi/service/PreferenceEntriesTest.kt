package com.navoodi.morimi.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 성향 프로필 항목의 접두사 처리.
 *
 * `PromptSieve`와 `ReflectionService`가 **글자까지 똑같은** 목록을 각자 갖고 있던 것을
 * 여기로 모았다. 둘이 어긋나면 "블록에는 제약으로 실렸는데 Reflection은 제약으로 안 보는"
 * 모순이 생긴다.
 */
class PreferenceEntriesTest {

    @Test
    fun `접두사와 콜론을 떼고 본문만 남긴다`() {
        assertEquals(listOf("조용한 카페"), PreferenceEntries.likes(listOf("좋아요: 조용한 카페")))
        assertEquals(listOf("시끄러운 술집"), PreferenceEntries.dislikes(listOf("싫어요: 시끄러운 술집")))
        // 콜론 없이 띄어쓰기만 있어도 허용한다 — 모델이 규약을 느슨하게 지킨다
        assertEquals(listOf("조용한 곳"), PreferenceEntries.likes(listOf("선호 조용한 곳")))
        // 전각 콜론
        assertEquals(listOf("파스타"), PreferenceEntries.likes(listOf("좋아요： 파스타")))
    }

    @Test
    fun `좋아요와 싫어요를 섞어도 각각만 가져간다`() {
        val prefs = listOf("좋아요: 조용한 곳", "싫어요: 시끄러운 술집", "선호: 파스타")
        assertEquals(listOf("조용한 곳", "파스타"), PreferenceEntries.likes(prefs))
        assertEquals(listOf("시끄러운 술집"), PreferenceEntries.dislikes(prefs))
    }

    /**
     * 온디바이스 모델이 접두사만 내고 내용을 비우는 경우.
     * 2026-09-13 실기기 평가에서 566개 중 119개(21.0%)가 그랬다.
     */
    @Test
    fun `내용 없는 항목은 저장 대상이 아니다`() {
        assertFalse(PreferenceEntries.isContentful("좋아요:"))
        assertFalse(PreferenceEntries.isContentful("좋아요: "))
        assertFalse(PreferenceEntries.isContentful("싫어요："))
        assertFalse(PreferenceEntries.isContentful("   "))

        assertEquals(
            listOf("좋아요: 조용한 카페", "싫어요: 시끄러운 술집"),
            PreferenceEntries.contentful(
                listOf("좋아요: 조용한 카페", "좋아요:", "싫어요: 시끄러운 술집", "싫어요： "),
            ),
        )
    }

    /**
     * 접두사 규약을 안 지킨 자유 서술은 **버리지 않는다.**
     * 버리면 모델이 규약을 어겼을 때 성향이 통째로 사라진다 — 빈 항목과는 다른 문제다.
     */
    @Test
    fun `접두사 없는 자유 서술은 남긴다`() {
        assertTrue(PreferenceEntries.isContentful("조용한 곳 좋아함"))
        assertTrue(PreferenceEntries.isContentful("매운 음식은 못 먹어"))
        assertEquals(listOf("조용한 곳 좋아함"), PreferenceEntries.contentful(listOf("조용한 곳 좋아함")))
        // 다만 접두사가 없으니 likes/dislikes로는 잡히지 않는다 — 알려진 한계다
        assertTrue(PreferenceEntries.likes(listOf("조용한 곳 좋아함")).isEmpty())
    }

    @Test
    fun `중복은 한 번만 남긴다`() {
        assertEquals(
            listOf("조용한 카페"),
            PreferenceEntries.likes(listOf("좋아요: 조용한 카페", "선호: 조용한 카페")),
        )
    }

    /** 거름망과 Reflection이 같은 규칙을 쓰는지 — 이 파일이 존재하는 이유. */
    @Test
    fun `거름망과 Reflection이 같은 불호를 본다`() {
        val prefs = listOf("싫어요: 시끄러운 술집", "불호: 매운 음식", "싫어요:")
        val fromSieve = PromptSieve.dislikes(prefs)
        assertEquals(listOf("시끄러운 술집", "매운 음식"), fromSieve)
        // ReflectionService.extractDislikes는 private이므로 동작으로 확인한다:
        // 거름망이 제약으로 본 항목은 Reflection도 위반 판정 대상으로 봐야 한다
        val r = ReflectionService.reflect(
            places = emptyList(),
            activities = listOf("시끄러운 술집에서 한잔"),
            preferences = prefs,
        )
        assertFalse("거름망이 제약으로 본 것을 Reflection이 놓쳤다", r.passed)
    }
}
