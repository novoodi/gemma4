const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { defineSecret } = require("firebase-functions/params");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");

initializeApp();

// ═══════════════════════════════════════════════════════════════════════════
// 외부 API 프록시 (API 키 서버 격리)
//
// 원칙:
//  - Gemini·카카오 로컬·기상청 키는 APK에 절대 포함하지 않는다. 키는 Secret Manager에만
//    존재하고, 앱은 아래 callable 함수를 통해서만 외부 API에 닿는다.
//  - 모든 프록시는 Firebase Auth 로그인 사용자만 호출 가능(비로그인 → unauthenticated).
//  - 프록시는 "키를 붙여 전달"만 한다. 프롬프트 조립·Function Calling 루프·Guardrail은
//    온디바이스 오케스트레이터(AgentOrchestrator)가 그대로 담당한다.
//  - Gemini 모델명은 서버 allowlist로 고정 — 클라이언트가 고가 모델을 지정해 비용을
//    유발할 수 없게 한다.
//  - 프라이버시 방화벽 불변: 여기로 오는 Gemini 페이로드는 이미 온디바이스에서
//    익명화·PII 스크러빙된 요약문뿐이다(채팅 원문은 기기 밖으로 나오지 않는다).
//
// 시크릿 등록: firebase functions:secrets:set GEMINI_API_KEY (KAKAO_REST_API_KEY, WEATHER_API_KEY)
// ═══════════════════════════════════════════════════════════════════════════

const GEMINI_API_KEY = defineSecret("GEMINI_API_KEY");
const KAKAO_REST_API_KEY = defineSecret("KAKAO_REST_API_KEY");
const WEATHER_API_KEY = defineSecret("WEATHER_API_KEY");

const PROXY_REGION = "asia-northeast3";
const GEMINI_ALLOWED_MODELS = new Set(["gemini-3.5-flash"]);
const GEMINI_MAX_BODY_BYTES = 256 * 1024; // 요약문+툴 히스토리 상한 — 폭주 페이로드 차단
const UPSTREAM_TIMEOUT_MS = 60_000;

function requireAuth(request) {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "로그인이 필요합니다.");
  }
}

function requireString(value, name, maxLen) {
  if (typeof value !== "string" || value.trim().length === 0) {
    throw new HttpsError("invalid-argument", `${name}은(는) 비어 있지 않은 문자열이어야 합니다.`);
  }
  if (maxLen && value.length > maxLen) {
    throw new HttpsError("invalid-argument", `${name} 길이 초과(${maxLen}).`);
  }
  return value.trim();
}

// 일시 오류(혼잡·레이트리밋·업스트림 장애)만 재시도 대상. 4xx 입력 오류는 즉시 반환.
const RETRYABLE_STATUS = new Set([429, 500, 503]);
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * 업스트림 호출 — HTTP 오류는 상태코드별 HttpsError로 변환해 클라이언트가 분류 가능하게.
 * opts.retries 만큼 지수 백오프(base·2^n + 지터)로 재시도한다. 총 대기는 함수 timeoutSeconds
 * 안에 들어오도록 호출자가 retries 를 정한다(Gemini: 1+2+4s, 120s 상한).
 */
async function fetchUpstream(label, url, init, opts = {}) {
  const retries = opts.retries ?? 0;
  const baseDelayMs = opts.baseDelayMs ?? 1000;
  for (let attempt = 0; ; attempt++) {
    const canRetry = attempt < retries;
    const backoff = () => baseDelayMs * 2 ** attempt + Math.floor(Math.random() * 250);

    let res;
    try {
      res = await fetch(url, { ...init, signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS) });
    } catch (e) {
      if (canRetry) {
        console.warn(`${label} 네트워크 오류 — 재시도 ${attempt + 1}/${retries}`, e?.message);
        await sleep(backoff());
        continue;
      }
      console.error(`${label} 네트워크 오류`, e);
      throw new HttpsError("unavailable", `${label} 연결 실패`);
    }

    const text = await res.text();
    if (!res.ok) {
      if (RETRYABLE_STATUS.has(res.status) && canRetry) {
        console.warn(`${label} HTTP ${res.status} — 재시도 ${attempt + 1}/${retries}: ${text.slice(0, 120)}`);
        await sleep(backoff());
        continue;
      }
      console.error(`${label} HTTP ${res.status}: ${text.slice(0, 300)}`);
      const code = res.status === 429 ? "resource-exhausted"
        : res.status >= 500 ? "unavailable"
        : "internal";
      throw new HttpsError(code, `${label} HTTP ${res.status}`, text.slice(0, 500));
    }
    try {
      return JSON.parse(text);
    } catch (e) {
      console.error(`${label} JSON 파싱 실패: ${text.slice(0, 300)}`);
      throw new HttpsError("internal", `${label} 응답 파싱 실패`);
    }
  }
}

/**
 * Gemini generateContent 프록시.
 * data: { model: string, body: GenerateContentRequest(REST 스키마 그대로) }
 * 반환: GenerateContentResponse JSON 그대로 (candidates[].content.parts[] 등)
 */
exports.geminiGenerate = onCall(
  { region: PROXY_REGION, secrets: [GEMINI_API_KEY], timeoutSeconds: 120, memory: "256MiB" },
  async (request) => {
    requireAuth(request);
    const { model, body } = request.data || {};
    const modelName = requireString(model, "model", 64);
    if (!GEMINI_ALLOWED_MODELS.has(modelName)) {
      throw new HttpsError("invalid-argument", `허용되지 않은 모델: ${modelName}`);
    }
    if (!body || typeof body !== "object" || !Array.isArray(body.contents)) {
      throw new HttpsError("invalid-argument", "body.contents 배열이 필요합니다.");
    }
    const serialized = JSON.stringify(body);
    if (serialized.length > GEMINI_MAX_BODY_BYTES) {
      throw new HttpsError("invalid-argument", "요청 본문이 너무 큽니다.");
    }
    const url = `https://generativelanguage.googleapis.com/v1beta/models/${modelName}:generateContent`;
    return fetchUpstream("Gemini", url, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-goog-api-key": GEMINI_API_KEY.value(),
      },
      body: serialized,
    }, { retries: 3, baseDelayMs: 1000 }); // 503(혼잡)·429 → 1s/2s/4s 백오프 후 최대 3회 재시도
  }
);

/**
 * 카카오 로컬 키워드 검색 프록시.
 * data: { query: string, size?: number(1..15), categoryGroupCode?: string }
 * 반환: 카카오 응답 JSON 그대로 ({ documents: [...], meta: {...} })
 */
exports.kakaoSearch = onCall(
  { region: PROXY_REGION, secrets: [KAKAO_REST_API_KEY], timeoutSeconds: 30, memory: "256MiB" },
  async (request) => {
    requireAuth(request);
    const { query, size, categoryGroupCode } = request.data || {};
    const q = requireString(query, "query", 200);
    const n = Number.isInteger(size) ? Math.min(Math.max(size, 1), 15) : 5;
    const params = new URLSearchParams({ query: q, size: String(n) });
    if (typeof categoryGroupCode === "string" && /^[A-Z0-9]{1,4}$/.test(categoryGroupCode)) {
      params.set("category_group_code", categoryGroupCode);
    }
    const url = `https://dapi.kakao.com/v2/local/search/keyword.json?${params.toString()}`;
    return fetchUpstream("KakaoLocal", url, {
      headers: { Authorization: `KakaoAK ${KAKAO_REST_API_KEY.value()}` },
    }, { retries: 1 });
  }
);

/**
 * 기상청 중기예보 프록시.
 * data: { kind: "land" | "ta", regId: string, tmFc: string(YYYYMMDDHHmm) }
 * 반환: 기상청 응답 JSON 그대로 (response.body.items.item[0] 에 예보 필드)
 */
exports.weatherMidFcst = onCall(
  { region: PROXY_REGION, secrets: [WEATHER_API_KEY], timeoutSeconds: 30, memory: "256MiB" },
  async (request) => {
    requireAuth(request);
    const { kind, regId, tmFc } = request.data || {};
    const op = kind === "land" ? "getMidLandFcst" : kind === "ta" ? "getMidTaFcst" : null;
    if (!op) throw new HttpsError("invalid-argument", "kind는 land 또는 ta 여야 합니다.");
    const reg = requireString(regId, "regId", 16);
    const tm = requireString(tmFc, "tmFc", 12);
    // 기상청 구역코드는 영문 대문자를 포함한다(예: 11B00000, 11H20201) — 숫자만 허용하면 전 요청이 거부된다
    if (!/^[0-9A-Z]{8}$/.test(reg) || !/^\d{12}$/.test(tm)) {
      throw new HttpsError("invalid-argument", "regId/tmFc 형식 오류");
    }
    const params = new URLSearchParams({
      serviceKey: WEATHER_API_KEY.value(),
      numOfRows: "10",
      pageNo: "1",
      dataType: "JSON",
      regId: reg,
      tmFc: tm,
    });
    const url = `https://apis.data.go.kr/1360000/MidFcstInfoService/${op}?${params.toString()}`;
    return fetchUpstream("KMA", url, {}, { retries: 1 });
  }
);

exports.notifyNewMessage = onDocumentCreated(
  "rooms/{roomId}/messages/{messageId}",
  async (event) => {
    const snap = event.data;
    if (!snap) return;

    const message = snap.data();
    const roomId = event.params.roomId;
    const senderId = message.senderId;
    const senderName = message.senderName || "새 메시지";
    const content = message.content || "";

    const db = getFirestore();

    // 방 정보 읽기
    const roomSnap = await db.collection("rooms").doc(roomId).get();
    if (!roomSnap.exists) return;
    const room = roomSnap.data();
    const participantUids = room.participantUids || [];
    const roomName = room.name || "채팅방";

    // 보낸 사람을 제외한 멤버들
    const recipients = participantUids.filter((uid) => uid !== senderId);
    if (recipients.length === 0) return;

    // 각 멤버의 fcmTokens 수집
    const tokens = [];
    for (const uid of recipients) {
      const userSnap = await db.collection("users").doc(uid).get();
      if (!userSnap.exists) continue;
      const userTokens = userSnap.data().fcmTokens || [];
      tokens.push(...userTokens);
    }
    if (tokens.length === 0) return;

    // 푸시 발송
    const payload = {
      notification: {
        title: roomName,
        body: `${senderName}: ${content}`,
      },
      data: {
        roomId: roomId,
      },
      android: {
        notification: {
          channelId: "morimi_chat",
        },
      },
      tokens: tokens,
    };

    const response = await getMessaging().sendEachForMulticast(payload);
    console.log(`푸시 발송: 성공 ${response.successCount} / 실패 ${response.failureCount}`);
  }
);