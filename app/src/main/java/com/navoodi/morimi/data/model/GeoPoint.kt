package com.navoodi.morimi.data.model

/**
 * WGS84 좌표. 카카오 로컬 검색의 x(경도)·y(위도)와 지도 표시에 쓴다 (순수 Kotlin — JVM 테스트 가능).
 *
 * 외부 값(카카오 응답·저장된 JSON)은 신뢰하지 않는다: 숫자가 아니거나, NaN·무한대이거나,
 * 위경도 범위를 벗어나거나, (0, 0)이면 좌표가 없는 것으로 본다. (0, 0)은 기니만 해상이라
 * 국내 장소일 수 없고, 값이 비어 0으로 채워진 경우가 대부분이다. x·y를 뒤바꿔 넣은 값도
 * 위도가 범위를 벗어나(경도 127 → 위도 127) 여기서 걸러진다.
 */
data class GeoPoint(val latitude: Double, val longitude: Double) {
    companion object {
        fun of(latitude: Double?, longitude: Double?): GeoPoint? {
            if (latitude == null || longitude == null) return null
            if (!latitude.isFinite() || !longitude.isFinite()) return null
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
            if (latitude == 0.0 && longitude == 0.0) return null
            return GeoPoint(latitude, longitude)
        }

        /** 카카오 documents의 문자열 x(경도)·y(위도). 빈 값·파싱 실패·범위 밖은 null. */
        fun fromKakao(x: String?, y: String?): GeoPoint? =
            of(latitude = y?.trim()?.toDoubleOrNull(), longitude = x?.trim()?.toDoubleOrNull())
    }
}
