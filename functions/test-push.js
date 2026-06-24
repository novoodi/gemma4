/**
 * FCM 푸시 알림 엔드-투-엔드 테스트
 *
 * Firebase CLI access_token을 사용해 FCM v1 REST API로 직접 푸시 발송.
 * google-services.json에서 프로젝트 ID를, firebase-tools.json에서 토큰을 읽음.
 *
 * 사용법:
 *   node test-push.js <recipientUid> [roomId]
 *
 * 예시:
 *   node test-push.js 2tIQEJQO5cNoMOukL0Y95TroJRh2 myRoomId123
 */

const https = require('https');
const os    = require('os');
const path  = require('path');
const fs    = require('fs');

const PROJECT_ID = 'navoodi-e389c';

// ── 1. Firebase CLI 토큰 로드 ────────────────────────────────────────────────
function loadAccessToken() {
  const credPath = path.join(os.homedir(), '.config', 'configstore', 'firebase-tools.json');
  const json     = JSON.parse(fs.readFileSync(credPath, 'utf8'));
  const tokens   = json.tokens;
  if (!tokens?.access_token) throw new Error('firebase-tools.json에 access_token 없음');
  console.log(`✅ Firebase CLI 토큰 로드 완료 (만료: ${new Date(tokens.expires_at).toLocaleString('ko-KR')})`);
  return tokens.access_token;
}

// ── 2. Firestore REST API 헬퍼 ───────────────────────────────────────────────
function firestoreRequest(method, path, token, body = null) {
  return new Promise((resolve, reject) => {
    const base    = `firestore.googleapis.com`;
    const fullPath = `/v1/projects/${PROJECT_ID}/databases/(default)/documents${path}`;
    const opts = {
      hostname: base,
      path:     fullPath,
      method,
      headers: { 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' },
    };
    const req = https.request(opts, res => {
      let data = '';
      res.on('data', c => data += c);
      res.on('end', () => {
        try { resolve(JSON.parse(data)); }
        catch { resolve(data); }
      });
    });
    req.on('error', reject);
    if (body) req.write(JSON.stringify(body));
    req.end();
  });
}

// Firestore 필드값 파싱 (stringValue, integerValue, arrayValue …)
function parseValue(v) {
  if (!v) return null;
  if (v.stringValue  !== undefined) return v.stringValue;
  if (v.integerValue !== undefined) return parseInt(v.integerValue);
  if (v.booleanValue !== undefined) return v.booleanValue;
  if (v.arrayValue)  return (v.arrayValue.values || []).map(parseValue);
  if (v.mapValue)    return Object.fromEntries(
    Object.entries(v.mapValue.fields || {}).map(([k, val]) => [k, parseValue(val)])
  );
  return null;
}

// ── 3. FCM v1 REST API ───────────────────────────────────────────────────────
function sendFcm(token, fcmToken, title, body, data = {}) {
  return new Promise((resolve, reject) => {
    const payload = JSON.stringify({
      message: {
        token: fcmToken,
        notification: { title, body },
        data: Object.fromEntries(Object.entries(data).map(([k, v]) => [k, String(v)])),
        android: { priority: 'high', notification: { channelId: 'morimi_chat' } },
      },
    });
    const opts = {
      hostname: 'fcm.googleapis.com',
      path:     `/v1/projects/${PROJECT_ID}/messages:send`,
      method:   'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type':  'application/json',
        'Content-Length': Buffer.byteLength(payload),
      },
    };
    const req = https.request(opts, res => {
      let d = '';
      res.on('data', c => d += c);
      res.on('end', () => {
        try { resolve({ status: res.statusCode, body: JSON.parse(d) }); }
        catch { resolve({ status: res.statusCode, body: d }); }
      });
    });
    req.on('error', reject);
    req.write(payload);
    req.end();
  });
}

// ── 메인 ─────────────────────────────────────────────────────────────────────
async function main() {
  const recipientUid = process.argv[2];
  const roomId       = process.argv[3] || '';

  if (!recipientUid) {
    console.error('사용법: node test-push.js <recipientUid> [roomId]');
    process.exit(1);
  }

  const accessToken = loadAccessToken();

  // users/{uid} 문서에서 fcmTokens 배열 읽기
  console.log(`\n사용자 fcmTokens 조회: users/${recipientUid}`);
  const userDoc = await firestoreRequest('GET', `/users/${recipientUid}`, accessToken);

  const fcmTokensField = userDoc?.fields?.fcmTokens;
  const fcmTokens = fcmTokensField ? parseValue(fcmTokensField) : [];

  if (!Array.isArray(fcmTokens) || fcmTokens.length === 0) {
    console.error('❌ fcmTokens 없음. 앱을 한 번 실행해 토큰을 저장하세요.');
    process.exit(1);
  }

  console.log(`✅ FCM 토큰 ${fcmTokens.length}개 발견`);
  fcmTokens.forEach((t, i) => console.log(`   [${i}] ${t.substring(0, 30)}…`));

  // 첫 번째 토큰에 푸시 발송
  const targetToken = fcmTokens[0];
  const title = '모이미 테스트 알림 🔔';
  const body  = '탭하면 채팅방으로 이동합니다. 이 알림이 보이면 FCM 설정 성공!';
  const data  = roomId ? { roomId } : {};

  console.log(`\nFCM 푸시 발송 중…`);
  console.log(`  title: ${title}`);
  console.log(`  body:  ${body}`);
  if (roomId) console.log(`  data.roomId: ${roomId}`);

  const result = await sendFcm(accessToken, targetToken, title, body, data);
  console.log(`\n응답 상태: ${result.status}`);

  if (result.status === 200) {
    console.log('✅ 푸시 발송 성공!');
    console.log('   → 기기 화면을 확인하세요 (앱이 포그라운드일 때는 logcat을 확인)');
    if (roomId) console.log('   → 알림 탭하면 해당 채팅방으로 이동해야 합니다');
  } else {
    console.error('❌ 푸시 발송 실패:');
    console.error(JSON.stringify(result.body, null, 2));
  }
}

main().catch(err => { console.error(err); process.exit(1); });
