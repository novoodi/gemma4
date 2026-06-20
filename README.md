# 모임 AI 비서

친구들과 나눈 카카오톡 스타일의 모임 채팅 대화를 **온디바이스 AI(Gemma 4)** 가 익명화·요약하고, **Gemini API** 가 참여자 성향 프로필과 과거 피드백을 반영하여 장소·활동·준비물을 추천해주는 Android 앱입니다.

채팅 원문은 스마트폰 밖으로 절대 나가지 않습니다. Gemma 4가 개인정보를 제거한 요약문만 클라우드로 전송하는 **온디바이스 프라이버시 방화벽** 구조를 채택하고 있습니다.

---

## 주요 기능

| 기능 | 설명 |
|------|------|
| **모임방 생성** | 이름과 참여자 목록으로 채팅방 생성 |
| **채팅 입력** | 발신자를 바꿔가며 대화 직접 입력 또는 샘플 대화 로드 |
| **프라이버시 방화벽** | Gemma 4가 온디바이스에서 채팅 원문을 익명화 요약 — 이름 등 개인정보는 디바이스 밖으로 전송되지 않음 |
| **성향 프로필 압축** | Gemma 4가 백그라운드에서 채팅 10개마다 선호도·가용성을 JSON으로 압축, Room DB에 누적 저장 |
| **AI 개인화 추천** | Gemini가 Gemma 요약문 + 참여자 성향 프로필(Room DB) + 과거 피드백(RAG)을 반영해 맞춤 추천 |
| **자율 검증 하네스** | Guardrail이 장소 영업 여부를 팩트 체크, 실패 시 자가 수정 후 최대 3회 재시도 |
| **날씨 조회** | 기상청 단기예보 API로 모임 당일 날씨 자동 확인 |
| **모임 후기 저장** | 추천 결과에 대한 피드백을 로컬 저장 → 다음 추천에 자동 반영 |
| **인앱 캘린더** | 확정된 모임 일정을 앱 내 캘린더에 추가 |

---

## 프라이버시 방화벽 아키텍처

채팅 원문이 클라우드로 직접 전송되는 구조적 결함을 해소하기 위해, Gemma 4가 1차 익명화 요약을 온디바이스에서 수행합니다.

```
[채팅 원문] ──→ Gemma 4 (온디바이스)
                 · 이름 등 개인정보 제거
                 · 날짜·장소·목적 중심 2~3문장 요약
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

### Step 5 — Gemini 개인화 추천 (RAG + UserStatus + Function Calling)

Gemma 요약문 + Room DB 성향 프로필 + 로컬 피드백 이력을 프롬프트에 주입합니다.

```
[Gemma 1차 요약]                     ← Gemma 익명화 요약 (개인정보 없음)
  이번 토요일 서울 서북부에서 식사 모임 예정.

[참여자 성향 프로필 — 최우선 반영]    ← Room DB (UserStatus)
  선호: 좋아요: 조용한 곳
  일정: 토요일 오후 가능

[과거 피드백 이력]                    ← 로컬 JSON (RAG)
  [2026-06-09] 오늘 모임 좋았어
  [2026-06-17] 이번 모임한 장소 너무 좋았엉

→ getWeather("서울", "2026-06-27") 호출
→ searchPlace("홍대 조용한 레스토랑", "서울") 호출
→ 맞춤 추천 생성
```

### Step 6 — 자율 검증 하네스 (Guardrail)

GuardrailService가 Gemini 추천 결과의 장소 영업 여부를 팩트 체크합니다.
검증 실패 시 피드백을 컨텍스트에 누적하고 최대 3회 자가 수정 재시도합니다.

```
Gemini 결과 → GuardrailService.verify()
  통과 → OrchestratorResult.Success → UI 표시
  실패 → 피드백 누적 → Gemini 재시도 (최대 3회)
```

### Step 7 — 피드백 저장 (Append-only)

모임 후 사용자가 입력한 후기를 로컬 JSON에 누적 저장합니다. 다음 추천 시 자동으로 RAG context로 주입됩니다.

---

## 아키텍처

```
app/
├── MoimApp.kt                    # Application — 싱글톤 초기화 및 의존성 주입
├── MainActivity.kt               # NavHost (4개 화면 라우팅)
├── navigation/Screen.kt          # sealed class 라우트 정의
│
├── data/
│   ├── local/
│   │   ├── AppDatabase.kt        # Room DB 싱글톤 (moim_database)
│   │   ├── UserStatusEntity.kt   # user_status 테이블 엔티티
│   │   ├── UserStatusDao.kt      # @Insert(onConflict=REPLACE) / @Query DAO
│   │   └── Converters.kt         # List<String> ↔ String ("||" 구분자) TypeConverter
│   ├── model/
│   │   ├── Message.kt            # 채팅 메시지
│   │   ├── ChatRoom.kt           # 모임방
│   │   ├── MeetingSummary.kt     # AI 분석 결과 (요약/장소/날짜/추천/날씨)
│   │   └── CalendarEvent.kt      # 캘린더 일정
│   ├── pipeline/
│   │   ├── OnDeviceLlmPort.kt    # 온디바이스 LLM 교체 계약 (compress / summarizeForPrivacy)
│   │   ├── MockOnDeviceLlm.kt    # 키워드 탐지 기반 Mock 구현
│   │   ├── GemmaOnDeviceLlm.kt   # LlmService 위임 구현
│   │   └── StatusCompressionPipeline.kt  # 압축 트리거·파싱·Room Upsert 오케스트레이션
│   └── repository/
│       ├── UserStatusRepository.kt # UserStatus Room DB 접근
│       ├── ChatRepository.kt     # 메시지·요약 인메모리 StateFlow
│       ├── CalendarRepository.kt # 일정 인메모리 StateFlow
│       └── FeedbackRepository.kt # 모임 피드백 로컬 저장 (append-only JSON)
│
├── service/
│   ├── LlmService.kt             # Gemma 4 엔진 초기화·compress·summarizeForPrivacy
│   ├── AgentOrchestrator.kt      # 프라이버시 방화벽 + Gemini Function Calling + 하네스 루프
│   ├── GuardrailService.kt       # 장소 영업 여부 팩트 체크 + 피드백 생성
│   ├── GeminiService.kt          # (레거시) Gemini API 직접 호출
│   └── WeatherService.kt         # 기상청 단기예보 API 연동
│
└── ui/
    ├── screen/
    │   ├── home/                 # 모임방 목록 + 생성 다이얼로그
    │   ├── chat/                 # 채팅 UI + 요약 트리거 + 백그라운드 압축
    │   ├── summary/              # AI 분석 결과 카드 + 피드백 입력
    │   └── calendar/             # 인앱 캘린더
    └── theme/                    # Material 3 테마
```

**패턴**: MVVM + Repository
**상태 관리**: `StateFlow` / `collectAsStateWithLifecycle`

---

## 동작 흐름

```
[채팅 입력 or 샘플 로드]
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
[GuardrailService — 팩트 체크]
  장소 영업 여부 검증
  실패 → 피드백 주입 후 Gemini 재시도 (최대 3회)
  통과 → OrchestratorResult.Success
        │
        ▼
[SummaryScreen]
  요약 / 날짜 / 장소 / 날씨 / AI 추천 카드 표시
        │
        ▼
[모임 후기 남기기] ← 사용자가 텍스트 피드백 입력
        │
        ▼
[FeedbackRepository.append()]
  로컬 JSON에 누적 저장 → 다음 모임 추천 시 자동 반영
```

---

## 기술 스택

| 항목 | 내용 |
|------|------|
| 언어 | Kotlin 2.2 |
| UI | Jetpack Compose + Material 3 |
| 네비게이션 | Navigation Compose 2.8 |
| 온디바이스 LLM | Google AI Edge LiteRTLM 0.11 (Gemma 4) |
| 클라우드 LLM | Google GenAI SDK 1.56.0 (Gemini) |
| 로컬 DB | Room 2.6.1 (KSP 2.2.21-2.0.4) |
| 개인화 방식 | UserStatus(Room DB) + RAG(Append-only 피드백) |
| 프라이버시 | 온디바이스 Gemma 익명화 요약 → 원문 미전송 |
| 날씨 API | 기상청 단기예보(VilageFcst) v2.0 |
| 최소 SDK | Android 14 (API 34) |
| 빌드 도구 | AGP 8.11 / Gradle 8.13 |

---

## 시작하기

### 1. 모델 파일 배치

앱이 사용하는 Gemma 4 모델 파일(`gemma-4-E2B-it.litertlm`)을 디바이스의 다음 경로에 넣어주세요:

```
/Android/data/com.example.gemma4/files/models/gemma-4-E2B-it.litertlm
```

> 모델 파일이 없으면 Mock 구현(`MockOnDeviceLlm`)이 자동으로 사용됩니다.

### 2. API 키 설정

`local.properties`에 다음 두 가지 키를 추가합니다:

```properties
# 기상청 단기예보 (https://www.data.go.kr)
WEATHER_API_KEY=발급받은_기상청_API_키

# Google Gemini API (https://aistudio.google.com/app/apikey)
GEMINI_API_KEY=발급받은_Gemini_API_키
```

> `local.properties`는 `.gitignore`에 포함되어 있어 Git에 업로드되지 않습니다.
> Gemini API 키 없이도 앱은 실행되며, Mock 추천 텍스트가 표시됩니다.

### 3. 빌드 및 실행

```bash
./gradlew assembleDebug
```

또는 Android Studio에서 Run(▶)을 누릅니다.

---

## 사용법

1. **+** 버튼으로 모임방을 만들고 참여자 이름을 쉼표로 입력합니다.
   예: `나, 철수, 영희` (첫 번째 이름이 내 이름)
2. 채팅방에서 발신자 칩을 눌러 화자를 바꾸며 대화를 입력하거나,
   **더미 로드** 메뉴에서 샘플 대화(익선동 모임 / 볼링 모임 / 홈파티)를 불러옵니다.
3. 대화가 쌓이면 Gemma 4가 백그라운드에서 자동으로 성향을 분석하고 Room DB에 저장합니다.
4. **이야기 정리** 버튼을 누르면 Gemma가 채팅 원문을 익명화 요약하고, Gemini가 그 요약문과 성향 프로필·피드백을 반영한 추천을 생성합니다.
5. 결과 화면에서 **모임 후기 남기기** 버튼으로 피드백을 입력하면 다음 추천에 반영됩니다.
6. 캘린더 아이콘을 눌러 모임을 일정에 추가할 수 있습니다.

---

## 주의사항

- GPU를 지원하는 Android 14 이상 기기가 필요합니다.
- 기상청 단기예보는 **오늘부터 3일 이내** 날짜만 지원합니다.
- 채팅·요약·일정 데이터는 앱 메모리에 저장되며 앱 재시작 시 초기화됩니다.
- 참여자 성향 프로필(`UserStatus`)은 Room DB에 영구 저장됩니다.
- 피드백 데이터(`feedback_history.json`)는 앱 내부 저장소에 영구 보존됩니다.
- 오는 방법(directions)은 현재 미구현 상태이며, 카카오맵 API 연동은 추후 개발 예정입니다.

---

## 향후 계획

- [ ] 카카오맵 REST API 실제 연동 (GuardrailService Mock 교체)
- [ ] LlmService → OnDeviceLlmPort 구현 교체 (MockOnDeviceLlm 제거)
- [x] Room DB 연동으로 참여자 성향 프로필 영구 저장
- [x] 온디바이스 프라이버시 방화벽 (채팅 원문 클라우드 전송 차단)
- [ ] 채팅·요약 데이터 Room DB 영구 저장
- [ ] 날씨 예보 범위 확장 (중기예보 API)
- [ ] 피드백 화면 개선 (별점, 태그 선택 등)
