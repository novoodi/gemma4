# 에이전트 흐름 결함 탐색 테스트 (2026-10)

- 작성일: 2026-10-01 · 브랜치 `gemma4_V2`
- 목적: 정상 동작 확인이 아니라 **결함 발견**. 기존 코드는 수정하지 않고 테스트만 추가했다.
- 대상: `AssistantOrchestrator.orchestrate()` 전체 흐름(JVM 단위 테스트)
- 테스트 파일 (`app/src/test/java/com/navoodi/morimi/service/`)
  - `AgentFlowFakes.kt` — 페이크·하네스
  - `AgentFlowParticipantsDefectTest.kt` — 시나리오 1·2·3
  - `AgentFlowInputFeedbackDefectTest.kt` — 시나리오 4·5
  - `AgentFlowGeminiDefectTest.kt` — 시나리오 6
- 실패한 케이스는 고치지 않고 `@Ignore("결함: …")`로 남겼다. 수정한 뒤 `@Ignore`를 지우면 회귀 테스트가 된다.

## 방법

| 교체 지점 | 페이크 | 비고 |
|---|---|---|
| `GeminiGateway` | `ScriptedGemini` | 호출 순서대로 응답 재생, 전송 본문 전부 기록(경계 누출 검사용) |
| `GuardrailService(search)` | `FakePlaceWorld` | 카카오처럼 비슷한 가게 최대 10건 반환 → 실제 `PlaceMatcher`가 판정 |
| `OnDeviceLlmPort` | `FakeOnDeviceLlm` | 기본은 `MockOnDeviceLlm`. 이름을 흘리는 요약을 주입할 수 있음 |
| `FeedbackRetriever` | `FixedRetriever` / 실제 `KeywordFallbackRetriever` + `FakeFeedbackDao` | RAG 회수분 고정 또는 실제 키워드 검색 |
| `MetricsRepository` | `null` | Room 없이 동작(생성자 기본 경로) |

ContextClassifier·PromptSieve·PiiScrubber·Reflection·AHP·PlaceRanker는 **실제 코드**가 돈다.
대화 날짜는 2026-10-01(목)로 고정했다(토요일 = 10-03, 일요일 = 10-04).

### 데이터 유입 경로 (코드 확인 결과)

- **참가자**: `Message.senderName`과 `UserStatusEntity.participants`만으로 들어온다.
  현재 방 멤버 목록은 `orchestrate()`에 전달되지 않는다. `ChatViewModel.summarize()`는 방의 전체 메시지 이력을 넘긴다.
- **선호·불호**: `UserStatusEntity.preferences` — 누가 말한 것인지 정보 없이 합집합으로 누적된다(`StatusCompressionPipeline.mergeStatus`).
- **메시지 순서**: `PromptSieve.lastMention`이 **리스트 순서**를 기준으로 한다. `timestamp`로 정렬하는 곳이 없다.
- **피드백**: `FeedbackRetriever.retrieve(query = 익명 요약문)` → 프롬프트 텍스트(RAG 섹션) + `PlaceRanker`의 과거 만족 기준. 결정론적 배제 게이트는 없다.
- **Guardrail 지역**: `callGeminiWithTools`의 `city` — Gemini 도구 호출 인자에서만 나온다. 도구를 부르지 않으면 `"미정"`.

## 결과 요약

- 추가 케이스 **59건** · 통과 31 · **실패 28** (`@Ignore`) = 결함 26 + 한계 2(S4-3, S5-6)
- 주담당별 실패 수: 최예인 19 · 유제혁 5 · 박종섭 4 · 양예찬 0 (UI는 JVM 대상 아님, S2-1 공동)

## 결과 표

결함 여부: **결함** = 기대와 다름 / 통과 = 기대대로 / **한계** = 설계상 의도이나 시나리오 기대와 어긋남(논의 필요)

### 시나리오 1 — 참가자 1명 / 2명 / 5명

| 시나리오 | 케이스 | 기대 결과 | 실제 결과 | 결함 여부 | 원인 파일 | 담당 |
|---|---|---|---|---|---|---|
| S1-1 정상 | 1명 대화 | 추천 성공, 인원은 지어내지 않고 "미정", 이름 미전송 | 기대대로 | 통과 | — | — |
| S1-2 정상 | 2명 대화 | 인원 "2명 (대화 참여자 기준)", 1회 성공 | 기대대로 | 통과 | — | — |
| S1-3 정상 | 5명 + Gemma 요약이 5명 실명을 흘림 | 경계에서 성·이름 모두 마스킹 | 기대대로 | 통과 | — | — |
| S1-4 경계 | 5명 발화 + "우리 4명이서" | 명시 인원 4명이 우선 | 기대대로 | 통과 | — | — |
| S1-5 이상 | 동명이인 2명(senderId 다름) + 1명 | 인원 3명 | **2명** — senderName으로 중복 제거 | **결함** | `PromptSieve.speakerHeadcount` | 최예인 |
| S1-6 이상 | 이름이 빈 발화자 2명 + 실명 2명 | 크래시 없음, 인원 2명 | 기대대로 | 통과 | — | — |
| S1-7 근접 | 대화에만 나오는 제3자(지훈)가 프로필 명단에 있음 | 마스킹 | 기대대로 | 통과 | — | — |
| S1-8 근접 | 같은 상황, 프로필 압축 전(userStatus 없음) | 마스킹 | **"지훈"이 Gemini로 전송** — 명단 밖·호칭 없는 이름 미탐 | **결함** | `PiiScrubber` (명단은 `AssistantOrchestrator.buildKnownNames`) | 유제혁 (공동: 최예인) |

### 시나리오 2 — 5명 중 1명(정하늘)이 중간에 나감

| 시나리오 | 케이스 | 기대 결과 | 실제 결과 | 결함 여부 | 원인 파일 | 담당 |
|---|---|---|---|---|---|---|
| S2-1 비정상 | 퇴장 후 인원 | 4명 | **5명** — 전체 이력 발화자를 셈 | **결함** | `AssistantOrchestrator`/`PromptSieve` (멤버 목록 입력 없음, `ChatViewModel`이 전체 이력 전달) | 최예인 (공동: 양예찬) |
| S2-2 비정상 | 퇴장자 선호 "좋아요: 회" | 선호 슬롯에서 제외 | **"회" 그대로 전송** | **결함** | `StatusCompressionPipeline.mergeStatus` (선호가 사람별 귀속 없음) | 유제혁 |
| S2-3 비정상 | 퇴장자 불호 "싫어요: 술집" + 술집 추천 | 1회 성공 | **Reflection 위반으로 3회 재시도** 후 폴백 | **결함** | `StatusCompressionPipeline` → `ReflectionService` | 유제혁 (공동: 최예인) |
| S2-4 비정상 | 퇴장 후 델타 병합 | 참가자 목록에서 퇴장자 제거 가능 | 합집합이라 영구 잔류, 퇴장 신호 경로 없음 | **결함** | `StatusCompressionPipeline.mergeStatus`, `ChatRepository.leaveRoom`(user_status 미갱신) | 유제혁 |
| S2-5 정상 | 퇴장자 실명이 요약에 등장 | 마스킹 | 기대대로(이력에 발화가 남아 있어 명단에 포함) | 통과 | — | — |
| S2-6 경계 | 퇴장자의 마지막 말 "해운대 가야 돼서 빠질게" | 지역은 합의된 강남 | **해운대** | **결함** | `PromptSieve.lastMention` (발화자 멤버십 무시) | 최예인 |
| S2-7 정상 | 남은 사람들의 토요일·강남 합의 | 일시 2026-10-03, 지역 강남 | 기대대로 | 통과 | — | — |

### 시나리오 3 — 메시지 순서 뒤섞임 · 동시 발화

| 시나리오 | 케이스 | 기대 결과 | 실제 결과 | 결함 여부 | 원인 파일 | 담당 |
|---|---|---|---|---|---|---|
| S3-1 비정상 | "홍대" → "홍대 말고 강남"을 역순 리스트로 전달 | 강남 | **홍대**(철회된 값 전송) | **결함** | `AssistantOrchestrator`/`PromptSieve` (timestamp 정렬 없음) | 최예인 |
| S3-2 비정상 | "토요일" → "일요일로 바꾸자"를 역순으로 | 10-04(일) | **10-03(토)** | **결함** | 같음 | 최예인 |
| S3-3 정상 | 같은 시각 5명 동시 발화 | 5건 모두 요약기에 전달, 인원 5명 | 기대대로 | 통과 | — | — |
| S3-4 근접 | 같은 시각 같은 내용("ㅇㅋ") 3건 | 덮어쓰기 없이 4건 전부 전달 | 기대대로 | 통과 | — | — |
| S3-5 경계 | 같은 시각 강남/홍대 상충, 입력 순서만 바꿈 | 두 순서의 결과가 같음 | **홍대 vs 강남** — 입력 순서 의존(비결정) | **결함** | `PromptSieve.lastMention` | 최예인 |
| S3-6 이상 | timestamp 0·음수·Long.MAX | 크래시 없음 | 기대대로 | 통과 | — | — |
| S3-7 정상 | 정렬된 입력, 동시 제안 후 "강남으로 확정" | 강남, 장소 VERIFIED | 기대대로 | 통과 | — | — |

### 시나리오 4 — 이상 입력

| 시나리오 | 케이스 | 기대 결과 | 실제 결과 | 결함 여부 | 원인 파일 | 담당 |
|---|---|---|---|---|---|---|
| S4-1 이상 | 빈 대화(메시지 0건) | 클라우드 호출 없이 Failed | **Gemini 호출 후 Success**(UI에서만 막음) | **결함** | `AssistantOrchestrator.orchestrate` (입력 검증 없음) | 최예인 |
| S4-2 이상 | 이모지만 | 일시·지역 "미정", 미확정 슬롯 표기, 진행 | 기대대로 | 통과 | — | — |
| S4-3 이상 | 영어만("Saturday in Gangnam") | 요일 인식(10-03) | **"미정"** | **한계** | `PromptSieve` (한국어 전용 추출기) | 최예인 |
| S4-4 경계 | 장소 언급 없음 | 지역 미정, 검색어에 "null"·"미정" 없음 | 기대대로 | 통과 | — | — |
| S4-5 정상 | 지역 "미정" + 부산 가게 추천 | 지역 검사 생략 → VERIFIED | 기대대로 | 통과 | — | — |
| S4-6 이상 | 2000건 대화 | 끝까지 처리 | 기대대로 | 통과 | — | — |
| S4-7 이상 | 2000건 대화의 요약 입력 크기 | 요약기 입력이 컨텍스트 한도(~8k) 이내 | **34,893자 전체 전달**(윈도우·청크 없음). 실기기 OOM·잘림 여부 확인 필요 | **결함** | `AssistantOrchestrator` → `LlmService.summarizeForPrivacy` | 최예인 |
| S4-8 정상 | "토요일 홍대" → "홍대 말고 강남, 일요일로" | 강남 · 10-04 | 기대대로 | 통과 | — | — |
| S4-9 근접 | "강남 가자" → "강남은 좀 별로야"(대안 없음) | 지역 미정 | **강남** — 철회 메시지를 건너뛰고 이전 언급으로 복귀 | **결함** | `PromptSieve.lastMention`/`extractArea` | 최예인 |

### 시나리오 5 — 추천 후 한 명이 불만 피드백

| 시나리오 | 케이스 | 기대 결과 | 실제 결과 | 결함 여부 | 원인 파일 | 담당 |
|---|---|---|---|---|---|---|
| S5-1 정상 | 별점 1 불만 후기 | 다음 프롬프트 RAG 섹션에 후기·별점 포함 | 기대대로 | 통과 | — | — |
| S5-2 비정상 | 모델이 불만 장소(미미식당)를 다시 추천 | 최종 결과에서 제외(또는 재시도) | **포함됨**(순위만 2위로) | **결함** | `AssistantOrchestrator`/`ReflectionService` (피드백 배제 게이트 없음) | 최예인 |
| S5-3 비정상 | 불만 "미미식당 강남점" → 추천 "미미식당 역삼점" | 비슷한 곳도 제외 | **포함됨** | **결함** | 같음 | 최예인 |
| S5-4 근접 | 불만 후기에 조사가 붙은 이름("미미식당은") | 랭킹 감점 | **감점 없음**(1위 유지) | **결함** | `PlaceRanker.pastSatisfaction` (토큰 매칭이 조사를 처리하지 않음) | 최예인 |
| S5-5 정상 | 띄어 쓴 이름("미미식당 너무…") | 랭킹 감점 | 기대대로 | 통과 | — | — |
| S5-6 근접 | 별점 없는(0점) 불만 "최악이었음" | 감점 | 감점 없음(설계상 미평가는 중립) | **한계** | `PlaceRanker`/`PastImpression` | 최예인 |
| S5-7 이상 | 다른 방 후기 속 제3자 실명("지훈이랑") | 경계에서 마스킹 | **"지훈"이 Gemini로 전송** | **결함** | `PiiScrubber` (명단이 현재 방 기준: `buildKnownNames`) | 유제혁 (공동: 최예인) |
| S5-8 비정상 | 실제 `KeywordFallbackRetriever`로 장소 불만 후기 검색 | 회수되어 프롬프트에 포함 | **회수 안 됨** — 쿼리가 장소명 없는 익명 요약문이라 겹치는 키워드 없음 | **결함** | `AssistantOrchestrator`(쿼리 구성)/`KeywordFallbackRetriever` | 최예인 |
| S5-9 정상 | 별점 5 만족 후기 | 해당 장소 1위 | 기대대로 | 통과 | — | — |

### 시나리오 6 — Gemini 비정상 응답 · Guardrail · 재시도 · 최종 실패

| 시나리오 | 케이스 | 기대 결과 | 실제 결과 | 결함 여부 | 원인 파일 | 담당 |
|---|---|---|---|---|---|---|
| S6-1 비정상 | 존재하지 않는 장소만 반복 | 3회 후 Failed, 2·3회차 프롬프트에 "존재하지 않는" 피드백 | 기대대로 | 통과 | — | — |
| S6-2 정상 | 가짜 → 실존 | 2회 만에 성공, VERIFIED | 기대대로 | 통과 | — | — |
| S6-3 정상 | 도구로 서울 지정 후 부산 가게 | "모임 지역(서울)" 피드백으로 재시도 → 성공 | 기대대로 | 통과 | — | — |
| S6-4 비정상 | 도구 호출 없이 부산 가게(대화는 강남) | 지역 밖으로 거절 | **통과** — Guardrail에 city "미정" 전달 | **결함** | `AssistantOrchestrator.callGeminiWithTools` (지역 슬롯 미사용) | 최예인 |
| S6-5 비정상 | 깨진 JSON 반복 | 3회 후 "파싱 실패" Failed | 기대대로 | 통과 | — | — |
| S6-6 근접 | 깨진 JSON 1회 → 정상 | 2회 만에 복구 | 기대대로 | 통과 | — | — |
| S6-7 비정상 | 빈 응답(candidates 없음) | 3회 후 Failed("비어") | 기대대로 | 통과 | — | — |
| S6-8 이상 | 빈 JSON `{}` | 재시도·실패 | **추천 0곳 Success** | **결함** | `GuardrailService.verify`(후보 0건 = 통과), `AssistantOrchestrator` | 박종섭 (공동: 최예인) |
| S6-9 이상 | `recommendedPlaces: []` | 재시도·실패 | **추천 0곳 Success** | **결함** | 같음 | 박종섭 (공동: 최예인) |
| S6-10 근접 | Reflection만 실패 ×2 → 3회차 깨진 JSON | 보존한 폴백으로 Success | **Failed** — catch 분기가 fallbackSuccess를 무시 | **결함** | `AssistantOrchestrator.orchestrate` catch 블록 | 최예인 |
| S6-11 정상 | Reflection만 계속 실패 | 3회 후 폴백 Success | 기대대로 | 통과 | — | — |
| S6-12 이상 | 25자 초과 지어낸 장소명 | 검증 없이 통과 금지 | **UNVERIFIED로 통과** — 길이 필터로 검증 대상에서 빠짐 | **결함** | `GuardrailService.verify` (`2..25`) | 박종섭 |
| S6-13 이상 | 6곳 추천, 6번째가 가짜 | 6번째도 검증 | **검증 없이 통과** | **결함** | `GuardrailService` (`MAX_CANDIDATES = 5`) | 박종섭 |
| S6-14 서버 장애 | 장소 검색 프록시 전면 장애 | UNKNOWN으로 1회 통과(재시도 없음) | 기대대로 | 통과 | — | — |
| S6-14b 서버 장애 | 검색 "결과 없음" | 장애와 구분해 재시도 → 3회 후 Failed | 기대대로 | 통과 | — | — |
| S6-15 서버 장애 | Gemini 프록시 계속 실패 | 3회 후 Failed | 기대대로 | 통과 | — | — |
| S6-16 서버 장애 | 모델이 도구만 무한 호출 | 왕복 상한으로 종료(3×9=27회 호출) | 기대대로 | 통과 | — | — |
| S6-17 근접 | "미미식당 - 조용하고 맛있음"(ASCII 하이픈) | 장소명 "미미식당" | **이유까지 장소명에 저장** | **결함** | `AssistantOrchestrator.parseGeminiPlaceEntry` | 최예인 |
| S6-18 이상 | 장소 배열에 숫자·객체 원소 혼입 | 문자열 원소는 살림 | **시도 전체 폐기 ×3 → Failed** | **결함** | `AssistantOrchestrator.stringList`(`getString`) | 최예인 |

## 우선순위 제안

1. **프라이버시 (S1-8, S5-7)** — 명단 밖 실명이 디바이스 경계를 넘는다. CLAUDE.md 불변 원칙 위반이다.
   명단을 넓히거나(후기 작성 당시 방의 참가자, 대화 본문의 호격 패턴) 스크러버의 미등록 이름 탐지를 강화해야 한다.
2. **Guardrail 우회 (S6-4, S6-8/9, S6-12, S6-13)** — 지어낸 장소·다른 지역 장소·빈 결과가 "성공"으로 나간다.
3. **퇴장자 반영 불가 (S2-1~S2-4, S2-6)** — 멤버 목록과 선호의 사람별 귀속이 없는 구조 문제다. 한 곳만 고쳐서는 해결되지 않는다.
4. **순서 의존 (S3-1, S3-2, S3-5)** — `orchestrate()` 진입 시 `timestamp`(동률이면 id) 정렬 한 줄로 대부분 해소된다.
5. **피드백 미반영 (S5-2, S5-3, S5-4, S5-8)** — 불만 장소에 대한 결정론적 배제가 없다.

## JVM에서 검증하지 못한 부분 (기록만)

| 항목 | 이유 |
|---|---|
| `searchPlace` 도구 경로 (`KakaoLocalService.searchKeyword`) | 오케스트레이터가 object를 직접 호출해 주입 지점이 없고 Firebase Functions(`CloudProxy`)를 탄다. 테스트는 도구를 부르지 않거나 네트워크 없이 끝나는 `getWeather(date="미정")`만 사용 |
| 실제 날씨 조회 (3~10일 뒤 날짜) | 위와 같은 이유(`WeatherService` → `CloudProxy`) |
| Room 저장·조회(`MetricsRepository`, `UserStatusDao`, `FeedbackDao` SQL) | Android 런타임 필요. `metricsRepository = null`, DAO는 페이크로 대체 |
| 실제 Gemma 요약·압축 (`LlmService`) | 온디바이스 모델·GPU 필요(에뮬레이터 미지원). S4-7의 컨텍스트 초과 영향은 **실기기 확인 필요** |
| 방 나가기 실제 흐름 (`ChatRepository.leaveRoom` → Firestore) | Firebase 필요. 코드상 `user_status`를 갱신하지 않는 것만 확인 |
| UI(`ChatViewModel`, Compose 화면) — 양예찬 담당 | Android 의존. 빈 대화 차단이 UI에만 있다는 점은 코드로 확인(`ChatViewModel.summarize`) |
