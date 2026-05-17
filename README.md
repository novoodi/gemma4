# 모임 AI 비서

친구들과 나눈 카카오톡 스타일의 모임 채팅 대화를 **온디바이스 AI(Gemma 4)**가 분석해 날짜·장소·날씨·할 것·챙겨갈 것을 자동으로 정리해주는 Android 앱입니다.

---

## 주요 기능

| 기능 | 설명 |
|------|------|
| **모임방 생성** | 이름과 참여자 목록으로 채팅방 생성 |
| **채팅 입력** | 발신자를 바꿔가며 대화 직접 입력 또는 샘플 대화 로드 |
| **AI 자동 요약** | Gemma 4 모델이 대화를 분석해 7가지 항목 추출 |
| **날씨 조회** | 기상청 단기예보 API로 모임 당일 날씨 자동 확인 |
| **인앱 캘린더** | 확정된 모임 일정을 앱 내 캘린더에 추가 |

### AI 추출 항목 (LLM 8단계 파이프라인)

1. 대화 내용 요약 (3~5문장)
2. 모임 장소
3. 모임 날짜 (YYYY-MM-DD)
4. 날씨 조회용 도시명 추출
5. 당일 활동 예정 내용
6. 챙겨갈 것 추천
7. 기상청 API 날씨 조회
8. 오는 방법 (TODO: 카카오맵 연동 예정)

---

## 아키텍처

```
app/
├── MoimApp.kt                    # Application — LlmService 싱글톤 초기화
├── MainActivity.kt               # NavHost (4개 화면 라우팅)
├── navigation/Screen.kt          # sealed class 라우트 정의
│
├── data/
│   ├── model/
│   │   ├── Message.kt            # 채팅 메시지
│   │   ├── ChatRoom.kt           # 모임방
│   │   ├── MeetingSummary.kt     # AI 분석 결과
│   │   └── CalendarEvent.kt      # 캘린더 일정
│   ├── repository/
│   │   ├── ChatRepository.kt     # 메시지·요약 인메모리 StateFlow
│   │   └── CalendarRepository.kt # 일정 인메모리 StateFlow
│   └── SampleData.kt             # 테스트용 실제 대화 샘플 3종
│
├── service/
│   ├── LlmService.kt             # Gemma 4 엔진 초기화·파이프라인 실행
│   └── WeatherService.kt         # 기상청 단기예보 API 연동
│
└── ui/
    ├── screen/
    │   ├── home/                 # 모임방 목록 + 생성 다이얼로그
    │   ├── chat/                 # 채팅 UI + 요약 트리거
    │   ├── summary/              # AI 분석 결과 카드 목록
    │   └── calendar/             # 인앱 캘린더
    └── theme/                    # Material 3 테마
```

**패턴**: MVVM + Repository  
**상태 관리**: `StateFlow` / `collectAsStateWithLifecycle`

---

## 동작 흐름

```
[모임방 생성]
      │
      ▼
[채팅 입력 or 샘플 로드]
      │
      ▼
[이야기 정리 버튼 클릭]
      │
      ▼
[LlmService.initialize()]  ← GPU 백엔드로 Gemma 4 로드
      │
      ▼
[8단계 LLM 파이프라인]  ← 단일 conversation으로 순차 질의
      │                    (요약 → 장소 → 날짜 → 도시 → 활동 → 챙겨갈 것)
      │
      ▼
[WeatherService]  ← 기상청 격자 좌표 변환 → 단기예보 API
      │
      ▼
[SummaryScreen]  ← 카드 형식으로 결과 표시
      │
      ▼
[캘린더 추가]  ← 날짜가 확정된 경우에만 활성화
```

---

## 기술 스택

| 항목 | 내용 |
|------|------|
| 언어 | Kotlin 2.2 |
| UI | Jetpack Compose + Material 3 |
| 네비게이션 | Navigation Compose 2.8 |
| 온디바이스 LLM | Google AI Edge LiteRTLM 0.11 (Gemma 4) |
| LLM 백엔드 | GPU |
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

### 2. 날씨 API 키 설정

[공공데이터포털](https://www.data.go.kr)에서 **기상청 단기예보 조회서비스** API 키를 발급받은 뒤 `local.properties`에 추가합니다:

```properties
WEATHER_API_KEY=여기에_발급받은_API_키
```

> API 키가 없어도 앱은 정상 실행됩니다. 날씨 항목에 "날씨 API 키가 설정되지 않았습니다"가 표시됩니다.

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
3. **이야기 정리** 버튼을 누르면 AI가 대화를 분석합니다 (수십 초 소요).
4. 분석 결과 화면에서 캘린더 아이콘을 눌러 모임을 일정에 추가할 수 있습니다.

---

## 주의사항

- GPU를 지원하는 Android 14 이상 기기가 필요합니다.
- 기상청 단기예보는 **오늘부터 3일 이내** 날짜만 지원합니다. 그 이후 날짜의 모임은 날씨 항목이 빈 칸으로 표시됩니다.
- 모든 데이터(채팅, 요약, 일정)는 앱 메모리에만 저장되며 앱 재시작 시 초기화됩니다.
- 오는 방법(directions)은 현재 하드코딩된 예시값이며, 카카오맵 API 연동은 추후 개발 예정입니다.

---

## 향후 계획

- [ ] 카카오맵 API 연동 (장소 → 길 안내)
- [ ] Room DB 연동으로 데이터 영구 저장
- [ ] 날씨 예보 범위 확장 (중기예보 API)
- [ ] gemini API를 사용해서 사용자에게 모임에 대한 추천해주기( ex 5월 15일에 홍대 ~~에서 만나시는걸로 아는데 맛집이나 놀거리를 추천해드릴까요?
- [ ] 사용자의 대화를 저장해서 추후 사용자에서 추천을 할 때 사용하기
