package com.navoodi.morimi.service

import org.json.JSONArray
import org.json.JSONObject

/**
 * constrained decoding 툴콜 결과(record_status args) → 성향 상태 JSON 직렬화.
 *
 * 툴콜 args는 `Map<String, 배열>`(participants/preferences/availability) 형태로 이미 파싱돼
 * 오지만, [com.navoodi.morimi.data.pipeline.StatusCompressionPipeline]이 기대하는 JSON
 * 문자열로 되돌려 넘긴다 — 포트/파이프라인/폴백을 건드리지 않는 최소 변경(B② DEVLOG 2026-07-14).
 *
 * 순수 Kotlin(안드로이드 의존성 없음) — JVM 단위 테스트 가능(컨벤션 #6).
 * org.json은 테스트 클래스패스에 실구현이 있음(CLAUDE.md).
 */
object StatusToolJson {

    private val KEYS = listOf("participants", "preferences", "availability")

    /** args의 각 키를 문자열 배열로 정규화해 `{participants:[],preferences:[],availability:[]}` 생성. */
    fun encode(args: Map<String, Any?>): String {
        val obj = JSONObject()
        for (key in KEYS) {
            val arr = JSONArray()
            (args[key] as? List<*>)?.forEach { v ->
                v?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(arr::put)
            }
            obj.put(key, arr)
        }
        return obj.toString()
    }
}
