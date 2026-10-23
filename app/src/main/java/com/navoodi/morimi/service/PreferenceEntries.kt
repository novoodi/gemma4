package com.navoodi.morimi.service

/**
 * 성향 프로필 항목(`"좋아요: 조용한 카페"`)의 접두사를 다루는 **한 곳**.
 *
 * ### 왜 모았나
 *
 * `PromptSieve`(블록 슬롯 채우기)와 `ReflectionService`(제약 위반 판정)가 **글자까지 똑같은**
 * 접두사 목록과 분리 로직을 각자 갖고 있었다. 둘이 어긋나면
 * "블록에는 제약으로 실렸는데 Reflection은 제약으로 안 보는" 모순이 생긴다 —
 * [KoTextMatch]를 만든 이유와 같은 문제라 같은 방식으로 해결한다.
 *
 * ### 빈 항목
 *
 * 온디바이스 모델이 접두사만 내고 내용을 비우는 경우가 있다(`"좋아요:"`).
 * 2026-09-13 실기기 평가에서 **566개 항목 중 119개(21.0%)** 가 그랬다
 * (`docs/eval/RESULTS.md`). constrained decoding이 스키마는 강제하지만 내용 공백은 막지 못한다.
 *
 * 읽는 쪽([likes]·[dislikes])은 원래 빈 항목을 걸렀지만, **저장은 그대로 됐다.**
 * 그래서 프로필에 쓰레기가 영구 누적되고 디버그 패널에 `"좋아요:, 좋아요:, 싫어요:"`처럼
 * 뜬다. [isContentful]로 **들어올 때** 거른다 — 모델 출력을 믿지 않는다(CLAUDE.md 컨벤션 #2).
 *
 * 순수 Kotlin — JVM 단위 테스트 가능(컨벤션 #6).
 */
object PreferenceEntries {

    val LIKE_PREFIXES = listOf("좋아요", "좋아함", "선호", "호감")
    val DISLIKE_PREFIXES = listOf("싫어요", "싫어함", "싫음", "불호", "비선호")

    /** 접두사와 콜론을 뗀 본문. 접두사가 없으면 null(프로필 항목이 아닌 것으로 본다). */
    fun body(entry: String): String? {
        val e = entry.trim()
        val p = (LIKE_PREFIXES + DISLIKE_PREFIXES).firstOrNull { e.startsWith(it) } ?: return null
        return e.removePrefix(p).trimStart(':', ' ', '：').trim().takeIf { it.isNotBlank() }
    }

    /**
     * 저장할 가치가 있는 항목인가 — **접두사만 있고 내용이 없으면 버린다.**
     *
     * 접두사가 아예 없는 자유 서술(`"조용한 곳 좋아함"`)은 남긴다. 접두사 규약을 안 지킨 것이지
     * 내용이 없는 것은 아니고, 버리면 모델이 규약을 어겼을 때 성향이 통째로 사라진다.
     */
    fun isContentful(entry: String): Boolean {
        val e = entry.trim()
        if (e.isBlank()) return false
        val p = (LIKE_PREFIXES + DISLIKE_PREFIXES).firstOrNull { e.startsWith(it) }
            ?: return true // 접두사 없는 자유 서술은 통과
        return e.removePrefix(p).trimStart(':', ' ', '：').trim().isNotBlank()
    }

    /** [entries]에서 내용 없는 항목을 걸러낸다. */
    fun contentful(entries: List<String>): List<String> = entries.filter(::isContentful)

    /** "좋아요" 계열 항목의 본문들. */
    fun likes(preferences: List<String>): List<String> = strip(preferences, LIKE_PREFIXES)

    /** "싫어요" 계열 항목의 본문들. */
    fun dislikes(preferences: List<String>): List<String> = strip(preferences, DISLIKE_PREFIXES)

    private fun strip(preferences: List<String>, prefixes: List<String>): List<String> =
        preferences.mapNotNull { raw ->
            val entry = raw.trim()
            // "싫어요: ...", "싫어요 ..." 모두 허용
            val p = prefixes.firstOrNull { entry.startsWith(it) } ?: return@mapNotNull null
            entry.removePrefix(p).trimStart(':', ' ', '：').trim().takeIf { it.isNotBlank() }
        }.distinct()
}
