# 모이미 — 모임 AI 비서

친구들과 나눈 채팅 대화를 **온디바이스 AI(Gemma 4)** 가 익명화·요약하고, **Gemini API** 가 참여자 성향 프로필과 과거 피드백을 반영하여 장소·활동·준비물을 추천해주는 Android 앱입니다.

채팅 원문은 스마트폰 밖으로 절대 나가지 않습니다. Gemma 4가 개인정보를 제거한 요약문만 클라우드로 전송하는 **온디바이스 프라이버시 방화벽** 구조를 채택하고 있습니다.

---

## 주요 기능

| 기능 | 설명 |
|------|------|
| **Google 로그인** | Firebase Auth 기반 Google 소셜 로그인 |
| **모임방 생성** | 방 이름으로 채팅방 생성, 6자리 초대코드 자동 발급 |
| **초대코드 입장** | 6자리 코드로 기존 방 참여 |
| **실시간 채팅** | Firestore 실시간 구독 기반 채팅 (앱 재시작 후에도 기록 유지) |
| **안읽음 표시** | 마지막 읽은 시각 기준 안읽은 메시지 수 뱃지 표시 |
| **FCM 푸시 알림** | 백그라운드 상태에서도 새 메시지 알림 수신 |
| **방 나가기** | 혼자면 방·메시지·초대코드 일괄 삭제, 멤버가 있으면 본인만 퇴장 |
| **프라이버시 방화벽** | Gemma 4가 온디바이스에서 채팅 원문을 익명화 요약 + `PiiScrubber`가 전송 직전 이름·연락처를 결정론적으로 마스킹 — 원문·개인정보는 디바이스 밖으로 전송되지 않음 |
| **성향 프로필 압축** | Gemma 4가 백그라운드에서 채팅 10개마다 선호도·가용성을 JSON으로 압축, Room DB에 증분 병합 저장 |
| **온디바이스 시맨틱 RAG** | EmbeddingGemma-300m이 후기를 기기 안에서 임베딩·검색 — 지난 모임 취향을 새 톡방 추천에 반영(사용자 단위). 앱 시작 시 임베딩 누락 후기를 백그라운드로 일괄 재인덱싱 |
| **AI 개인화 추천** | Gemini가 Gemma 요약문 + 참여자 성향 프로필(Room DB) + 관련 과거 후기(RAG)를 반영해 맞춤 추천 |
| **자율 검증 하네스** | Guardrail이 장소 영업 여부를 팩트 체크(하드 게이트), Reflection이 추천의 사용자 제약(싫어요) 위반을 자기비평(소프트 게이트) — 실패 시 피드백 누적 후 최대 3회 자가 수정 재시도 |
| **추천 결과 재확인** | AI 추천 결과를 Room에 영속 저장 — 앱 재시작 후에도 채팅방의 "지난 추천 보기"로 다시 열람 |
| **날씨 조회** | 기상청 단기예보 API로 모임 당일 날씨 자동 확인 |
| **모임 후기 저장** | 채팅방 재진입 시 후기 팝업 → 온디바이스 임베딩 인덱싱 → 다음 추천에 시맨틱 반영 |
| **인앱 캘린더** | 확정된 모임 일정을 앱 내 캘린더에 추가 |

---

## 프라이버시 방화벽 아키텍처

채팅 원문이 클라우드로 직접 전송되는 구조적 결함을 해소하기 위해, Gemma 4가 1차 익명화 요약을 온디바이스에서 수행합니다.

```
[채팅 원문] ──→ Gemma 4 (온디바이스)
                 · 이름 등 개인정보 제거
                 · 날짜·장소·목적 중심 2~3문장 요약
                         │
                 PiiScrubber (결정론적 마스킹 게이트)
                 · 이름·전화·이메일을 규칙 기반으로 최종 마스킹
                 · 요약문과 RAG 후기 모두 이 게이트를 통과
                         │
          ┌──── 디바이스 경계 ────────────────┐
          │   클라우드로 전송되는 것:          │
          │   "이번 토요일 서울 서북부 지역에서  │
          │    식사 모임을 진행할 예정입니다."   │
          └─────────────────────────────────┘
                         │
                         ▼
                  Gemini API (클라우드)
                   · 날짜 YYYY-MM-DD 변환
                   · getWeather 도구 호출
                   · searchPlace 도구 호출
                   · 최종 추천 생성
```

---

## AI 파이프라인

### Step 1 — Room DB (참여자 성향 영구 저장)

채팅 분석 결과를 `user_status` 테이블에 roomId 단위로 Upsert합니다.

```
UserStatusEntity
├── roomId       (PK — 채팅방 ID)
├── participants (참여자 이름 목록)
├── preferences  (선호/불호 항목)
├── availability (가능 일정·시간대)
└── lastUpdated  (마지막 압축 시각)
```

### Step 2 — Gemma 4 온디바이스 압축 (성향 추출)

채팅 내용을 구조화된 JSON으로 압축합니다. 네트워크 불필요, 완전 온디바이스 실행.

```json
{
  "participants": ["이승현", "양예찬", "차민영"],
  "preferences": ["좋아요: 조용한 곳", "싫어요: 시끄러운 술집"],
  "availability": ["토요일 오후 가능", "주중 저녁 불가"]
}
```

> **constrained decoding**: `record_status` 툴 스키마로 출력을 문법 제약(LiteRT-LM
> `enableConversationConstrainedDecoding`)해, 깨진 JSON을 구조적으로 차단합니다. 툴콜이
> 나오지 않으면 자유텍스트 프롬프트 + 정규식 repair로 폴백합니다.

### Step 3 — 백그라운드 자동 트리거

채팅 메시지가 10개 단위로 쌓일 때마다 압축을 자동 실행합니다.
`compressionJob.join()`으로 이야기 정리 버튼 클릭 시 압축 완료를 보장합니다.

### Step 4 — Gemma 4 프라이버시 요약 (온디바이스)

`summarizeForPrivacy()`가 채팅 원문에서 개인정보를 제거한 2~3문장 요약문을 생성합니다.
이 요약문만 클라우드로 전송되며, 채팅 원문은 디바이스 외부로 나가지 않습니다.

```
입력 (디바이스 내부):
  [양예찬]: 이번 토요일 홍대에서 저녁 먹을까?
  [차민영]: 좋아! 조용한 곳으로 찾아봐

출력 (Gemini에 전달되는 요약):
  이번 토요일 서울 서북부(홍대 인근)에서 식사 모임을 진행할 예정입니다.
  조용한 분위기를 선호하며, 구체적인 시간과 인원은 조율 중입니다.
```

### Step 5 — Gemini 개인화 추천 (온디바이스 RAG + UserStatus + Function Calling)

Gemma 요약문 + Room DB 성향 프로필 + **EmbeddingGemma로 회수한 과거 후기**를 프롬프트에 주입합니다.
클라우드로 나가는 모든 텍스트(요약문·후기)는 전송 직전 `PiiScrubber`(결정론적 마스킹)를 통과합니다.

```
[Gemma 1차 요약]                     ← Gemma 익명화 요약 (개인정보 없음)
  이번 토요일 서울 서북부에서 식사 모임 예정.

[참여자 성향 프로필 — 최우선 반영]    ← Room DB (UserStatus)
  선호: 좋아요: 조용한 곳
  일정: 토요일 오후 가능

[관련 후기 — 온디바이스 시맨틱 검색]  ← EmbeddingGemma RAG (사용자 전체 후기에서 top-k 회수)
  [2026-06-17] 조용한 카페가 대화하기 좋았어   ← 다른 방에서 남긴 후기도 취향으로 반영

→ getWeather("서울", "2026-06-27") 호출
→ searchPlace("홍대 조용한 레스토랑", "서울") 호출
→ 맞춤 추천 생성
```

> **온디바이스 RAG**: 후기를 EmbeddingGemma-300m(768차원, raw LiteRT)로 임베딩해 Room에 저장하고,
> 추천 시 요약문과의 코사인 유사도로 관련 후기를 회수합니다. 임베딩·검색이 전부 기기 안에서
> 일어나 **"후기조차 기기 밖으로 나가지 않습니다"**. 검색은 방 단위가 아니라 **사용자 단위**라,
> 지난 모임에서 좋았던 취향이 새 톡방 추천에도 누적 반영됩니다. (모델 미다운로드 시 키워드 폴백)

### Step 6 — 자율 검증 하네스 (Guardrail + Reflection)

Gemini 추천 결과를 두 계층으로 검증합니다. 검증 실패 시 피드백을 컨텍스트에 누적하고
최대 3회 자가 수정 재시도합니다.

- **Guardrail (하드 게이트)** — `GuardrailService`가 추천 장소의 영업 여부를 카카오 로컬 API로
  팩트 체크. 존재하지 않는 장소(CLOSED)는 재시도로 걸러내고, 검증 불가(UNKNOWN)는
  "검증됨"과 구분해 정직하게 표기(fail-open 금지).
- **Reflection (소프트 게이트)** — `ReflectionService`가 추천(장소명+이유, 활동)이 사용자
  성향 프로필의 "싫어요:" 제약을 위반하는지 결정론적으로 자기비평. 프롬프트 지시는 1차 방어일
  뿐, 집행은 이 게이트가 한다(`PiiScrubber` 철학의 확장). 장소가 유효한 결과는 소프트 제약을
  못 맞춰도 폴백으로 보존해 총 실패를 막는다.

```
Gemini 결과 → Guardrail.verify() + Reflection.reflect()
  둘 다 통과 → OrchestratorResult.Success → UI 표시
  실패 → 피드백 누적(장소 교체 / 싫어요 제외) → Gemini 재시도 (최대 3회)
```

### Step 7 — 추천 결과 영속 + 재확인 진입점

검증을 통과한 추천 결과(`MeetingSummary`)를 `meeting_summary` 테이블(JSON 직렬화 컬럼)에
저장합니다. 앱 시작 시 복원되어, 앱을 재시작한 뒤에도 채팅방의 **"지난 추천 보기"** 칩으로
지난 추천을 다시 열람할 수 있습니다.

### Step 8 — 후기 저장 + 온디바이스 임베딩 인덱싱

모임 후 **채팅방에 다시 들어오면 후기 팝업**이 떠서 후기를 입력받습니다. 저장 시 텍스트를 즉시
Room에 넣고, EmbeddingGemma로 벡터를 인덱싱합니다(임베딩은 앱 스코프에서 수행 — 화면을 벗어나도
끝까지 완료). 임베딩에 실패해 벡터가 비어 있던 후기는 다음 앱 시작 시 일괄 재인덱싱으로 복구됩니다.
후기는 폰 로컬(=사용자별)에 쌓여, 다음 추천 때 Step 5의 시맨틱 검색으로 회수됩니다.

---

## 아키텍처

```
app/
├── MoimApp.kt                    # Application — 싱글톤 초기화 및 의존성 주입
├── MainActivity.kt               # NavHost 라우팅
├── navigation/Screen.kt          # sealed class 라우트 정의
│
├── data/
│   ├── local/
│   │   ├── AppDatabase.kt        # Room DB 싱글톤 (moim_database, v4 — user_status/feedback/recommended_room/meeting_summary)
│   │   ├── UserStatusEntity.kt   # user_status 테이블 엔티티
│   │   ├── UserStatusDao.kt      # @Insert(onConflict=REPLACE) / @Query DAO
│   │   └── Converters.kt         # List<String> ↔ String ("||" 구분자) TypeConverter
│   ├── model/
│   │   ├── Message.kt            # 채팅 메시지
│   │   ├── ChatRoom.kt           # 모임방 (inviteCode, lastMessage, lastMessageTime 포함)
│   │   ├── MeetingSummary.kt     # AI 분석 결과 (요약/장소/날짜/추천/날씨, Room 영속)
│   │   └── CalendarEvent.kt      # 캘린더 일정
│   ├── pipeline/
│   │   ├── OnDeviceLlmPort.kt    # 온디바이스 LLM 교체 계약 (compress / summarizeForPrivacy)
│   │   ├── MockOnDeviceLlm.kt    # 키워드 탐지 기반 Mock 구현
│   │   ├── GemmaOnDeviceLlm.kt   # LlmService 위임 구현
│   │   └── StatusCompressionPipeline.kt  # 압축 트리거·파싱·Room Upsert 오케스트레이션
│   └── repository/
│       ├── ChatRepository.kt     # Firestore 실시간 구독 (방·메시지·읽음 시각·초대코드) + 요약 인메모리 캐시
│       ├── UserStatusRepository.kt # UserStatus Room DB 접근
│       ├── CalendarRepository.kt # 일정 인메모리 StateFlow
│       ├── FeedbackRepository.kt # 모임 후기 Room 저장 + 온디바이스 임베딩 인덱싱·재인덱싱
│       ├── SummaryRepository.kt  # AI 추천 결과(MeetingSummary) Room 영속 (JSON 컬럼)
│       └── FcmRepository.kt      # FCM 토큰 Firestore 저장
│
├── service/
│   ├── LlmService.kt             # Gemma 4 엔진 초기화·compress·summarizeForPrivacy
│   ├── EmbeddingGemmaEmbedder.kt # EmbeddingGemma 임베더 (raw LiteRT + DJL 토크나이저)
│   ├── PiiScrubber.kt            # 클라우드 전송 직전 결정론적 PII 마스킹 게이트 (순수 Kotlin)
│   ├── AgentOrchestrator.kt      # 프라이버시 방화벽 + Gemini Function Calling + 하네스 루프
│   ├── GuardrailService.kt       # 장소 영업 여부 팩트 체크 + 피드백 생성 (하드 게이트)
│   ├── ReflectionService.kt      # 추천의 사용자 제약(싫어요) 위반 자기비평 (소프트 게이트, 순수 Kotlin)
│   ├── FcmService.kt             # FCM 토큰 갱신 및 포그라운드 푸시 알림 표시
│   ├── ModelDownloadService.kt   # HF에서 모델 다운로드 (Foreground Service, Range 이어받기)
│   └── WeatherService.kt         # 기상청 단기예보 API 연동
│
└── ui/
    ├── screen/
    │   ├── splash/               # 스플래시 (로그인 상태 분기)
    │   ├── login/                # Google 로그인 (Firebase Auth)
    │   ├── onboarding/           # 온보딩
    │   ├── home/                 # 모임방 목록 + 생성 다이얼로그 + 안읽음 뱃지
    │   ├── joinroom/             # 초대코드 입장
    │   ├── chat/                 # 실시간 채팅 + AI 요약 트리거 + 백그라운드 압축
    │   ├── ailoading/            # AI 파이프라인 진행 상태 + 검증 하네스 디버그 로그
    │   ├── aireport/             # AI 추천 결과 카드 (장소·활동·준비물, 캘린더 담기)
    │   ├── summary/              # AI 분석 결과 카드 + 피드백 입력
    │   ├── calendar/             # 인앱 캘린더
    │   ├── vote/                 # 투표 화면
    │   ├── modeldownload/        # Gemma 모델 다운로드 진행 UI
    │   └── profile/              # 마이페이지 / 프로필 편집
    └── theme/                    # Material 3 테마
```

**패턴**: MVVM + Repository
**상태 관리**: `StateFlow` / `collectAsStateWithLifecycle`
**백엔드**: Firebase Firestore (방·메시지) + Firebase Auth (인증) + FCM (푸시)

---

## 동작 흐름

```
[Google 로그인]
        │
        ▼
[홈 — 내 채팅방 목록 (Firestore 실시간)]
  안읽음 메시지 수 뱃지 표시
        │
        ├── [+ 버튼] 방 생성 → 6자리 초대코드 자동 발급
        ├── [코드 입장] 초대코드로 기존 방 참여
        │
        ▼
[채팅방 (Firestore 실시간)]
        │
        ├──(10개 단위마다, 백그라운드)──────────────────────────────┐
        │                                                          ▼
        │                                          [Gemma 4 — 성향 압축 (온디바이스)]
        │                                      채팅 → participants/preferences/availability JSON
        │                                                          │
        │                                              [Room DB Upsert]
        │                                          user_status 테이블에 저장
        │                                                          │
        ▼                                                          │
[이야기 정리 버튼 클릭]                                             │
        │                                                          │
        ├── compressionJob.join() ← 압축 완료까지 대기 ─────────────┘
        │
        ├── Room DB에서 UserStatus 로드
        │
        ▼
[Gemma 4 — summarizeForPrivacy (온디바이스)]
  채팅 원문 → 개인정보 제거 → 2~3문장 익명화 요약
  ※ 이 단계까지 원문은 디바이스 밖으로 나가지 않음
        │
        ▼
[Gemini API — 요약문만 수신]
  Gemma 요약 + UserStatus(성향 프로필) + 과거 피드백(RAG)
  → getWeather 도구 호출 (날짜 YYYY-MM-DD 자동 변환)
  → searchPlace 도구 호출
  → 장소/활동/준비물 추천 생성
        │
        ▼
[검증 하네스 — Guardrail + Reflection]
  Guardrail: 장소 영업 여부 팩트 체크 (하드 게이트)
  Reflection: 사용자 제약(싫어요) 위반 자기비평 (소프트 게이트)
  실패 → 피드백 주입 후 Gemini 재시도 (최대 3회)
  통과 → OrchestratorResult.Success
        │
        ▼
[SummaryRepository — Room 영속]
  meeting_summary 테이블에 추천 결과 저장 → "지난 추천 보기"로 재확인
        │
        ▼
[AIReportScreen]
  요약 / 날짜 / 장소 / 날씨 / AI 추천 카드 표시
        │
        ▼
[모임 후기 남기기] ← 사용자가 텍스트 피드백 입력
        │
        ▼
[FeedbackRepository.append()]
  Room 저장 + 온디바이스 임베딩 인덱싱 → 다음 모임 추천 시 시맨틱 회수
```

---

## 기술 스택

| 항목 | 내용 |
|------|------|
| 언어 | Kotlin 2.2 |
| UI | Jetpack Compose + Material 3 |
| 네비게이션 | Navigation Compose 2.8 |
| 온디바이스 LLM | Google AI Edge LiteRTLM 0.11 (Gemma 4 — 요약·성향 압축) |
| 온디바이스 임베딩 | EmbeddingGemma-300m via LiteRT 1.4 + DJL 토크나이저 (후기 RAG) |
| 클라우드 LLM | Google GenAI SDK 1.56.0 (Gemini) |
| 로컬 DB | Room 2.6.1 (KSP 2.2.21-2.0.4) |
| 클라우드 DB | Firebase Firestore (방·메시지 실시간 구독) |
| 인증 | Firebase Auth (Google 소셜 로그인) |
| 푸시 알림 | Firebase Cloud Messaging (FCM) |
| 개인화 방식 | UserStatus(Room) + 온디바이스 시맨틱 RAG(EmbeddingGemma, 사용자 단위 후기) |
| 프라이버시 | 온디바이스 Gemma 익명화 요약 + PiiScrubber 마스킹 게이트 → 원문·후기 미전송 |
| 날씨 API | 기상청 단기예보(VilageFcst) v2.0 |
| 최소 SDK | Android 14 (API 34) |
| 빌드 도구 | AGP 8.11 / Gradle 8.13 |

---

## 시작하기

### 1. Firebase 설정

Firebase Console에서 `google-services.json`을 다운로드하여 `app/` 디렉터리에 배치합니다.

> `google-services.json`은 `.gitignore`에 포함되어 있어 Git에 업로드되지 않습니다.

### 2. 모델 파일 배치

앱이 사용하는 Gemma 4 모델 파일(`gemma-4-E2B-it.litertlm`)을 디바이스의 다음 경로에 넣어주세요:

```
/Android/data/com.navoodi.morimi/files/models/gemma-4-E2B-it.litertlm
```

> 모델 파일이 없으면 Mock 구현(`MockOnDeviceLlm`)이 자동으로 사용됩니다.

### 3. API 키 설정

`local.properties`에 다음 키를 추가합니다:

```properties
# 기상청 단기예보 (https://www.data.go.kr)
WEATHER_API_KEY=발급받은_기상청_API_키

# Google Gemini API (https://aistudio.google.com/app/apikey)
GEMINI_API_KEY=발급받은_Gemini_API_키
```

> `local.properties`는 `.gitignore`에 포함되어 있어 Git에 업로드되지 않습니다.
> Gemini API 키 없이도 앱은 실행되며, Mock 추천 텍스트가 표시됩니다.

### 4. 빌드 및 실행

```bash
./gradlew assembleDebug
```

또는 Android Studio에서 Run(▶)을 누릅니다.

---

## 사용법

1. **Google 계정으로 로그인**합니다.
2. **+** 버튼으로 모임방을 만들면 6자리 초대코드가 자동 발급됩니다.
3. 초대코드를 친구에게 공유하거나, **코드 입장** 버튼으로 기존 방에 참여합니다.
4. 채팅방에서 메시지를 입력하면 Firestore에 실시간 저장되고, 상대방 화면에도 즉시 표시됩니다.
5. 대화가 쌓이면 Gemma 4가 백그라운드에서 자동으로 성향을 분석하고 Room DB에 저장합니다.
6. **이야기 정리** 버튼을 누르면 Gemma가 채팅 원문을 익명화 요약하고, Gemini가 그 요약문과 성향 프로필·피드백을 반영한 추천을 생성합니다.
7. 결과 화면에서 **모임 후기 남기기** 버튼으로 피드백을 입력하면 다음 추천에 반영됩니다.
8. 캘린더 아이콘을 눌러 모임을 일정에 추가할 수 있습니다.

---

## 주의사항

- GPU를 지원하는 Android 14 이상 기기가 필요합니다.
- 기상청 단기예보는 **오늘부터 3일 이내** 날짜만 지원합니다.
- 채팅·방 데이터는 Firebase Firestore에 영구 저장됩니다 (앱 재시작 후에도 유지).
- 참여자 성향 프로필(`UserStatus`)은 Room DB에 영구 저장됩니다.
- 후기 데이터는 Room DB에 저장되고 EmbeddingGemma로 온디바이스 임베딩 인덱싱됩니다 (사용자별·기기 내).
- AI 추천 결과는 Room DB(`meeting_summary`)에 영속 저장되어 앱 재시작 후에도 "지난 추천 보기"로 재확인됩니다.
- 캘린더 일정은 앱 메모리에만 저장되며 앱 재시작 시 초기화됩니다 (Room 영속화 예정).
- 카카오맵 장소 검색은 현재 Mock 결과를 반환하며, 실제 API 연동은 추후 개발 예정입니다.

---

## 향후 계획

- [x] Room DB 연동으로 참여자 성향 프로필 영구 저장
- [x] 온디바이스 프라이버시 방화벽 (채팅 원문 클라우드 전송 차단)
- [x] **PII 스크러버** — 클라우드 전송 직전 결정론적 마스킹 게이트 (요약문·후기)
- [x] **온디바이스 시맨틱 RAG** — EmbeddingGemma로 후기 임베딩·검색 (사용자 단위 취향 누적)
- [x] 온디바이스 모델 다운로드 (Gemma + EmbeddingGemma·토크나이저, Range 이어받기)
- [x] Firestore 기반 채팅·방 데이터 실시간 영구 저장
- [x] Firebase Auth Google 로그인 / FCM 푸시 알림
- [x] **추천 결과 Room 영속화 + 재확인 진입점** ("지난 추천 보기")
- [x] **Reflection 패스** — 추천이 명시 제약(싫어요)을 위반하지 않는지 자기비평 (소프트 게이트)
- [x] **임베딩 실패 후기 앱 시작 시 일괄 재인덱싱**
- [x] **온디바이스 constrained decoding** — 성향 압축을 툴 스키마 제약 디코딩으로 교체(깨진 JSON 구조적 차단)
- [ ] Gemini 호출 Cloud Function 프록시화 (API 키 서버 이전)
- [x] 초대코드 기반 방 참여
- [x] 안읽음 메시지 뱃지
- [ ] 날씨 예보 범위 확장 (중기예보 API)
- [ ] 피드백 화면 개선 (별점, 태그 선택 등)
- [ ] AI 추천 결과 Firestore 영구 저장
