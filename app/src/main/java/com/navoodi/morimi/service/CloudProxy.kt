package com.navoodi.morimi.service

import android.util.Log
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 외부 API 프록시(Cloud Functions callable) 호출 게이트.
 *
 * API 키 격리 원칙: Gemini·카카오 로컬·기상청 키는 APK에 존재하지 않는다.
 * 키는 Firebase Functions 시크릿에만 있고, 앱은 이 객체를 통해 서버 프록시
 * (`functions/index.js`의 geminiGenerate / kakaoSearch / weatherMidFcst)를 부른다.
 * 프록시는 Firebase Auth 로그인 사용자만 호출할 수 있다.
 *
 * 프라이버시 방화벽과의 관계: 이 경계를 넘는 페이로드는 호출자가 이미
 * [PiiScrubber]를 통과시킨 요약문·검색어뿐이어야 한다. 채팅 원문은 여기로 오지 않는다.
 */
object CloudProxy {

    private const val TAG = "CloudProxy"
    /** functions/index.js PROXY_REGION 과 일치해야 한다. */
    private const val REGION = "asia-northeast3"

    private val functions: FirebaseFunctions by lazy { FirebaseFunctions.getInstance(REGION) }

    /** 프록시 호출 실패를 호출자가 분류할 수 있도록 code(FirebaseFunctionsException.Code 이름)를 보존 */
    class ProxyException(val fn: String, val code: String, message: String, cause: Throwable? = null) :
        Exception("[$fn] $code: $message", cause)

    /**
     * callable 함수를 호출해 JSON 객체 응답을 받는다.
     * 프록시는 업스트림 JSON을 그대로 돌려주므로 호출자는 기존 REST 응답 파싱을 재사용한다.
     */
    suspend fun callJson(
        name: String,
        data: JSONObject,
        timeoutSec: Long = 60,
    ): JSONObject = withContext(Dispatchers.IO) {
        val raw = try {
            functions.getHttpsCallable(name)
                .apply { setTimeout(timeoutSec, TimeUnit.SECONDS) }
                .call(data)
                .await()
                .data
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: FirebaseFunctionsException) {
            Log.e(TAG, "$name 실패 code=${e.code} details=${e.details}", e)
            throw ProxyException(name, e.code.name, e.message ?: "알 수 없는 오류", e)
        } catch (e: Exception) {
            Log.e(TAG, "$name 호출 예외", e)
            throw ProxyException(name, "UNKNOWN", e.message ?: "알 수 없는 오류", e)
        }
        toJsonObject(raw) ?: throw ProxyException(name, "INTERNAL", "응답이 JSON 객체가 아님: ${raw?.javaClass?.simpleName}")
    }

    /** callable 결과(Map/List/primitive 트리)를 org.json 트리로 변환 */
    private fun toJsonObject(value: Any?): JSONObject? = when (value) {
        is JSONObject -> value
        is Map<*, *> -> JSONObject().also { obj ->
            value.forEach { (k, v) -> obj.put(k.toString(), wrap(v)) }
        }
        else -> null
    }

    private fun wrap(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> toJsonObject(value)!!
        is List<*> -> JSONArray().also { arr -> value.forEach { arr.put(wrap(it)) } }
        else -> value
    }
}
