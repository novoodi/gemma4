package com.navoodi.morimi.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 좌표 검증 — 카카오 x/y 문자열과 저장된 값은 신뢰하지 않는다. */
class GeoPointTest {

    // ── 정상 ──────────────────────────────────────────────

    @Test
    fun `정상 - 카카오 x는 경도, y는 위도`() {
        assertEquals(GeoPoint(37.5571, 126.9245), GeoPoint.fromKakao(x = "126.9245", y = "37.5571"))
    }

    @Test
    fun `정상 - 앞뒤 공백은 무시`() {
        assertEquals(GeoPoint(35.1587, 129.1604), GeoPoint.fromKakao(" 129.1604 ", "\t35.1587\n"))
    }

    @Test
    fun `정상 - 범위 경계값(±90, ±180)은 허용`() {
        assertEquals(GeoPoint(90.0, 180.0), GeoPoint.of(90.0, 180.0))
        assertEquals(GeoPoint(-90.0, -180.0), GeoPoint.of(-90.0, -180.0))
    }

    // ── 좌표 없음 · 빈 문자열 ─────────────────────────────

    @Test
    fun `좌표 없음 - null이면 null`() {
        assertNull(GeoPoint.fromKakao(null, null))
        assertNull(GeoPoint.fromKakao("126.9", null))
        assertNull(GeoPoint.of(37.5, null))
    }

    @Test
    fun `빈 문자열 - 둘 중 하나라도 비면 null`() {
        assertNull(GeoPoint.fromKakao("", ""))
        assertNull(GeoPoint.fromKakao("126.9245", ""))
        assertNull(GeoPoint.fromKakao("   ", "37.55"))
    }

    // ── 숫자 아님 ─────────────────────────────────────────

    @Test
    fun `숫자 아님 - 문자·쉼표 소수점·단위가 섞이면 null`() {
        assertNull(GeoPoint.fromKakao("abc", "37.5"))
        assertNull(GeoPoint.fromKakao("126,92", "37,55"))
        assertNull(GeoPoint.fromKakao("126.9°", "37.5N"))
        assertNull(GeoPoint.fromKakao("null", "undefined"))
    }

    @Test
    fun `숫자 아님 - NaN·Infinity 문자열도 null`() {
        assertNull(GeoPoint.fromKakao("NaN", "37.5"))
        assertNull(GeoPoint.fromKakao("126.9", "Infinity"))
        assertNull(GeoPoint.of(Double.NaN, 126.9))
        assertNull(GeoPoint.of(37.5, Double.NEGATIVE_INFINITY))
    }

    // ── 범위 밖 ───────────────────────────────────────────

    @Test
    fun `범위 밖 - 위도 90 초과·경도 180 초과는 null`() {
        assertNull(GeoPoint.of(90.0001, 126.9))
        assertNull(GeoPoint.of(37.5, 180.0001))
        assertNull(GeoPoint.of(-91.0, 0.5))
    }

    @Test
    fun `범위 밖 - x·y를 뒤바꿔 넣으면 위도 126이 되어 null`() {
        assertNull(GeoPoint.fromKakao(x = "37.5571", y = "126.9245"))
    }

    @Test
    fun `범위 밖 - (0,0)은 값 누락으로 보고 null`() {
        assertNull(GeoPoint.fromKakao("0", "0"))
        assertNull(GeoPoint.of(0.0, 0.0))
    }
}
