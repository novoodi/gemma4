package com.navoodi.morimi.service

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusToolJsonTest {

    private fun parse(json: String): Triple<List<String>, List<String>, List<String>> {
        val o = JSONObject(json)
        fun arr(k: String) = o.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } }
        return Triple(arr("participants"), arr("preferences"), arr("availability"))
    }

    @Test
    fun `정상 args를 스키마 JSON으로 직렬화`() {
        val json = StatusToolJson.encode(
            mapOf(
                "participants" to listOf("양예찬", "차민영"),
                "preferences" to listOf("좋아요:매운 음식", "싫어요:시끄러운 술집"),
                "availability" to listOf("토요일 오후 가능"),
            )
        )
        val (p, pref, avail) = parse(json)
        assertEquals(listOf("양예찬", "차민영"), p)
        assertEquals(listOf("좋아요:매운 음식", "싫어요:시끄러운 술집"), pref)
        assertEquals(listOf("토요일 오후 가능"), avail)
    }

    @Test
    fun `누락 키는 빈 배열로 - 항상 세 키 존재`() {
        val json = StatusToolJson.encode(mapOf("participants" to listOf("이승현")))
        val (p, pref, avail) = parse(json)
        assertEquals(listOf("이승현"), p)
        assertEquals(emptyList<String>(), pref)
        assertEquals(emptyList<String>(), avail)
    }

    @Test
    fun `빈 args도 세 키를 가진 유효 JSON`() {
        val (p, pref, avail) = parse(StatusToolJson.encode(emptyMap()))
        assertEquals(emptyList<String>(), p)
        assertEquals(emptyList<String>(), pref)
        assertEquals(emptyList<String>(), avail)
    }

    @Test
    fun `공백·빈 문자열 원소는 제거`() {
        val json = StatusToolJson.encode(
            mapOf("preferences" to listOf("좋아요:커피", "  ", "", "싫어요:소음"))
        )
        val (_, pref, _) = parse(json)
        assertEquals(listOf("좋아요:커피", "싫어요:소음"), pref)
    }

    @Test
    fun `배열이 아닌 값은 빈 배열로 방어`() {
        val json = StatusToolJson.encode(mapOf("participants" to "문자열아님", "preferences" to 42))
        val (p, pref, _) = parse(json)
        assertEquals(emptyList<String>(), p)
        assertEquals(emptyList<String>(), pref)
    }

    @Test
    fun `숫자 등 비문자 원소는 toString으로 변환`() {
        val json = StatusToolJson.encode(mapOf("availability" to listOf(1, "토요일")))
        val (_, _, avail) = parse(json)
        assertEquals(listOf("1", "토요일"), avail)
    }

    @Test
    fun `출력은 StatusCompressionPipeline이 파싱하는 블록 형태`() {
        // 파이프라인의 JSON_BLOCK 정규식(\{[\s\S]*\})이 통째로 잡는 단일 객체여야 함
        val json = StatusToolJson.encode(mapOf("participants" to listOf("A")))
        assert(json.trimStart().startsWith("{")) { "JSON 객체로 시작해야 함: $json" }
        assert(json.trimEnd().endsWith("}")) { "JSON 객체로 끝나야 함: $json" }
    }
}
