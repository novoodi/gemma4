# CLAUDE.md — 모이미 (모임 AI 비서)

캡스톤 프로젝트. 친구들과의 채팅을 온디바이스 AI(Gemma 4)가 익명화·요약하고,
Gemini API가 성향 프로필과 과거 피드백을 반영해 장소·활동을 추천하는 Android 앱.

## 절대 불변 원칙 (Privacy Firewall)

**채팅 원문은 어떤 경우에도 디바이스 밖으로 나가지 않는다.**

- 클라우드(Gemini)로 전송 가능한 것: Gemma 4가 온디바이스에서 생성한 익명화 요약문,
  성향 프로필(JSON), 과거 피드백 텍스트뿐
- **경계를 넘는 모든 자유 텍스트는 `PiiScrubber`를 통과해야 한다** — Gemma 요약문과
  피드백(RAG 컨텍스트) 모두. 프롬프트 지시("이름 언급 마")는 1차 방어일 뿐,
  집행은 결정론적 스크러버가 한다. 피드백은 사용자가 쓴 자유 텍스트라 실명이 섞일 수 있음
- 새 기능을 추가할 때 항상 자문할 것: "이 데이터가 디바이스 경계를 넘는가?
  넘는다면 PiiScrubber를 통과했는가?"
- 이 원칙을 깨는 지름길(예: "일단 원문을 Gemini에 보내서 테스트")은 임시로도 금지

## 아키텍처

```
[채팅 원문] → Gemma 4 (LiteRT-LM, 온디바이스, GPU)
                ├─ summarizeForPrivacy(): 익명화 요약 (이름 제거, 날짜·장소·목적 중심)
                └─ compress(): 성향 프로필 JSON (채팅 10개마다 델타 압축 → Room 병합 upsert)
                     │
              PiiScrubber (결정론적 마스킹 게이트 — 요약문·RAG 컨텍스트)
                     │
              ── 디바이스 경계 ──
                     │
              AgentOrchestrator → Gemini (Function Calling: getWeather / searchPlace)
                     → GuardrailService 팩트체크 (OPEN/CLOSED/UNKNOWN 3-상태)
                     → 실패 시 피드백 누적 재시도 (최대 3회, 툴 왕복 상한 8회)
```

### 핵심 컴포넌트

| 위치 | 역할 |
|---|---|
| `service/LlmService` | LiteRT-LM Engine 래퍼. Gemma 4 E2B (.litertlm, GPU 백엔드) |
| `service/PiiScrubber` | 클라우드 전송 직전 결정론적 PII 마스킹 (순수 Kotlin, 명단 대조+정규식+호칭) |
| `service/AgentOrchestrator` | 하이브리드 하네스 통제실. Gemini FC + Guardrail 재시도 루프 |
| `service/GuardrailService` | 추천 장소 영업 여부 팩트체크. fail-open 금지 — 검증 불가는 UNKNOWN |
| `service/ModelDownloadService` | HF에서 모델 다운로드 (Foreground Service, Range 이어받기) |
| `service/EmbeddingGemmaEmbedder` | EmbeddingGemma 임베더 (raw LiteRT+DJL). 프리픽스 API 강제, 로드→사용→해제 |
| `service/GeminiService` | 레거시 단방향 경로 — 데드코드, 제거 예정. 새 코드에서 참조 금지 |
| `data/pipeline/OnDeviceLlmPort` | 온디바이스 LLM 추상화 포트 (Gemma/Mock 런타임 교체) |
| `data/pipeline/FeedbackRetriever` | 후기 검색 포트 (EmbeddingGemma 시맨틱 / 키워드 폴백 교체) |
| `data/pipeline/StatusCompressionPipeline` | Gemma JSON 방어적 파싱 → 직전 상태와 증분 병합 → Room |
| `data/repository/FeedbackRepository` | 모임 후기 저장 (Room, 저장 시 임베딩 인덱싱) |
| `data/local/` | Room DB (user_status 등) |

## 코드 컨벤션 — 이 레포에서 확립된 패턴을 따를 것

1. **포트-어댑터 + 런타임 교체**: 온디바이스 LLM은 `OnDeviceLlmPort` 인터페이스 뒤에 둔다.
   실제 구현(`GemmaOnDeviceLlm`)과 목(`MockOnDeviceLlm`)을 런타임 교체 가능하게 유지
   (`MoimApp.reinitializePipelines()` 참조). 새 AI 컴포넌트(임베더 등)도 동일 패턴:
   포트 정의 → 실제 어댑터 + 폴백 어댑터.

2. **방어적 JSON 파싱**: 온디바이스 모델 출력은 절대 신뢰하지 않는다.
   `StatusCompressionPipeline` 패턴 준수 — Regex로 `{...}` 블록 추출 →
   trailing comma 정제 → 파싱 실패 시 크래시가 아니라 로그 후 스킵.

3. **엔진 동시성**: LiteRT-LM Engine, TFLite Interpreter는 thread-safe가 아니다.
   `LlmService`의 `engineMutex` 패턴처럼 Mutex로 보호하고, 초기화 성공 후에만
   필드에 대입한다 (미초기화 엔진 오염 방지).

4. **프롬프트 규칙**: 온디바이스 Gemma 프롬프트는 한국어, 출력 포맷을 명시적으로 강제,
   "별표·샵·대괄호 없이 일반 텍스트" 지시 포함 (`NO_MARKDOWN` 상수 참조).

5. **관찰 가능성**: 에이전트 흐름의 주요 단계는 `AgentEvent`로 발행한다.
   새 파이프라인 단계 추가 시 대응하는 이벤트도 추가 (기존 이벤트 확장 시
   기본값 파라미터로 비파괴 확장 — `GemmaSummaryCompleted.redactions` 전례).

6. **테스트 가능한 설계**: 핵심 로직은 Android 의존성 없는 순수 Kotlin으로 작성해
   JVM 단위 테스트를 가능하게 한다 (`PiiScrubber` 패턴). 테스트 접근이 필요한
   내부 함수는 `internal` (`extractAndParse`, `mergeStatus` 전례).
   org.json은 테스트 클래스패스에 실구현이 있음 (`testImplementation org.json:json`).

7. **루프에는 반드시 상한**: LLM 재시도·툴 호출 루프에 `while(true)` 금지.
   `MAX_ATTEMPTS`, `MAX_TOOL_ROUNDS` 전례처럼 명명 상수로 상한을 둔다.

## RAG / 임베딩 규칙 (EmbeddingGemma 도입 시 필수)

- **프리픽스 비대칭 — 절대 빼먹지 말 것**:
  - 문서 인덱싱(후기 저장): `"title: none | text: "` + 본문
  - 검색 쿼리: `"task: search result | query: "` + 질문
  - 둘 중 하나라도 빠지면 빌드·실행은 멀쩡한데 검색 품질만 조용히 저하됨
- **골든 테스트 의무**: Python `sentence-transformers`로 동일 문장을 임베딩한
  기준 벡터와 코사인 유사도를 검증하는 테스트 없이는 임베더 구현을 완료로 간주하지 않음.
  **골든 임계값 = 0.93** (2026-07-12 실측 min 0.9466, unsloth 미러 int4 기준).
  더불어 랭킹 일치 테스트(쿼리별 Python top-1 == 온디바이스 top-1)로 순위 보존 검증
- 벡터 검색은 roomId 필터 → brute-force 코사인 top-k (수백 건 규모에서 충분,
  벡터 DB 도입은 성능 문제가 실측될 때만)
- 임베더는 상시 상주하지 않는다: 로드 → 사용 → 해제 (Gemma E2B와 메모리 경합 방지)
- 검색 결과(피드백 텍스트)도 클라우드로 가기 전 `PiiScrubber`를 통과한다

## 모델 파일

| 모델 | 형식 | 용도 | 런타임 |
|---|---|---|---|
| gemma-4-E2B-it | .litertlm (~2.5GB) | 익명화 요약, 성향 압축 | LiteRT-LM 0.11 |
| embeddinggemma-300m (도입 예정) | .tflite (~200MB, 실측 전) | 후기 시맨틱 검색 | LiteRT (raw interpreter) |

- 저장 위치: `context.getExternalFilesDir("models")`
- 다운로드 출처: HF `litert-community` (E2B는 직접 URL 확인됨,
  embeddinggemma는 **gated** — 라이선스 동의·토큰 필요 여부 확인)
- **LiteRT-LM(생성)과 LiteRT(임베딩)는 별개 런타임이다. LiteRT-LM에는 임베딩 API가 없음**
  (2026-07 조사 확정 — Engine/Conversation은 생성 전용)

## 검증 원칙

- 문서·계획보다 코드 실측 우선. 의존성 추가나 아키텍처 변경은 본 구현 전에
  스파이크(빌드 + 실기기 스모크 테스트)로 판정한다
- 스파이크 판정 기준은 시작 전에 명문화한다 (통과/실패/우회 조건)
- 네이티브 .so 충돌 발생 시: 즉시 실패가 아니라 `packaging { jniLibs { pickFirsts } }`
  우회를 먼저 시도한 후 판정
- LLM Inference API 계열은 에뮬레이터를 지원하지 않음 — 실기기에서 테스트할 것

## 문서화

- 아키텍처 결정(채택/기각/방향 전환)이 있을 때마다 `docs/DEVLOG.md`에 기록
  (없으면 생성): 날짜, 결정, 근거, 기각한 대안, 남은 리스크
- README.md는 기능·아키텍처의 현재 상태만 반영 (히스토리는 DEVLOG에)

## 빌드 · 커밋

- Kotlin + Jetpack Compose, 버전 카탈로그(`gradle/libs.versions.toml`) 사용 —
  의존성 추가 시 버전 하드코딩 금지
- Room 컴파일러는 ksp, Firebase는 BoM으로 관리
- **NDK 필수**: `ndkVersion`(현재 28.2.13676358) 설치돼 있어야 함. 빌드 시 `copyLibcxxShared`
  태스크가 NDK sysroot에서 `libc++_shared.so`를 주입한다(DJL 토크나이저 런타임 의존성).
  없으면 `assembleDebug` 실패 — SDK Manager로 해당 NDK 설치
- 커밋: `type(scope): 한국어 요약` (feat/fix/chore/build/refactor).
  `.idea/` 변경은 커밋에 포함하지 않는다
- 시크릿은 `local.properties` → BuildConfig 경유만.
  `google-services.json`·모델 파일·`*-debug.log`는 git 금지 (.gitignore 유지)
