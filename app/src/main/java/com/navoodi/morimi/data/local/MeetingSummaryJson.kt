package com.navoodi.morimi.data.local

import com.navoodi.morimi.data.model.MeetingSummary
import com.navoodi.morimi.data.model.RecommendedPlace
import com.navoodi.morimi.data.model.VerificationStatus
import org.json.JSONArray
import org.json.JSONObject

/**
 * MeetingSummary ↔ JSON 직렬화 (Room 영속용, 순수 Kotlin — JVM 단위 테스트 가능).
 *
 * 쓰는 쪽은 우리 코드지만 읽는 쪽은 앱 버전 간 스키마 변화를 견뎌야 하므로
 * 방어적으로 파싱한다: 없는 필드는 기본값, 모르는 enum은 UNVERIFIED,
 * 전체 파싱 실패는 크래시가 아니라 null(호출측에서 로그 후 스킵).
 */
object MeetingSummaryJson {

    fun toJson(s: MeetingSummary): String = JSONObject().apply {
        put("roomId", s.roomId)
        put("summary", s.summary)
        put("location", s.location)
        put("meetingDate", s.meetingDate)
        put("recommendation", s.recommendation)
        put("weather", s.weather)
        put("directions", s.directions)
        put("places", JSONArray().apply {
            s.places.forEach { p ->
                put(JSONObject().apply {
                    put("name", p.name)
                    put("address", p.address)
                    put("reason", p.reason)
                    put("placeUrl", p.placeUrl)
                    put("verification", p.verification.name)
                })
            }
        })
        put("activities", JSONArray(s.activities))
        put("itemsToBring", JSONArray(s.itemsToBring))
    }.toString()

    fun fromJson(json: String): MeetingSummary? = runCatching {
        val o = JSONObject(json)
        val roomId = o.optString("roomId")
        if (roomId.isBlank()) return null
        MeetingSummary(
            roomId = roomId,
            summary = o.optString("summary"),
            location = o.optString("location"),
            meetingDate = o.optString("meetingDate"),
            recommendation = o.optString("recommendation"),
            weather = o.optString("weather"),
            directions = o.optString("directions"),
            places = o.optJSONArray("places").toPlaces(),
            activities = o.optJSONArray("activities").toStringList(),
            itemsToBring = o.optJSONArray("itemsToBring").toStringList(),
        )
    }.getOrNull()

    private fun JSONArray?.toPlaces(): List<RecommendedPlace> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            val p = optJSONObject(i) ?: return@mapNotNull null
            val name = p.optString("name")
            if (name.isBlank()) return@mapNotNull null
            RecommendedPlace(
                name = name,
                address = p.optString("address"),
                reason = p.optString("reason"),
                placeUrl = p.optString("placeUrl"),
                verification = runCatching { VerificationStatus.valueOf(p.optString("verification")) }
                    .getOrDefault(VerificationStatus.UNVERIFIED),
            )
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i -> optString(i).takeIf { it.isNotBlank() } }
    }
}
