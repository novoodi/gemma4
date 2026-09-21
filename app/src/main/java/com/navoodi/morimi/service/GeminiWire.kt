package com.navoodi.morimi.service

import org.json.JSONArray
import org.json.JSONObject

/**
 * Gemini generateContent REST 와이어 포맷 조립·해석 (순수 Kotlin, org.json).
 *
 * SDK 없이 REST JSON을 직접 다룬다 — 앱에는 API 키가 없고, 이 본문을
 * [CloudProxy]가 서버 프록시(geminiGenerate)로 넘기면 서버가 키만 붙여 전달한다.
 * Android 의존성이 없어 JVM 단위 테스트 가능([GeminiWireTest]).
 *
 * 참조: https://ai.google.dev/api/generate-content
 */
object GeminiWire {

    data class FunctionCallRequest(val name: String, val args: Map<String, Any?>)

    // ── 스키마 / 선언 빌더 ────────────────────────────────────────────────────

    fun schema(
        type: String,
        description: String? = null,
        properties: Map<String, JSONObject>? = null,
        required: List<String>? = null,
        items: JSONObject? = null,
    ): JSONObject = JSONObject().apply {
        put("type", type)
        description?.let { put("description", it) }
        properties?.let { props ->
            put("properties", JSONObject().also { p -> props.forEach { (k, v) -> p.put(k, v) } })
        }
        required?.let { put("required", JSONArray(it)) }
        items?.let { put("items", it) }
    }

    fun functionDeclaration(name: String, description: String, parameters: JSONObject): JSONObject =
        JSONObject()
            .put("name", name)
            .put("description", description)
            .put("parameters", parameters)

    // ── 요청 조립 ─────────────────────────────────────────────────────────────

    fun userText(text: String): JSONObject = JSONObject()
        .put("role", "user")
        .put("parts", JSONArray().put(JSONObject().put("text", text)))

    /** 도구 실행 결과를 모델에 되돌려주는 컨텐츠 (name → 결과 텍스트) */
    fun functionResponses(results: List<Pair<String, String>>): JSONObject {
        val parts = JSONArray()
        results.forEach { (name, result) ->
            parts.put(
                JSONObject().put(
                    "functionResponse",
                    JSONObject()
                        .put("name", name)
                        .put("response", JSONObject().put("result", result))
                )
            )
        }
        return JSONObject().put("role", "user").put("parts", parts)
    }

    fun request(
        contents: JSONArray,
        functionDeclarations: JSONArray,
        responseSchema: JSONObject,
    ): JSONObject = JSONObject()
        .put("contents", contents)
        .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", functionDeclarations)))
        .put(
            "generationConfig",
            JSONObject()
                .put("responseMimeType", "application/json")
                .put("responseSchema", responseSchema)
        )

    // ── 응답 해석 ─────────────────────────────────────────────────────────────

    /** 첫 후보의 content (히스토리에 그대로 되붙이는 용도) */
    fun modelContent(response: JSONObject): JSONObject? =
        response.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")

    fun functionCalls(response: JSONObject): List<FunctionCallRequest> {
        val parts = modelContent(response)?.optJSONArray("parts") ?: return emptyList()
        return (0 until parts.length()).mapNotNull { i ->
            val fc = parts.optJSONObject(i)?.optJSONObject("functionCall") ?: return@mapNotNull null
            val name = fc.optString("name")
            if (name.isBlank()) return@mapNotNull null
            FunctionCallRequest(name, toMap(fc.optJSONObject("args")))
        }
    }

    /** 텍스트 파트를 이어 붙인 본문. 없거나 공백이면 null. */
    fun text(response: JSONObject): String? {
        val parts = modelContent(response)?.optJSONArray("parts") ?: return null
        val joined = buildString {
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                if (p.has("text")) append(p.optString("text"))
            }
        }
        return joined.takeIf { it.isNotBlank() }
    }

    fun toMap(obj: JSONObject?): Map<String, Any?> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.opt(k)
            out[k] = if (v == JSONObject.NULL) null else v
        }
        return out
    }
}
