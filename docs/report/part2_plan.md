# Ⅱ. 프로젝트 계획서

> 표지(프로젝트명·개발기간·참여자 표)는 중간 보고서 원본 양식을 그대로 사용한다.
> 그림 파일: `docs/report/figures/fig_architecture.png`(1. 시스템 구성도), `docs/report/figures/fig_gantt.png`(7. 개발 일정).

# I. 시스템 개발 관리

## 1. 시스템 구성도

(그림 삽입: `fig_architecture.png` — 온디바이스-클라우드 하이브리드 구성도)

본 시스템은 Android 단말(Client Side), Firebase·Google Cloud(Cloud Side), 외부 도구 API(External Tools)의 세 영역으로 구성된다. 그림의 번호는 데이터 흐름 순서이다.

- **Track 1 — 백그라운드 상태 압축(①~④)**: 채팅방에 메시지가 10개 누적될 때마다 최근 15개 메시지를 온디바이스 Gemma 4 E2B(LiteRT-LM, GPU)에 전달한다(①). 모델은 `record_status` 툴 스키마로 제약 디코딩된 JSON(참석자·선호/불호·가능 일정)을 반환하고(②), `StatusCompressionPipeline`이 방어적으로 파싱해 직전 상태와 결정론적으로 병합한 뒤 Room DB `user_status`에 Upsert한다(③). 추천 시점에 오케스트레이터가 이 프로필을 로드한다(④).
- **Track 2 — 추천 하네스 루프(⑤~⑮)**: 사용자가 '이야기 정리'를 누르면(⑤) `AgentOrchestrator`가 Gemma 4에 원문 익명화 요약을 요청하고(⑥), 결과를 `PiiScrubber`로 마스킹한다(⑦). EmbeddingGemma-300m이 이 요약문으로 과거 후기를 시맨틱 검색해 회수하며 이 후기 역시 스크러버를 통과한다(⑧). 요약문·프로필·후기와 도구 명세를 담은 프롬프트가 Cloud Functions 프록시를 거쳐 Gemini에 전달되고(⑨), Gemini는 툴 호출 또는 최종 JSON을 반환한다(⑩). 툴 호출(`getWeather`·`searchPlace`)은 앱이 프록시를 통해 카카오 로컬·기상청 API를 대리 실행한다(⑪). 추천 초안(⑫)은 Guardrail(장소 실존, 3-상태)과 Reflection(불호 위반)으로 검증되고, 실패 시 반려 사유를 누적해 최대 3회 재시도한다(⑬). 통과한 결과는 Room `meeting_summary`에 영속되어(⑭) 카드로 표시되고 이후 '지난 추천 보기'로 재확인된다(⑮).
- **Cloud Side**: Firebase Auth(Google 로그인), Cloud Firestore(방·메시지·초대코드, 참여자 한정 보안 규칙), FCM(푸시), Cloud Functions 2세대(새 메시지 푸시 트리거, 외부 API 프록시 3종). 외부 API 키는 Secret Manager에만 존재한다.
- **경계 원칙**: 채팅 원문은 Track 1·2 모두 기기 안에서만 처리된다. 클라우드로 나가는 것은 마스킹된 요약문, 비식별 성향 프로필, 마스킹된 후기뿐이다.
- **개발 주체 구분(그림 테두리)**: 굵은 실선은 본 팀이 직접 설계·구현한 코드(UI, AgentOrchestrator, PiiScrubber, 후기 RAG 리트리버, StatusCompressionPipeline, Guardrail, Reflection, ModelDownloadService)이다. 굵은 점선은 외부 모델·플랫폼 위에 자체 통합 코드를 얹은 부분으로, Gemma 4·EmbeddingGemma는 Google의 공개 가중치 모델이지만 제약 디코딩·프롬프트·프리픽스 강제·엔진 동시성 제어는 자체 구현이며, Room DB는 라이브러리 위에 자체 스키마·DAO를, Cloud Functions와 Firestore는 플랫폼 위에 자체 함수 코드와 보안 규칙을 얹었다. 얇은 회색 실선은 그대로 가져다 쓴 외부 서비스·모델(Gemini, Firebase Auth·FCM, Secret Manager, 카카오 로컬 API, 기상청 API)이다.

## 2. 추진 전략

### (1) 기술적 추진 전략

**적용 기술 개요 및 특징**
- **Android 네이티브 앱(Kotlin + Jetpack Compose)**: 온디바이스 LLM 런타임(LiteRT-LM)이 Android 네이티브 라이브러리로 제공되므로, 플랫폼 채널 브릿지 계층 없이 Kotlin에서 직접 엔진을 제어한다. 중간 보고서의 Flutter 안은 스파이크 결과 LiteRT-LM·LiteRT·DJL 네이티브 의존성을 단일 코드베이스에서 안정적으로 통합하기 어려워 기각하였다.
- **Hybrid LLM(Gemma 4 E2B + Gemini)**: 사적 대화 분석은 기기 내부에서, 외부 연동과 추론은 클라우드에서 처리하는 투트랙 기술을 적용한다.
- **하네스 에이전트(Harness Agent)**: 오케스트레이터를 통해 AI가 도구를 자율 호출하고 결과를 Guardrail·Reflection으로 검증하는 제어 공학 기술을 도입한다.
- **온디바이스 시맨틱 RAG**: EmbeddingGemma-300m을 raw LiteRT로 구동해 후기 임베딩·검색을 기기 안에서 수행한다.

**API 및 Open Source SW 활용 방안**
- **Google AI Edge LiteRT-LM 0.11**: 오픈 가중치 경량 모델 Gemma 4 E2B(.litertlm)를 GPU 백엔드로 구동한다. 실험적 constrained decoding 플래그와 툴 스키마로 압축 출력을 문법 제약한다.
- **LiteRT 1.4 + DJL 0.33 토크나이저**: EmbeddingGemma-300m .tflite 인터프리터와 HuggingFace 토크나이저를 결합한다. LiteRT-LM에는 임베딩 API가 없어 별도 런타임을 병행한다.
- **Gemini API(generateContent REST)**: SDK 없이 REST JSON을 직접 조립(`GeminiWire`)해 Function Calling과 JSON 응답 스키마를 사용한다. 호출은 Cloud Functions 프록시를 경유한다.
- **Firebase(Auth·Firestore·FCM·Functions)**: 인증·실시간 채팅·푸시·서버리스 프록시를 담당한다. Firestore 보안 규칙으로 발신자 위조와 비참여자 접근을 차단한다.
- **카카오 로컬 API / 기상청 중기예보 API**: 에이전트가 호출할 도구(Tools)이며, 카카오 로컬 API는 추천 장소의 실존 여부를 판정하는 Guardrail에도 사용된다.

**알고리즘 차별성(기존 vs 제안)**
- **상태 압축 알고리즘**: 대화 원문을 누적하는 기존 RAG와 달리, 툴 스키마 제약 디코딩으로 선호/불호·제약 조건만 JSON으로 추출하고, LLM 재요약 없이 직전 상태와 합집합·중복 제거로 병합(리스트당 25건 상한)해 토큰 오염을 방지한다.
- **자가 수정(Self-correction) 루프 알고리즘**: 프롬프트 1회 전송 후 무조건 출력하는 선형 방식과 달리, 초안을 결정론적 코드(Guardrail·Reflection)로 채점하고 실패 시 반려 사유를 주입해 재시도한다. 재시도 3회·도구 왕복 8회의 명명 상수 상한을 두어 무한 루프를 금지한다.
- **검증 3-상태**: 검증 불가를 '통과'로 처리하던 fail-open을 제거하고 OPEN/CLOSED/UNKNOWN으로 구분해, 검증하지 못한 장소를 UI에 '미검증'으로 표기한다.

### (2) 관리적 추진 전략

**예상 문제점 및 해결 방안**
- **문제점**: 온디바이스 모델(Gemma 4 E2B 약 2.5GB) 구동 시 기기 발열·메모리 부족(OOM)·UI 스레드 멈춤 우려.
  **해결 방안**: 모든 온디바이스 추론은 IO 디스패처의 코루틴에서 비동기 배치(메시지 10개 단위)로 실행한다. LiteRT-LM 엔진과 LiteRT 인터프리터는 스레드 안전하지 않으므로 Mutex로 직렬화하고, 임베더는 작업 단위로 로드·사용·해제하여 Gemma와의 메모리 경합을 방지한다.
- **문제점**: LLM 출력의 비결정성으로 인한 파싱 실패·검증 통과 불가.
  **해결 방안**: 출력 포맷을 제약 디코딩으로 강제하고, 실패 시 자유텍스트 폴백과 정규식 repair로 방어한다. Reflection이 통과하지 못해도 장소가 유효한 결과는 폴백으로 보존해 총 실패를 막는다.
- **문제점**: 외부 API 키 노출 및 일시 장애(429/503).
  **해결 방안**: 키를 서버 시크릿으로 격리하고, 프록시에서 지수 백오프 재시도(1s/2s/4s)와 입력 검증·모델 allowlist를 집행한다.

**기술 획득 및 수행 방법**
- Google AI Edge(LiteRT-LM·LiteRT) 공식 문서와 AAR의 실제 API를 코드 실측(javap)으로 확인해 적용한다. 문서·계획보다 코드 실측을 우선한다.
- 의존성 추가·아키텍처 변경은 본 구현 전 **스파이크(빌드 + 실기기 스모크)**로 통과/실패/우회 기준을 명문화해 판정한다(예: LiteRT-LM과 DJL 동시 상주 검증, constrained decoding 실기기 판정).
- 온디바이스 모델의 정확도는 골든 테스트(Python 기준 벡터 대비 코사인 유사도 임계 0.93)로 보증하며, 임계 미달 시 구현을 완료로 간주하지 않는다.
- 아키텍처 결정(채택·기각·방향 전환)은 `docs/DEVLOG.md`에 날짜·근거·기각 대안·잔여 리스크와 함께 기록한다.

## 3. 개발 기술

### (1) 적용 기술

- **프론트엔드 및 하네스 코어**: Kotlin 2.2, Jetpack Compose + Material 3, Navigation Compose. MVVM + Repository 패턴과 `StateFlow`로 오케스트레이터의 비동기 상태(진행·재시도·완료)를 UI와 동기화하며, 하네스 단계는 `AgentEvent`로 발행되어 AI 진행 화면에 표시된다.
- **로컬 데이터베이스**: Room 2.6.1(KSP). `user_status`(성향 프로필), `feedback`(후기 + 768차원 임베딩 BLOB), `meeting_summary`(추천 결과 JSON), `recommended_room`(후기 팝업 트리거).
- **AI 모델**
  - Gemma 4 E2B(.litertlm, LiteRT-LM 0.11, GPU): 오프라인 데이터 정제기(성향 압축·익명화 요약). 보유 역량: 제약 디코딩·프롬프트 설계·방어적 파싱.
  - EmbeddingGemma-300m(.tflite, LiteRT 1.4 + DJL 토크나이저): 후기 시맨틱 검색. 보유 역량: 프리픽스 비대칭 강제·골든 테스트·brute-force 코사인 검색.
  - Gemini gemini-3.5-flash(REST): 클라우드 추론기. 보유 역량: Function Calling 스키마·JSON 응답 스키마·도구 왕복 루프 구현.
- **백엔드**: Firebase Auth·Firestore(보안 규칙)·FCM·Cloud Functions 2세대(Node.js 24, Secret Manager).
- **검증 계층(순수 Kotlin)**: `PiiScrubber`, `ReflectionService`, `GuardrailService`, `StatusCompressionPipeline`, `GeminiWire` — Android 의존성 없이 JVM 단위 테스트(62건)로 검증.

### (2) 개발 방법론

**적용 방안(Agile + 스파이크 기반 판정)**
AI 모델의 결과물이 비결정론적이므로 폭포수 모델 대신 애자일 기반 점진적 개발을 수행한다. 2주 단위 스프린트로 '프롬프트·스키마 설계 → 도구 연동 → 검증 게이트 → 실기기 피드백 반영' 사이클을 반복하고, 새 의존성·아키텍처 변경은 스파이크로 먼저 판정한 뒤 본 구현한다. 핵심 로직은 순수 Kotlin으로 분리해 단위 테스트를 지속 확장한다.

**산출물 종류 및 제출 시기**
- 프로젝트 제안서·계획서·시스템 분석서·요구사항 명세서(중간 보고서, 2026-06) → 기말 현행화본(2026-11)
- 아키텍처 결정 기록(`docs/DEVLOG.md`, 상시) 및 README(현재 상태 반영, 상시)
- 단위 테스트 코드·골든 테스트 데이터(구현 시점마다) 및 실기기 검증 기록(DEVLOG)
- 서명된 AAB·Play Console 내부 테스트 트랙 등록(2026-10)
- 최종 소스 코드·사용자 매뉴얼·발표 자료(2026-11)

## 4. 개발 환경

**HW(하드웨어)**
- 개발용 기기: Windows 11 PC(Android Studio, NDK 28).
- 테스트용 단말기: 삼성 갤럭시(SM-F966N) — GPU 위임 지원, 온디바이스 LLM 적재를 위해 RAM 6GB 이상 권장. LLM 추론 API는 에뮬레이터를 지원하지 않으므로 실기기에서 검증한다.

**SW(소프트웨어 및 DB)**
- IDE/도구: Android Studio, Gradle 8.13 / AGP 8.11 / KSP, Firebase CLI, Git/GitHub, Python 3.10(골든 벡터 생성·문서 변환).
- DB: Room 2.6.1(로컬), Cloud Firestore(클라우드).
- OS: Android 14(API 34) 이상 타겟(compileSdk 36).

**NW(네트워크)**
- Firebase(Auth·Firestore·FCM·Functions) 및 프록시 경유 Gemini·카카오 로컬·기상청 API 통신을 위한 HTTPS. 온디바이스 정제(압축·요약·임베딩)는 오프라인에서 동작한다.

**프로그래밍 언어**
- Kotlin 2.2: 앱 UI, 오케스트레이터, 온디바이스 AI 파이프라인, DB 등 핵심 로직.
- JavaScript(Node.js 24): Cloud Functions(푸시 트리거·외부 API 프록시).
- Python 3.10: EmbeddingGemma 골든 벡터 생성(sentence-transformers), 보고서 변환 스크립트.

## 5. 개발 제약사항

**시스템 및 구현 제약사항**
- **하드웨어 제약**: Gemma 4 E2B(약 2.5GB)와 EmbeddingGemma(약 180MB)를 적재하려면 GPU 위임 지원 단말과 충분한 가용 RAM(6GB 이상 권장)이 필요하며, 모델 파일 다운로드에 약 2.8GB의 저장 공간을 확보해야 한다. 구형 기기에서는 추론 속도가 저하될 수 있다.
- **최소 지원 버전**: Android 14(API 34) 이상.
- **배터리·발열 제약**: 온디바이스 추론 트리거를 메시지 10개 누적 시 1회로 제한하고, 임베더는 상시 상주하지 않는다.
- **엔진 동시성 제약**: LiteRT-LM 엔진·LiteRT 인터프리터는 스레드 안전하지 않아 Mutex로 직렬화한다. constrained decoding 플래그는 전역 상태이므로 압축 호출 구간에서만 켜고 원복한다.

**운영 환경 제약**
- 클라우드 추론(Gemini), 장소 검증(카카오 로컬), 날씨 조회(기상청)는 로그인·온라인 상태에서만 동작한다. 백그라운드 성향 압축과 익명화 요약, 후기 임베딩·검색은 오프라인에서도 동작한다.
- 기상청 중기예보는 모임 날짜가 오늘로부터 3일 이후 10일 이내일 때만 제공된다. 범위 밖이거나 날짜 미정이면 안내 문구로 대체한다.
- 프록시가 배포되지 않았거나 외부 API가 장애인 경우 추천은 실패로 처리되며, 온디바이스 요약·압축 기능은 영향을 받지 않는다.

**비즈니스 및 법적/표준 제약사항**
- **API 호출 한도**: 카카오·기상청 무료 API의 일일 트래픽 초과를 막기 위해 오케스트레이터 재시도를 최대 3회, 도구 왕복을 최대 8회로 강제 종료한다. 프록시는 Gemini 모델명을 allowlist로 고정하고 본문 크기를 제한한다.
- **API 키 격리**: 외부 API 키는 APK에 포함하지 않으며 Secret Manager에만 둔다(BuildConfig 경유 금지).
- **개인정보보호법**: 사용자 동의 없이 식별 가능한 개인정보를 외부 서버로 전송할 수 없으므로 모든 비식별화(익명화 요약·PII 마스킹)는 온디바이스에서 완료되어야 한다. Play 스토어 출시 시 개인정보처리방침 및 데이터 안전 섹션 작성이 필요하다.

## 6. 개발 조직

> 아래 역할 배분은 중간 보고서의 조직을 현재 기술 스택에 맞게 옮긴 것이다. 팀원별 실제 담당은 확정 후 수정한다.

**팀 구조도**
- Project Manager / 아키텍트: 김정현
- AI · 온디바이스 파이프라인: 김정현, 박종섭, 유재혁
- 백엔드(Firebase · Cloud Functions 프록시 · 외부 API): 박종섭, 유재혁
- Android UI/UX(Compose): 양예찬, 최예인
- 문서·테스트: 전원

**수행조직 및 업무분장**
- **PM / 아키텍트**: 전체 일정 관리, 하이브리드 아키텍처(Gemma–Gemini 연결)와 오케스트레이터 제어 로직 설계, 스파이크 판정 기준 수립, DEVLOG 관리.
- **AI · 온디바이스 파이프라인**: Gemma 4 제약 디코딩·프롬프트 설계, 상태 압축 파이프라인과 Room 병합, PII 스크러버, EmbeddingGemma RAG(골든 테스트 포함), Guardrail·Reflection 검증 게이트.
- **백엔드**: Firebase Auth·Firestore 스키마와 보안 규칙, FCM 푸시 트리거, 외부 API 프록시(시크릿·재시도·검증), 카카오 로컬·기상청 연동.
- **Android UI/UX**: Compose 화면(채팅·AI 진행·리포트 카드·캘린더·후기), 디자인 시스템, 내비게이션, 상태 관리와 비동기 진행 표시.
- **문서·테스트**: 유스케이스·요구사항 현행화, 실기기 검증 시나리오 수행, 출시 준비 항목(서명·정책) 점검.

## 7. 개발 일정

(그림 삽입: `fig_gantt.png` — 2026-03 ~ 2026-11 간트차트, 실적/계획 구분)

| 기간 | 주요 작업 | 상태 |
|---|---|---|
| 2026-03 ~ 04 | 주제 선정, 요구 분석, 제안서 작성, 하이브리드 LLM·하네스 아키텍처 설계 | 완료 |
| 2026-05 | Android 앱 골격·채팅 UI, 참여자 성향 프로필 누적 저장 | 완료 |
| 2026-06 | Room DB + Gemma 4 압축 파이프라인, Gemini Function Calling·오케스트레이터, 프라이버시 방화벽, Firebase 로그인·Firestore 채팅·FCM·안읽음, AI 리포트 카드·캘린더, 중간 보고서 제출 | 완료 |
| 2026-07 | 디자인 시스템 마이그레이션·치명 버그 수정, PII 스크러버, EmbeddingGemma 온디바이스 RAG, Guardrail 3-상태·Reflection·재시도 상한, Firestore 보안 규칙·R8 난독화, constrained decoding, 추천 결과 영속 | 완료 |
| 2026-09 | 외부 API 키 서버 격리(Functions 프록시), 출시 준비 착수(서명·규칙 배포·정책), 기말 보고서 현행화 착수 | 진행 중 |
| 2026-10 | 출시 준비 완료, Play 내부 테스트 트랙 등록, 실기기 검증·버그 수정 | 계획 |
| 2026-11 | 기말 보고서 완성, 최종 발표 자료·시연 준비, 최종 제출(11-30) | 계획 |
