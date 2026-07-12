/**
 * 데모용 샘플 대화 시드 스크립트 (관리자 전용)
 *
 * 심사/데모 전에 특정 채팅방에 가상의 데모 대화를 미리 채워 넣는다.
 * Firebase CLI access_token으로 Firestore REST API를 직접 호출한다 —
 * 프로젝트 Owner OAuth 토큰은 보안 규칙을 우회하므로(admin 등가),
 * senderId를 데모 페르소나(user_00x)로 자유롭게 지정할 수 있다.
 *
 * ⚠️ 여기 담긴 대화는 전부 가상으로 창작된 것이다. 실존 인물의 실제
 *    카카오톡 대화·실명은 저장소·APK 어디에도 포함하지 않는다(프라이버시 원칙).
 *
 * 사용법:
 *   node seed-sample.js <초대코드> <데이터셋번호 0-3>
 *
 * 예시:
 *   node seed-sample.js ABC234 0
 *
 * 데이터셋:
 *   0 = 저녁 모임 (조용한 곳 선호)
 *   1 = 주말 볼링 모임
 *   2 = 회식 · 성향 파악용 (술X · 매운거X · 조용한 곳)
 *   3 = 회식 · 성향 변화 확인용 (술O · 매운거O · 시끄러운 곳)
 */

const https = require('https');
const os    = require('os');
const path  = require('path');
const fs    = require('fs');

const PROJECT_ID = 'navoodi-e389c';

// ── 데모 페르소나 (전부 가상 인물) ───────────────────────────────────────────
// 실제 Firebase uid는 28자 랜덤이라 이 고정 id와 절대 겹치지 않는다 →
// 데모 실행자(실제 로그인 계정)의 말풍선과 자연스럽게 구분된다.
const PERSONAS = {
  a: { id: 'user_001', name: '김하늘' },
  b: { id: 'user_002', name: '이도윤' },
  c: { id: 'user_003', name: '박서준' },
  d: { id: 'user_004', name: '최지우' },
  e: { id: 'user_005', name: '정민준' },
  f: { id: 'user_006', name: '한서연' },
};

// (페르소나키, 내용) 목록
const DATASETS = [
  {
    name: '저녁 모임',
    messages: [
      ['a', '이번 주말에 다 같이 저녁 먹을까?'],
      ['b', '좋아 토요일 어때'],
      ['c', '토요일 저녁 나 가능해'],
      ['d', '나도 토요일 좋아'],
      ['a', '장소는 어디가 좋을까'],
      ['b', '지난번엔 너무 시끄러워서 대화가 안 됐잖아'],
      ['b', '이번엔 좀 조용한 데로 가자'],
      ['c', '동의 조용한 곳 찬성'],
      ['d', '나 매운 건 잘 못 먹으니까 그건 참고해줘'],
      ['a', '오케이 조용하고 안 매운 데로 찾아볼게'],
      ['e', '난 뭐든 좋아 따라갈게'],
      ['b', '시간은 6시쯤?'],
      ['c', '6시 좋아'],
      ['a', '그럼 토요일 6시로 정하자'],
    ],
  },
  {
    name: '주말 볼링 모임',
    messages: [
      ['b', '다들 이번 주 되는 날 언제야'],
      ['e', '난 아무 때나 프리해'],
      ['c', '난 일요일 빼고 다 돼'],
      ['a', '나는 토요일 오후 가능'],
      ['b', '그럼 토요일 오후로 고고'],
      ['a', '볼링 어때? 오랜만에 치자'],
      ['e', '오 볼링 좋다'],
      ['d', '나도 볼링 찬성'],
      ['c', '위치는 강남쪽이 중간이려나'],
      ['a', '강남 좋아 볼링장 알아볼게'],
      ['b', '볼링 치고 저녁도 먹자'],
      ['e', '술은 난 안 마셔도 되고'],
      ['a', '오케이 4시에 강남에서 보자'],
      ['c', '콜'],
    ],
  },
  {
    name: '회식 (성향 파악)',
    messages: [
      ['a', '이번 회식 어디서 할까'],
      ['b', '난 술은 안 마실래 요즘 속이 안 좋아서'],
      ['a', '오키 무알콜로 가자'],
      ['d', '나도 술 안 마셔 운전해야 돼서'],
      ['b', '장소는 좀 조용한 데로 하자'],
      ['b', '저번에 시끄러워서 얘기가 안 됐어'],
      ['c', '맞아 조용한 곳 찬성'],
      ['d', '나 매운 거 잘 못 먹는 거 알지? 마라탕 이런 건 패스'],
      ['a', '기억하고 있어'],
      ['c', '그럼 깔끔한 한식집 어때'],
      ['b', '좋아'],
      ['a', '조용한 한식집으로 알아볼게'],
    ],
  },
  {
    name: '회식 (성향 변화 확인)',
    messages: [
      ['a', '오늘 회식 고고'],
      ['b', '오늘은 나 술 마실래! 속도 다 나았고 오랜만에 달리자'],
      ['a', '오 웬일이야'],
      ['c', '이도윤 술 마신다니 신기하네'],
      ['b', '그리고 오늘은 시끄러운 데 가도 돼 활기찬 데서 놀자'],
      ['d', '나도 오늘은 한 잔 할래 대리 부르면 되니까'],
      ['d', '그리고 나 요즘 매운 거 잘 먹어 마라탕 완전 빠졌어'],
      ['a', '오 마라탕집 갈까 그럼'],
      ['c', '시끄러운 마라탕집 고고'],
      ['b', '조아조아 술도 되는 데로'],
      ['a', '활기찬 마라탕집으로 예약할게'],
    ],
  },
];

// ── Firebase CLI 토큰 로드 ───────────────────────────────────────────────────
function loadAccessToken() {
  const credPath = path.join(os.homedir(), '.config', 'configstore', 'firebase-tools.json');
  const json     = JSON.parse(fs.readFileSync(credPath, 'utf8'));
  const tokens   = json.tokens;
  if (!tokens?.access_token) throw new Error('firebase-tools.json에 access_token 없음 — firebase login 먼저 실행');
  console.log(`✅ Firebase CLI 토큰 로드 완료 (만료: ${new Date(tokens.expires_at).toLocaleString('ko-KR')})`);
  return tokens.access_token;
}

// ── Firestore REST 헬퍼 ──────────────────────────────────────────────────────
function firestoreRequest(method, reqPath, token, body = null) {
  return new Promise((resolve, reject) => {
    const fullPath = `/v1/projects/${PROJECT_ID}/databases/(default)/documents${reqPath}`;
    const opts = {
      hostname: 'firestore.googleapis.com',
      path:     fullPath,
      method,
      headers: { 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' },
    };
    const req = https.request(opts, res => {
      let data = '';
      res.on('data', c => data += c);
      res.on('end', () => {
        let parsed;
        try { parsed = JSON.parse(data); } catch { parsed = data; }
        resolve({ status: res.statusCode, body: parsed });
      });
    });
    req.on('error', reject);
    if (body) req.write(JSON.stringify(body));
    req.end();
  });
}

function parseValue(v) {
  if (!v) return null;
  if (v.stringValue    !== undefined) return v.stringValue;
  if (v.integerValue   !== undefined) return parseInt(v.integerValue);
  if (v.timestampValue !== undefined) return v.timestampValue;
  return null;
}

// ── 메인 ─────────────────────────────────────────────────────────────────────
async function main() {
  const inviteCode = process.argv[2];
  const datasetIdx = parseInt(process.argv[3], 10);

  if (!inviteCode || Number.isNaN(datasetIdx)) {
    console.error('사용법: node seed-sample.js <초대코드> <데이터셋번호 0-3>');
    process.exit(1);
  }
  const dataset = DATASETS[datasetIdx];
  if (!dataset) {
    console.error(`❌ 데이터셋 ${datasetIdx} 없음 (0-${DATASETS.length - 1} 범위)`);
    process.exit(1);
  }

  const token = loadAccessToken();

  // 1) 초대코드 → roomId
  const code = inviteCode.trim().toUpperCase();
  console.log(`\n초대코드 조회: inviteCodes/${code}`);
  const codeDoc = await firestoreRequest('GET', `/inviteCodes/${code}`, token);
  const roomId = codeDoc.body?.fields?.roomId ? parseValue(codeDoc.body.fields.roomId) : null;
  if (!roomId) {
    console.error(`❌ 초대코드 ${code}에 해당하는 방을 찾을 수 없습니다.`);
    process.exit(1);
  }
  console.log(`✅ roomId = ${roomId}`);

  // 2) 메시지 순차 생성 (timestamp를 1초씩 증가시켜 정렬 유지)
  console.log(`\n"${dataset.name}" 데이터셋 시드 시작 — 메시지 ${dataset.messages.length}건`);
  const base = Date.now();
  let lastContent = '';
  let lastIso = '';
  for (let i = 0; i < dataset.messages.length; i++) {
    const [personaKey, content] = dataset.messages[i];
    const persona = PERSONAS[personaKey];
    const iso = new Date(base + i * 1000).toISOString();
    const res = await firestoreRequest('POST', `/rooms/${roomId}/messages`, token, {
      fields: {
        senderId:   { stringValue: persona.id },
        senderName: { stringValue: persona.name },
        content:    { stringValue: content },
        timestamp:  { timestampValue: iso },
      },
    });
    if (res.status !== 200) {
      console.error(`❌ 메시지 ${i} 생성 실패 (HTTP ${res.status}):`, JSON.stringify(res.body));
      process.exit(1);
    }
    lastContent = content;
    lastIso = iso;
    process.stdout.write(`\r   ${i + 1}/${dataset.messages.length} 전송…`);
  }
  console.log('');

  // 3) 방 메타(lastMessage/lastMessageTime) 갱신
  const patch = await firestoreRequest(
    'PATCH',
    `/rooms/${roomId}?updateMask.fieldPaths=lastMessage&updateMask.fieldPaths=lastMessageTime`,
    token,
    {
      fields: {
        lastMessage:     { stringValue: lastContent },
        lastMessageTime: { timestampValue: lastIso },
      },
    },
  );
  if (patch.status !== 200) {
    console.error(`⚠️ 방 메타 갱신 실패 (HTTP ${patch.status}) — 메시지는 이미 기록됨`);
  }

  console.log(`\n✅ 시드 완료 — 방 "${roomId}"에 "${dataset.name}" ${dataset.messages.length}건 기록`);
  console.log('   → 앱에서 해당 방을 열면 대화가 표시되고, 10개 단위 성향 압축이 자동 트리거됩니다.');
}

main().catch(err => { console.error(err); process.exit(1); });
