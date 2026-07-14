package com.navoodi.morimi.data.local

import com.navoodi.morimi.data.model.MeetingSummary
import com.navoodi.morimi.data.model.RecommendedPlace
import com.navoodi.morimi.data.model.VerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeetingSummaryJsonTest {

    private val full = MeetingSummary(
        roomId = "room-1",
        summary = "토요일 홍대 모임",
        location = "홍대입구역",
        meetingDate = "2026-07-18",
        recommendation = "보드게임 카페 추천",
        weather = "맑음 28도",
        directions = "2호선 홍대입구역 9번 출구",
        places = listOf(
            RecommendedPlace(
                name = "레드버튼 홍대점",
                address = "서울 마포구",
                reason = "보드게임 200종",
                placeUrl = "https://place.map.kakao.com/123",
                verification = VerificationStatus.VERIFIED,
            ),
            RecommendedPlace(name = "이름만 있는 곳"),
        ),
        activities = listOf("보드게임", "노래방"),
        itemsToBring = listOf("우산"),
    )

    @Test
    fun `왕복 직렬화 - 모든 필드 보존`() {
        val restored = MeetingSummaryJson.fromJson(MeetingSummaryJson.toJson(full))
        assertEquals(full, restored)
    }

    @Test
    fun `왕복 직렬화 - 기본값 요약도 보존`() {
        val minimal = MeetingSummary(roomId = "room-2")
        assertEquals(minimal, MeetingSummaryJson.fromJson(MeetingSummaryJson.toJson(minimal)))
    }

    @Test
    fun `깨진 JSON은 크래시 없이 null`() {
        assertNull(MeetingSummaryJson.fromJson("not json at all"))
        assertNull(MeetingSummaryJson.fromJson("{\"roomId\": "))
    }

    @Test
    fun `roomId 없는 JSON은 null`() {
        assertNull(MeetingSummaryJson.fromJson("""{"summary":"내용만 있음"}"""))
    }

    @Test
    fun `없는 필드는 기본값으로 - 구버전 스키마 호환`() {
        val restored = MeetingSummaryJson.fromJson("""{"roomId":"r","summary":"s"}""")!!
        assertEquals("r", restored.roomId)
        assertEquals("s", restored.summary)
        assertEquals(emptyList<RecommendedPlace>(), restored.places)
        assertEquals(emptyList<String>(), restored.activities)
        assertEquals("", restored.weather)
    }

    @Test
    fun `모르는 verification 값은 UNVERIFIED로 폴백`() {
        val json = """{"roomId":"r","places":[{"name":"곳","verification":"FUTURE_STATUS"}]}"""
        val restored = MeetingSummaryJson.fromJson(json)!!
        assertEquals(VerificationStatus.UNVERIFIED, restored.places.single().verification)
    }

    @Test
    fun `이름 없는 장소는 스킵`() {
        val json = """{"roomId":"r","places":[{"address":"주소만"},{"name":"유효한 곳"}]}"""
        val restored = MeetingSummaryJson.fromJson(json)!!
        assertEquals(listOf("유효한 곳"), restored.places.map { it.name })
    }
}
