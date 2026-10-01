package com.navoodi.morimi.ui.screen.aireport

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.navoodi.morimi.BuildConfig
import com.navoodi.morimi.data.model.RecommendedPlace
import com.navoodi.morimi.ui.theme.MoColors

/**
 * 추천 장소 지도 — 결과 화면 상단에 항상 펼쳐져 있다(버튼을 눌러야 보이는 구조가 아님).
 * 모든 추천 장소를 한 지도에 번호 핀으로 찍고, [focusedIndex] 카드의 핀을 강조한다.
 *
 * 무엇을 보여줄지는 [PlaceMapPlanner]가 정한다. 키가 없거나, 좌표가 하나도 없거나,
 * SDK 로드가 실패·지연되면 앱을 멈추지 않고 주소·카카오맵 링크 목록으로 대체한다.
 */
@Composable
fun PlaceMapView(
    places: List<RecommendedPlace>,
    focusedIndex: Int?,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
) {
    val plan = remember(places) { PlaceMapPlanner.plan(places, BuildConfig.KAKAO_MAP_JS_KEY) }
    // SDK가 실제로 실패하면 Ready → 대체 목록으로 내려간다(장소가 바뀌면 다시 시도)
    var loadFailed by remember(places) { mutableStateOf(false) }

    val shown: PlaceMapState =
        if (plan is PlaceMapState.Ready && loadFailed) PlaceMapPlanner.fallback(places, MapFallbackReason.LOAD_FAILED)
        else plan

    val frame = modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .border(1.dp, MoColors.border, RoundedCornerShape(16.dp))
        .background(MoColors.surfaceCard)

    when (shown) {
        PlaceMapState.Hidden -> Unit
        is PlaceMapState.Fallback -> MapFallback(shown, frame.heightIn(max = height))
        is PlaceMapState.Ready -> Column(frame) {
            KakaoMapWebView(
                html = shown.html,
                focusedIndex = focusedIndex,
                onFailed = { loadFailed = true },
                modifier = Modifier.fillMaxWidth().height(height),
            )
            if (shown.missingLocation > 0) {
                Text(
                    "위치 정보가 없는 ${shown.missingLocation}곳은 지도에서 제외했어요",
                    fontSize = 11.sp,
                    color = MoColors.textTertiary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** JS → Kotlin 신호. JavaBridge 스레드에서 불리므로 메인 스레드로 넘긴다. */
class PlaceMapBridge(private val onReady: () -> Unit, private val onError: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onReady() { main.post(onReady) }

    @JavascriptInterface
    fun onError(reason: String) { main.post { onError(reason) } }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun KakaoMapWebView(
    html: String,
    focusedIndex: Int?,
    onFailed: () -> Unit,
    modifier: Modifier,
) {
    // html로 키를 걸지 않는다 — factory의 브리지 콜백이 이 상태 하나만 보게 하고, 새 HTML을 실을 때 update에서 내린다
    var ready by remember { mutableStateOf(false) }
    val latestOnFailed by rememberUpdatedState(onFailed)
    var webView by remember { mutableStateOf<WebView?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                // 우리 HTML 밖으로 이동하지 않는다 — 지도 안의 링크 클릭 등은 무시
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                }
                addJavascriptInterface(
                    PlaceMapBridge(
                        onReady = { ready = true },
                        onError = { latestOnFailed() },
                    ),
                    PlaceMapPlanner.BRIDGE_NAME,
                )
                webView = this
            }
        },
        update = { view ->
            if (view.tag != html) {
                view.tag = html
                ready = false
                view.loadDataWithBaseURL(BuildConfig.KAKAO_MAP_ORIGIN, html, "text/html", "utf-8", null)
            }
        },
        onRelease = { view ->
            view.removeJavascriptInterface(PlaceMapPlanner.BRIDGE_NAME)
            view.destroy()
            webView = null
        },
    )

    // 카드를 넘기면 해당 장소 핀을 강조, 장소 카드가 아니면 전체 보기
    LaunchedEffect(focusedIndex, ready) {
        if (ready) webView?.evaluateJavascript("focusPin(${focusedIndex ?: -1})", null)
    }
}

@Composable
private fun MapFallback(state: PlaceMapState.Fallback, modifier: Modifier) {
    val context = LocalContext.current
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("🗺️ ${state.reason.message}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MoColors.textSecondary)
        state.entries.forEach { e ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(e.link)))
                        } catch (_: Exception) {}
                    }
                    .padding(vertical = 4.dp),
            ) {
                PinBadge(e.label)
                Column(Modifier.weight(1f)) {
                    Text(e.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MoColors.textPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        e.address.ifBlank { "주소 정보 없음" },
                        fontSize = 12.sp, color = MoColors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text("카카오맵 ›", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MoColors.brand)
            }
        }
    }
}

/** 지도 핀과 같은 번호 배지 — 카드·대체 목록에서 핀과 짝을 맞춘다 */
@Composable
fun PinBadge(label: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(MoColors.brand),
        contentAlignment = Alignment.Center,
    ) {
        Text("$label", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MoColors.textOnBrand)
    }
}
