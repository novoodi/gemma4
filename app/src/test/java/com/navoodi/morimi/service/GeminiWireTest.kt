package com.navoodi.morimi.service

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gemini REST 와이어 포맷 조립·해석 검증 (org.json 실구현 사용).
 * 서버 프록시(geminiGenerate)는 이 본문을 키만 붙여 그대로 전달하므로,
 * 여기서 REST 스키마 형태가 보장돼야 프록시 경로 전체가 성립한다.
 */
class GeminiWireTest {

    private val decl = GeminiWire.functionDeclaration(
        name = "getWeather",
        description = "날씨",
        parameters = GeminiWire.schema(
            "OBJECT",
            properties = mapOf("city" to GeminiWire.schema("STRING", "도시")),
            required = listOf("city"),
        ),
    )

    private val responseSchema = GeminiWire.schema(
        "OBJECT",
        properties = mapOf(
            "summary" to GeminiWire.schema("STRING"),
            "places" to GeminiWire.schema("ARRAY", items = GeminiWire.schema("STRING")),
        ),
        required = listOf("summary", "places"),
    )

    @Test
    fun request_hasRestShape_contentsToolsGenerationConfig() {
        val contents = JSONArray().put(GeminiWire.userText("안녕"))
        val req = GeminiWire.request(contents, JSONArray().put(decl), responseSchema)

        assertEquals("user", req.getJSONArray("contents").getJSONObject(0).getString("role"))
        assertEquals(
            "안녕",
            req.getJSONArray("contents").getJSONObject(0)
                .getJSONArray("parts").getJSONObject(0).getString("text")
        )
        val fnDecls = req.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations")
        assertEquals("getWeather", fnDecls.getJSONObject(0).getString("name"))
        assertEquals(
            "STRING",
            fnDecls.getJSONObject(0).getJSONObject("parameters")
                .getJSONObject("properties").getJSONObject("city").getString("type")
        )
        val gen = req.getJSONObject("generationConfig")
        assertEquals("application/json", gen.getString("responseMimeType"))
        assertEquals("OBJECT", gen.getJSONObject("responseSchema").getString("type"))
        assertEquals(
            "STRING",
            gen.getJSONObject("responseSchema").getJSONObject("properties")
                .getJSONObject("places").getJSONObject("items").getString("type")
        )
    }

    @Test
    fun schema_omitsUnsetFields() {
        val s = GeminiWire.schema("STRING")
        assertEquals(1, s.length())
        assertEquals("STRING", s.getString("type"))
    }

    @Test
    fun functionCalls_extractsNameAndArgs_fromCandidateParts() {
        val response = JSONObject(
            """
            {"candidates":[{"content":{"role":"model","parts":[
              {"functionCall":{"name":"getWeather","args":{"city":"서울","date":"2026-09-13"}}},
              {"functionCall":{"name":"searchPlace","args":{"query":"강남 카페","city":"서울"}}}
            ]}}]}
            """.trimIndent()
        )
        val calls = GeminiWire.functionCalls(response)
        assertEquals(2, calls.size)
        assertEquals("getWeather", calls[0].name)
        assertEquals("서울", calls[0].args["city"])
        assertEquals("2026-09-13", calls[0].args["date"])
        assertEquals("searchPlace", calls[1].name)
        assertEquals("강남 카페", calls[1].args["query"])
    }

    @Test
    fun functionCalls_emptyWhenTextOnly_andTextJoinsParts() {
        val response = JSONObject(
            """
            {"candidates":[{"content":{"role":"model","parts":[
              {"text":"{\"summary\":\"요약\","},
              {"text":"\"places\":[]}"}
            ]}}]}
            """.trimIndent()
        )
        assertTrue(GeminiWire.functionCalls(response).isEmpty())
        val text = GeminiWire.text(response)
        assertEquals("{\"summary\":\"요약\",\"places\":[]}", text)
        assertEquals("요약", JSONObject(text!!).getString("summary"))
    }

    @Test
    fun text_nullWhenNoCandidates_orBlank() {
        assertNull(GeminiWire.text(JSONObject("{}")))
        assertNull(GeminiWire.text(JSONObject("""{"candidates":[{"content":{"parts":[{"text":"  "}]}}]}""")))
    }

    @Test
    fun functionCalls_skipsMalformedEntries() {
        val response = JSONObject(
            """
            {"candidates":[{"content":{"parts":[
              {"functionCall":{"args":{"x":1}}},
              {"functionCall":{"name":"","args":{}}},
              {"functionCall":{"name":"ok"}},
              {"text":"hi"}
            ]}}]}
            """.trimIndent()
        )
        val calls = GeminiWire.functionCalls(response)
        assertEquals(1, calls.size)
        assertEquals("ok", calls[0].name)
        assertTrue(calls[0].args.isEmpty())
    }

    @Test
    fun functionResponses_wrapsEachResultUnderUserRole() {
        val content = GeminiWire.functionResponses(
            listOf("getWeather" to "맑음", "searchPlace" to "검색 결과 없음")
        )
        assertEquals("user", content.getString("role"))
        val parts = content.getJSONArray("parts")
        assertEquals(2, parts.length())
        val fr0 = parts.getJSONObject(0).getJSONObject("functionResponse")
        assertEquals("getWeather", fr0.getString("name"))
        assertEquals("맑음", fr0.getJSONObject("response").getString("result"))
    }

    @Test
    fun modelContent_roundTripsIntoHistory() {
        val response = JSONObject(
            """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"f","args":{}}}]}}]}"""
        )
        val history = JSONArray().put(GeminiWire.userText("q"))
        history.put(GeminiWire.modelContent(response)!!)
        assertEquals(2, history.length())
        assertEquals("model", history.getJSONObject(1).getString("role"))
    }

    @Test
    fun toMap_convertsNullSentinel() {
        val m = GeminiWire.toMap(JSONObject("""{"a":null,"b":"x"}"""))
        assertTrue(m.containsKey("a"))
        assertNull(m["a"])
        assertEquals("x", m["b"])
    }
}
