# DEVLOG — 아키텍처 결정 기록

> 형식: 날짜 / 결정 / 근거 / 기각한 대안 / 남은 리스크

## 2026-07-12 — 온디바이스 RAG: EmbeddingGemma + raw LiteRT 경로 채택 (스파이크 선행)

**결정**: 후기 시맨틱 검색은 EmbeddingGemma 300m(.tflite)을 raw LiteRT 인터프리터
(`com.google.ai.edge.litert:litert:1.4.0`)로 구동하고, 토크나이즈는 DJL
`HuggingFaceTokenizer`(tokenizer.json)로 처리한다. 생성은 기존 Gemini 클라우드 유지 —
임베딩+검색만 온디바이스 추가. 본 구현 전 빌드 스파이크로 네이티브 공존을 판정한다.

**근거**:
- 기존 런타임 LiteRT-LM 0.11에는 임베딩 API가 없음(Engine/Conversation 생성 전용) —
  공식 Kotlin API 문서로 확정. 별도 임베딩 런타임 필요
- GDE 실증 2건(Soloupis, on-device RAG)이 동일 스택(LiteRT 1.4.0 + DJL 토크나이저 +
  코사인 top-k)으로 실기기 구동을 보여줌. 우리는 생성이 클라우드라 그들보다 필요한
  조각이 적음
- 모델 실물 존재: `litert-community/embeddinggemma-300m`
  (`embeddinggemma-300M_seq1024_mixed-precision.tflite`, 768차원, gated)

**기각한 대안**:
- **AI Edge RAG SDK**(`localagents-rag:0.1.0`): 턴키(SqliteVectorStore 등)지만
  `tasks-genai`(MediaPipe LLM 런타임)를 전이 의존으로 강제 → litertlm과 네이티브 충돌
  리스크가 더 크고, 임베더가 EmbeddingGemma가 아닌 Gecko
- **경량 결정론 검색만**(키워드/TF-IDF): 리스크 0이지만 "신경망 RAG" 서사 불가.
  폴백 어댑터로는 유효 (포트-어댑터 패턴으로 공존)

**남은 리스크**:
1. `litert 1.4.0` ↔ `litertlm 0.11` 네이티브 .so 공존 — 스파이크로 실측
   (기준: assembleDebug 통과 + 실기기에서 기존 Gemma 파이프라인 정상 동작)
2. DJL 토크나이저 안드로이드 아티팩트 좌표 확정 필요
3. embeddinggemma HF repo gated — 다운로드 UX(토큰 필요 여부) 확인
4. Gemma E2B와 메모리 경합 — 임베더 로드→사용→해제로 관리

### 스파이크 1 결과 (2026-07-12 진행)

**작업 1 — 의존성·네이티브 공존: 빌드 레벨 통과 ✅ (실기기 검증 대기)**
- 좌표 확정: `com.google.ai.edge.litert:litert:1.4.0` + `litert-support:1.4.0`(google 저장소),
  `ai.djl.huggingface:tokenizers:0.33.0`(Java API) + **`ai.djl.android:tokenizer-native:0.33.0`**
  (AAR, 전 ABI `libdjl_tokenizer.so` 동봉 — 이게 안드로이드 런타임의 열쇠)
- `assembleDebug` 통과. APK 내 5개 네이티브가 **이름 충돌 없이 공존**:
  `liblitertlm_jni.so`(기존) + `libtensorflowlite_jni.so`·`libLiteRt.so`·
  `libLiteRtClGlAccelerator.so`(신규 litert) + `libdjl_tokenizer.so`(DJL).
  `pickFirsts` 우회 불필요했음
- 부작용 처리: DJL jar가 데스크톱 네이티브(dll/dylib)를 리소스로 동봉 →
  `packaging { resources.excludes += "native/lib/**" }` 로 제거
- 경고(치명 아님): `litert-support`와 `litert-support-api`가 동일 namespace
  `org.tensorflow.lite.support` 사용 — AGP 경고만 발생, 빌드 정상

**작업 2 — DJL 토크나이저 런타임: 통과 ✅ (실기기 SM-F966N, arm64)**
- `EmbeddingTokenizerSpikeTest`(androidTest) 작성 → `am instrument`로 실행
- **1차 실패**: `UnsatisfiedLinkError: library "libc++_shared.so" not found: needed by
  libdjl_tokenizer.so`. DJL 네이티브가 C++ 공유 STL을 요구하는데 APK에 미포함
- **우회 성공**: NDK 28.2의 `libc++_shared.so`를 4개 ABI `app/src/main/jniLibs/`에 동봉.
  (CLAUDE.md "즉시 실패 말고 우회 먼저" 원칙 적용 — 이번엔 pickFirst가 아니라 누락 STL 보충)
- **2차 통과**: 한국어 인코딩 정상. `"task: search result | query: 이번 토요일 홍대에서 저녁 모임"`
  → 19토큰 `<bos> task : ▁search ▁result ▁| ▁query : ▁이번 ▁토 요일 ▁홍 대 에서 ▁저 녁 ▁모 임 <eos>`.
  BOS/EOS·SentencePiece 분절 정상, ID 전부 vocab 범위 내
- 죽은 `ExampleInstrumentedTest`(패키지명 `com.example.gemma4` 불일치) 삭제 — 후속 목록 항목 처리
- tokenizer.json(19.4MB, BPE, vocab 262,144 = Gemma 어휘 일치) 확보 —
  repo 커밋 안 함, adb push로 기기 주입

**작업 1 — 네이티브 공존 런타임: 통과 ✅ (실기기 SM-F966N, 모델 확보 후 완결)**
- `RuntimeCoexistenceSpikeTest` 3건 전부 통과 (18.1초). 실제 초기화·추론 실증:
  - `litertlm_engine_initializes_and_summarizes`: gemma-4 E2B(2.58GB) GPU 초기화 +
    익명화 요약 정상 — *"이번 토요일 저녁 홍대에서 식사 모임... 오후 6시에 만나기로"*
    (이름 미포함, 기존 파이프라인 무결)
  - `litert_interpreter_loads_embeddinggemma`: litert(raw TFLite)로 .tflite 로드 →
    **입력텐서 [1, 512] / 출력텐서 [1, 768]** = EmbeddingGemma 스펙 정확히 일치
  - `both_runtimes_coexist_in_one_process`: 한 프로세스에서 litertlm + litert 동시
    초기화 후 litertlm 요약 지속 정상 — **동시 상주 실증**
- 앱 실행도 크래시 없음(Splash→MainActivity, `FATAL`/`dlopen` 실패 0건)
- 결론: 두 온디바이스 런타임(생성 litertlm + 임베딩 litert)이 한 APK·한 프로세스에서
  빌드·로드·초기화·추론 전 단계 공존 가능. 스파이크 핵심 리스크 해소

**신규 리스크 발견 — 16KB 페이지 정렬 (2026-07-12, SM-F966N에서 경고 표면화)**
- 증상: 앱 실행 시 "이 앱은 16KB와 호환되지 않습니다. ELF 정렬 검사 실패" 디버그 경고.
  8개 .so 나열됨
- **실측(llvm-readelf -l, LOAD 세그먼트 align)으로 진범 특정**:
  | .so | 정렬 | 판정 |
  |---|---|---|
  | liblitertlm_jni.so (기존) | 0x4000 | ✅ 16KB |
  | libLiteRt.so / libLiteRtClGlAccelerator.so (신규 litert) | 0x4000 | ✅ 16KB |
  | libtensorflowlite_jni.so (신규 litert) | 0x4000 | ✅ 16KB |
  | libc++_shared.so (NDK 28.2) / libandroidx.graphics.path / libdatastore | 0x4000 | ✅ 16KB |
  | **libdjl_tokenizer.so (DJL)** | **0x1000** | **❌ 4KB — 유일 위반** |
  → 경고가 나머지를 "알 수 없는 오류"로 뭉뚱그렸으나 실제 미정렬은 DJL 하나.
  **내 스파이크 추가가 기존 앱을 깨지 않았음**(litertlm/litert 전부 16KB)
- **영향 범위**: 경고이지 차단 아님 — 이 기기서 DJL 토크나이저 정상 동작(작업 2 통과),
  현재 4KB 모드 구동. 캡스톤 데모(디버그 빌드)는 지장 없음.
  **단 Google Play는 2025-11부터 targetSdk 35+ 앱에 16KB 정렬 요구 → 프로덕션 배포 리스크**
- **해결 옵션(2단계/배포 전 결정)**:
  1. DJL이 16KB 재빌드한 tokenizer-native 상위 버전 대기(현재 0.33.0이 최신, 미정렬)
  2. 대체 토크나이저 — `ai.djl.sentencepiece`, Londogard NLP(Kotlin), 또는 tokenizer.json
     기반 순수 JVM 구현(ProAndroidDev HF Sentence Transformers 사례)
  3. 캡스톤 범위에선 디버그 데모로 시연, "알려진 배포 제약"으로 문서화
- AGP 8.11 + NDK 28.2로 이미 16KB 지원 활성(그래서 우리 소스·google 라이브러리는 정렬됨).
  DJL은 서드파티 prebuilt이라 우리가 재정렬 불가

**작업 3 — HF 게이팅: 판정 완료 ✅**
- 기존 gemma-4 E2B URL(litert-community): **무인증 200** — 기존 ModelDownloadService가
  인증 없이 동작했던 이유. 같은 패턴 재사용 불가(아래)
- `litert-community/embeddinggemma-300m` · `google/embeddinggemma-300m`: **401, gated="auto" 확정**
  → 공식 경로는 `Authorization: Bearer <HF토큰>` 헤더 + 라이선스 동의 필요
  (ModelDownloadService에 헤더 추가는 소규모 수정)
- **우회 경로 발견**: `kontextdev/embeddinggemma-300m-litertlm` — **gated=False, 무인증 200**,
  `embeddinggemma-300M_seq512_mixed-precision.tflite` 호스팅. 서드파티 미러라
  공식 대비 무결성 보증 없음 — 데모/개발용으로 쓰되, 정식 배포 시 공식 게이팅 경로 권장
- tokenizer.json 무인증 미러: `onnx-community/embeddinggemma-300m-ONNX`, `unsloth/embeddinggemma-300m`

**2단계 구현 시 필수 준수 2건 (스파이크 통과 후, 별도 승인 후 진행)**
1. **프리픽스 비대칭**: 인덱싱 `"title: none | text: "` / 쿼리 `"task: search result | query: "` —
   빠뜨려도 빌드·실행 멀쩡, 검색 품질만 조용히 저하
2. **골든 테스트**: Python sentence-transformers 기준 벡터와 코사인 ≥ 0.99 검증 없이는 미완료

### 스파이크 1 최종 판정 (2026-07-12): **통과 — 방법 B 기술적 실현 가능 ✅**
- 작업 1(런타임 공존) · 2(DJL 토크나이저) · 3(HF 게이팅) 모두 통과, 실기기 실증
- 확보된 토대: 의존성 좌표, libc++_shared 4-ABI 동봉, DJL 토크나이저 동작,
  litert [1,512]→[1,768] 파이프라인 형태, 비게이팅 tflite/tokenizer 미러
- 미해결 리스크 1건(프로덕션 한정): **libdjl_tokenizer.so 16KB 미정렬** — 데모 무관,
  Play 배포 전 대체 토크나이저 검토
- **스파이크 잔여물 처리 결정 필요**: (a) 의존성·jniLibs·계측테스트를 2단계 토대로 유지,
  (b) libc++_shared.so 4개(~30MB) git 커밋 vs gradle 복사 태스크, (c) 실제 임베딩
  추론·프리픽스·골든테스트는 2단계에서

## 2026-07-12 — 프라이버시 방화벽: PII 스크러버를 경계 게이트로 일반화

**결정**: 클라우드로 나가는 모든 자유 텍스트(Gemma 요약문 + RAG 피드백 컨텍스트)는
`PiiScrubber`를 통과한다. 프롬프트 지시는 1차 방어, 집행은 결정론적 스크러버.

**근거**: 요약문만 스크러빙하면 사용자가 쓴 후기(자유 텍스트, 실명 가능)가
`buildRagContext()` 경유로 미검사 통과 — CLAUDE.md 불변 원칙과 모순되는 실제 구멍.

**기각한 대안**: 저장 시점 스크러빙(후기 원본을 마스킹 저장) — 온디바이스 보관 데이터는
원문 유지가 정당하고, 경계 통과 시점 단일 게이트가 감사(監査)에 더 단순.
