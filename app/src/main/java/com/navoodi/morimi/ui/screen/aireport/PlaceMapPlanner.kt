package com.navoodi.morimi.ui.screen.aireport

import com.navoodi.morimi.data.model.RecommendedPlace
import java.net.URLEncoder

/**
 * 추천 결과 지도의 표시 결정 — Compose·WebView와 분리한 순수 Kotlin (JVM 단위 테스트 가능).
 *
 * 화면([PlaceMapView])은 이 결과를 그리기만 한다. 무엇을 보여줄지(지도 / 대체 목록 / 숨김),
 * 어떤 장소가 핀이 되는지, 지도 HTML에 무엇이 들어가는지는 전부 여기서 정한다.
 *
 * 지도 방식: WebView + 카카오맵 JavaScript SDK. 키는 BuildConfig(local.properties)에서 받고,
 * 없거나 형식이 이상하면 지도를 띄우지 않고 주소·카카오맵 링크 목록으로 대체한다.
 */

/** 지도 핀 1개. [label]은 카드 순번(1부터) — 좌표 없는 장소가 빠져도 카드 번호와 어긋나지 않는다. */
data class MapPin(val index: Int, val label: Int, val name: String, val lat: Double, val lng: Double)

/** 지도 대신 보여줄 장소 한 줄 — 좌표가 없어도 모든 장소가 들어간다. */
data class MapFallbackEntry(
    val label: Int,
    val name: String,
    val address: String,
    val link: String,
    val hasLocation: Boolean,
)

enum class MapFallbackReason(val message: String) {
    /** 장소는 있지만 좌표가 하나도 없다 */
    NO_COORDINATES("지도에 표시할 위치 정보가 없습니다"),
    /** 카카오맵 키가 없거나 형식이 맞지 않는다 */
    KEY_MISSING("지도를 불러올 수 없음 (지도 키 미설정)"),
    /** 키는 있었지만 SDK 로드·초기화가 실패하거나 시간 안에 끝나지 않았다 */
    LOAD_FAILED("지도를 불러올 수 없음"),
}

sealed interface PlaceMapState {
    /** 추천 장소 0곳 — 지도 영역 자체를 그리지 않는다 */
    data object Hidden : PlaceMapState

    data class Fallback(val reason: MapFallbackReason, val entries: List<MapFallbackEntry>) : PlaceMapState

    /** 지도 표시. [missingLocation]은 좌표가 없어 핀에서 빠진 장소 수(안내 문구용) */
    data class Ready(val pins: List<MapPin>, val html: String, val missingLocation: Int) : PlaceMapState
}

object PlaceMapPlanner {

    /** 카카오 앱 키는 32자 16진수. 다른 문자가 섞이면 HTML·URL에 넣지 않는다(주입 방지) */
    private val KEY_PATTERN = Regex("^[A-Za-z0-9]{16,64}$")

    /** SDK가 이 시간 안에 준비되지 않으면 실패로 보고 대체 목록으로 전환한다 */
    const val LOAD_TIMEOUT_MS = 10_000

    const val SDK_URL = "https://dapi.kakao.com/v2/maps/sdk.js"
    const val BRIDGE_NAME = "MorimiMap"

    fun isUsableKey(key: String?): Boolean = key != null && KEY_PATTERN.matches(key.trim())

    fun plan(places: List<RecommendedPlace>, jsKey: String?): PlaceMapState {
        if (places.isEmpty()) return PlaceMapState.Hidden
        val pins = pinsOf(places)
        if (pins.isEmpty()) return fallback(places, MapFallbackReason.NO_COORDINATES)
        if (!isUsableKey(jsKey)) return fallback(places, MapFallbackReason.KEY_MISSING)
        return PlaceMapState.Ready(pins, html(pins, jsKey!!.trim()), missingLocation = places.size - pins.size)
    }

    fun pinsOf(places: List<RecommendedPlace>): List<MapPin> = places.mapIndexedNotNull { i, p ->
        val point = p.geoPoint ?: return@mapIndexedNotNull null
        MapPin(index = i, label = i + 1, name = p.name, lat = point.latitude, lng = point.longitude)
    }

    fun fallback(places: List<RecommendedPlace>, reason: MapFallbackReason): PlaceMapState.Fallback =
        PlaceMapState.Fallback(
            reason = reason,
            entries = places.mapIndexed { i, p ->
                MapFallbackEntry(
                    label = i + 1,
                    name = p.name,
                    address = p.address,
                    link = kakaoMapLink(p),
                    hasLocation = p.geoPoint != null,
                )
            },
        )

    /**
     * 키 없이 열리는 카카오맵 링크. 장소 상세 URL → 좌표 링크 → 이름 검색 순으로 고른다.
     * (map.kakao.com/link/… 는 웹·앱 모두 열리고 API 키가 필요 없다)
     */
    fun kakaoMapLink(place: RecommendedPlace): String {
        val url = place.placeUrl.trim()
        if (url.startsWith("https://") || url.startsWith("http://")) return url
        val name = encodePath(place.name.trim().ifEmpty { "장소" })
        val point = place.geoPoint
        return if (point != null) "https://map.kakao.com/link/map/$name,${point.latitude},${point.longitude}"
        else "https://map.kakao.com/link/search/$name"
    }

    private fun encodePath(s: String): String =
        URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20").replace("%2C", "%20")

    /**
     * 지도 HTML. 장소명은 JS 문자열 리터럴로만 들어가고(escape), 화면에는 textContent로 그린다 —
     * 모델이 만든 이름에 `</script>`나 HTML이 섞여도 실행되지 않는다.
     * 핀 번호는 카드 번호와 같다. focusPin(i)로 카드 i의 핀을 강조하고, -1이면 전체를 맞춘다.
     */
    fun html(pins: List<MapPin>, key: String): String {
        val data = pins.joinToString(",", "[", "]") { p ->
            "{\"i\":${p.index},\"n\":${p.label},\"name\":${jsString(p.name)},\"lat\":${p.lat},\"lng\":${p.lng}}"
        }
        return """
<!doctype html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>
html,body,#map{margin:0;padding:0;width:100%;height:100%;background:#eef1f5}
.pin{position:relative;display:flex;align-items:center;gap:4px;padding:3px 8px;border-radius:14px;
 background:#ffffff;border:2px solid #3b6cf6;color:#3b6cf6;font:700 13px sans-serif;white-space:nowrap;
 box-shadow:0 1px 4px rgba(0,0,0,.25)}
.pin .nm{display:none;font-weight:600;color:#1d2430}
.pin.on{background:#3b6cf6;color:#ffffff;z-index:10}
.pin.on .nm{display:inline;color:#ffffff}
</style></head>
<body><div id="map"></div>
<script>
var PINS = $data;
var map = null, marks = [], bounds = null, done = false;
function report(ok, why) {
  if (done) return; done = true;
  try { if (ok) $BRIDGE_NAME.onReady(); else $BRIDGE_NAME.onError(String(why)); } catch (e) {}
}
var timer = setTimeout(function () { report(false, 'timeout'); }, $LOAD_TIMEOUT_MS);
function fitAll() {
  if (!map || marks.length === 0) return;
  if (marks.length === 1) { map.setLevel(4); map.setCenter(marks[0].pos); }
  else { map.setBounds(bounds, 40, 40, 40, 40); }
}
function focusPin(i) {
  var hit = null;
  marks.forEach(function (m) { var on = (m.i === i); m.el.className = on ? 'pin on' : 'pin'; if (on) hit = m; });
  if (hit) { map.panTo(hit.pos); } else { fitAll(); }
}
function init() {
  var first = new kakao.maps.LatLng(PINS[0].lat, PINS[0].lng);
  map = new kakao.maps.Map(document.getElementById('map'), { center: first, level: 5 });
  bounds = new kakao.maps.LatLngBounds();
  PINS.forEach(function (p) {
    var pos = new kakao.maps.LatLng(p.lat, p.lng);
    bounds.extend(pos);
    var el = document.createElement('div'); el.className = 'pin';
    var num = document.createElement('span'); num.textContent = String(p.n);
    var nm = document.createElement('span'); nm.className = 'nm'; nm.textContent = p.name;
    el.appendChild(num); el.appendChild(nm);
    el.addEventListener('click', function () { focusPin(p.i); });
    new kakao.maps.CustomOverlay({ position: pos, content: el, yAnchor: 1.2, clickable: true, zIndex: 3 }).setMap(map);
    marks.push({ i: p.i, el: el, pos: pos });
  });
  fitAll();
}
</script>
<script src="$SDK_URL?appkey=$key&autoload=false" onerror="report(false,'script')"></script>
<script>
if (typeof kakao === 'undefined' || !kakao.maps || !kakao.maps.load) { report(false, 'sdk'); }
else {
  kakao.maps.load(function () {
    try { init(); clearTimeout(timer); report(true); }
    catch (e) { clearTimeout(timer); report(false, 'init'); }
  });
}
</script>
</body></html>
""".trimIndent()
    }

    /** JS 문자열 리터럴. `<`·`>`·`&`·줄 구분자까지 \u 이스케이프해 script 블록을 벗어나지 못하게 한다. */
    internal fun jsString(s: String): String = buildString {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' || c == '<' || c == '>' || c == '&' || c == '\'' || c == ' ' || c == ' ' ->
                    append("\\u").append(String.format("%04x", c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}
