# 모임 AI 비서

친구들과 나눈 카카오톡 스타일의 모임 채팅 대화를 **온디바이스 AI(Gemma 4)** 가 분석하고, **Gemini API** 가 과거 피드백과 참여자 성향 프로필을 반영하여 장소·활동·준비물을 추천해주는 Android 앱입니다.

---

## 주요 기능

| 기능 | 설명 |
|------|------|
| **모임방 생성** | 이름과 참여자 목록으로 채팅방 생성 |
| **채팅 입력** | 발신자를 바꿔가며 대화 직접 입력 또는 샘플 대화 로드 |
| **AI 자동 요약** | Gemma 4가 대화를 분석해 요약·장소·날짜·도시 추출 |
| **성향 프로필 압축** | Gemma 4가 백그라운드에서 채팅 10개마다 선호도·가용성을 JSON으로 압축, Room DB에 누적 저장 |
| **AI 개인화 추천** | Gemini가 참여자 성향 프로필(Room DB) + 과거 피드백(RAG)을 반영해 맞춤 추천 |
| **날씨 조회** | 기상청 단기예보 API로 모임 당일 날씨 자동 확인 |
| **모임 후기 저장** | 추천 결과에 대한 피드백을 로컬 저장 → 다음 추천에 자동 반영 |
| **인앱 캘린더** | 확정된 모임 일정을 앱 내 캘린더에 추가 |

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

### Step 2 — Gemma 4 온디바이스 압축

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

### Step 4 — Gemini 개인화 추천 (RAG + UserStatus)

Gemma 추출 결과 + Room DB의 성향 프로필 + 로컬 피드백 이력을 프롬프트에 주입합니다.

```
[이번 모임 정보]                    ← Gemma 추출
- 날짜 / 지역 / 장소 / 요약

[참여자 성향 프로필 — 최우선 반영]   ← Room DB (UserStatus)
- 참가자: 이승현, 양예찬, 차민영
- 선호: 좋아요: 조용한 곳 / 싫어요: 시끄러운 술집
- 일정: 토요일 오후 가능

[과거 피드백 이력]                   ← 로컬 JSON (RAG)
- [2026-06-09] 오늘 모임 좋았어
- [2026-06-17] 이번 모임한 장소 너무 좋았엉

→ 참여자 성향을 최우선으로, 과거 피드백도 반영한 맞춤 추천
```

### Step 5 — 피드백 저장 (Append-only)

모임 후 사용자가 입력한 후기를 로컬 JSON에 누적 저장합니다. 다음 추천 시 자동으로 RAG context로 주입됩니다.

---

## 아키텍처

```
app/
├── MoimApp.kt                    # Application — LlmService·AppDatabase 싱글톤 초기화
├── MainActivity.kt               # NavHost (4개 화면 라우팅)
├── navigation/Screen.kt          # sealed class 라우트 정의
│
├── data/
│   ├── local/
│   │   ├── AppDatabase.kt        # Room DB 싱글톤 (moim_database)
│   │   ├── UserStatusEntity.kt   # user_status 테이블 엔티티
│   │   ├── UserStatusDao.kt      # @Upsert / @Query DAO
│   │   └── Converters.kt         # List<String> ↔ String ("||" 구분자) TypeConverter
│   ├── model/
│   │   ├── Message.kt            # 채팅 메시지
│   │   ├── ChatRoom.kt           # 모임방
│   │   ├── MeetingSummary.kt     # AI 분석 결과 (요약/장소/날짜/추천/날씨)
│   │   └── CalendarEvent.kt      # 캘린더 일정
│   ├── repository/
│   │   ├── UserStatusRepository.kt # UserStatus Room DB 접근
│   │   ├── ChatRepository.kt     # 메시지·요약 인메모리 StateFlow
│   │   ├── CalendarRepository.kt # 일정 인메모리 StateFlow
│   │   └── FeedbackRepository.kt # 모임 피드백 로컬 저장 (append-only JSON)
│   └── SampleData.kt             # 테스트용 실제 대화 샘플 3종
│
├── service/
│   ├── LlmService.kt             # Gemma 4 엔진 초기화·압축·정보 추출 파이프라인
│   ├── GeminiService.kt          # Gemini API 호출 + UserStatus/RAG 주입 추천 생성
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
        ├──(10개 단위마다, 백그라운드)──────────────────────────┐
        │                                                      ▼
        │                                          [Gemma 4 — 성향 압축]
        │                                   채팅 → participants/preferences/availability JSON
        │                                                      │
        │                                          [Room DB Upsert]
        │                                      user_status 테이블에 저장
        │                                                      │
        ▼                                                      │
[이야기 정리 버튼 클릭]                                         │
        │                                                      │
        ├── compressionJob.join() ← 압축 완료까지 대기 ─────────┘
        │
        ├── Room DB에서 UserStatus 로드
        │
        ▼
[Gemma 4 — Conv A]
  요약 + 장소 + 날짜 + 도시 추출
        │
        ├── FeedbackRepository에서 과거 피드백 로드 (RAG context)
        │
        ▼
[Gemini API]
  Gemma 추출 결과 + UserStatus(성향 프로필) + 과거 피드백 → 장소/활동/준비물 추천
        │
        ▼
[WeatherService]
  기상청 격자 좌표 변환 → 단기예보 API
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
| 클라우드 LLM | Google Gemini API |
| 로컬 DB | Room 2.6.1 (KSP 2.2.21-2.0.4) |
| 개인화 방식 | UserStatus(Room DB) + RAG(Append-only 피드백) |
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

> 모델 파일이 없으면 "이야기 정리" 버튼을 눌렀을 때 경로 안내 에러가 표시됩니다.

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
4. **이야기 정리** 버튼을 누르면 Gemma가 대화를 분석하고, Gemini가 성향 프로필과 피드백을 반영한 추천을 생성합니다.
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

- [ ] 카카오맵 API 연동 (장소 → 길 안내)
- [x] Room DB 연동으로 참여자 성향 프로필 영구 저장
- [ ] 채팅·요약 데이터 Room DB 영구 저장
- [ ] 날씨 예보 범위 확장 (중기예보 API)
- [ ] 피드백 화면 개선 (별점, 태그 선택 등)