package com.navoodi.morimi.data.local

import com.navoodi.morimi.data.model.MeetingSummary
import com.navoodi.morimi.data.model.RecommendedPlace
import com.navoodi.morimi.data.model.VerificationStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 추천 결과 저장(Room JSON 컬럼)에 좌표 필드 추가 — 구버전 데이터 호환과 깨진 값 방어. */
class MeetingSummaryJsonGeoTest {

    private fun summary(vararg places: RecommendedPlace) = MeetingSummary(roomId = "room-geo", places = places.toList())

    private fun restoreSingle(placeJson: String): RecommendedPlace? =
        MeetingSummaryJson.fromJson("""{"roomId":"room-geo","places":[$placeJson]}""")?.places?.singleOrNull()

    @Test
    fun `정상 - 좌표 저장 후 복원`() {
        val p = RecommendedPlace(name = "미미식당", address = "서울 강남구", latitude = 37.5012, longitude = 127.0396)
        val back = MeetingSummaryJson.fromJson(MeetingSummaryJson.toJson(summary(p)))!!.places.single()
        assertEquals(p, back)
    }

    @Test
    fun `좌표 없음 - JSON에 좌표 키를 쓰지 않아 구버전과 같은 형태`() {
        val json = MeetingSummaryJson.toJson(summary(RecommendedPlace(name = "미미식당")))
        val place = JSONObject(json).getJSONArray("places").getJSONObject(0)
        assertFalse(place.has("latitude"))
        assertFalse(place.has("longitude"))
    }

    @Test
    fun `구버전 데이터 - 좌표 필드 없는 저장본도 그대로 복원`() {
        val legacy = """
            {"roomId":"room-old","summary":"요약","location":"홍대","meetingDate":"2026-07-18",
             "places":[{"name":"레드버튼 홍대점","address":"서울 마포구","reason":"보드게임",
                        "placeUrl":"https://place.map.kakao.com/123","verification":"VERIFIED"}],
             "activities":["보드게임"],"itemsToBring":["우산"]}
        """.trimIndent()
        val s = MeetingSummaryJson.fromJson(legacy)
        assertNotNull(s)
        val p = s!!.places.single()
        assertEquals("레드버튼 홍대점", p.name)
        assertEquals(VerificationStatus.VERIFIED, p.verification)
        assertNull(p.latitude)
        assertNull(p.longitude)
    }

    @Test
    fun `빈 문자열·숫자 아님 - 좌표만 null, 장소는 유지`() {
        assertNull(restoreSingle("""{"name":"A","latitude":"","longitude":""}""")!!.geoPoint)
        assertNull(restoreSingle("""{"name":"A","latitude":"abc","longitude":"127.0"}""")!!.geoPoint)
        assertEquals("A", restoreSingle("""{"name":"A","latitude":"abc","longitude":"127.0"}""")!!.name)
    }

    @Test
    fun `범위 밖 - 위도 126(뒤바뀜)·999는 null`() {
        assertNull(restoreSingle("""{"name":"A","latitude":126.9,"longitude":37.5}""")!!.geoPoint)
        assertNull(restoreSingle("""{"name":"A","latitude":999,"longitude":999}""")!!.geoPoint)
    }

    @Test
    fun `이상 입력 - 한쪽만 있거나 JSON null이면 둘 다 null`() {
        val half = restoreSingle("""{"name":"A","latitude":37.5}""")!!
        assertNull(half.latitude)
        assertNull(half.longitude)
        val nul = restoreSingle("""{"name":"A","latitude":null,"longitude":null}""")!!
        assertNull(nul.geoPoint)
    }

    @Test
    fun `이상 입력 - 문자열 숫자 좌표는 숫자로 복원(타 버전이 문자열로 썼을 때)`() {
        val p = restoreSingle("""{"name":"A","latitude":"37.5","longitude":"127.0"}""")!!
        assertEquals(37.5, p.latitude!!, 1e-9)
    }

    @Test
    fun `이상 입력 - 범위 밖 좌표를 가진 모델 객체는 저장 시 좌표를 쓰지 않는다`() {
        val json = MeetingSummaryJson.toJson(summary(RecommendedPlace(name = "A", latitude = 200.0, longitude = 37.0)))
        assertFalse(JSONObject(json).getJSONArray("places").getJSONObject(0).has("latitude"))
    }
}
