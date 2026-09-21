package com.navoodi.morimi.service

import org.json.JSONObject

/**
 * Gemini generateContent 호출 포트. 오케스트레이터는 이 포트만 알고,
 * 실제 전송 경로(서버 프록시)와 분리돼 있어 테스트에서 교체 가능하다.
 *
 * @param model 모델명 — 서버 allowlist(functions/index.js GEMINI_ALLOWED_MODELS)에 있어야 한다
 * @param body  REST GenerateContentRequest JSON ([GeminiWire.request])
 * @return      REST GenerateContentResponse JSON
 */
interface GeminiGateway {
    suspend fun generateContent(model: String, body: JSONObject): JSONObject
}

/** Cloud Functions 프록시(geminiGenerate) 경유 — API 키는 서버 시크릿에만 존재 */
class CloudGeminiGateway : GeminiGateway {
    override suspend fun generateContent(model: String, body: JSONObject): JSONObject =
        CloudProxy.callJson(
            name = "geminiGenerate",
            data = JSONObject().put("model", model).put("body", body),
            timeoutSec = 120,
        )
}
