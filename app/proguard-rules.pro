# ─────────────────────────────────────────────────────────────────────────────
# 모이미 R8 규칙
# release 빌드 난독화·축소(isMinifyEnabled=true). Gradle 캐시의 실제 AAR/JAR을
# 열어 consumer proguard 규칙 유무를 확인한 결과에 근거해 작성됨.
# ─────────────────────────────────────────────────────────────────────────────

# 크래시 스택 역난독화용 — mapping.txt와 함께 원본 줄 번호 보존
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
# 리플렉션 직렬화(Jackson 등)에 필요한 메타데이터
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# native 메서드 이름 보존 (JNI)
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ── 1) LiteRT-LM 0.11.0 ──────────────────────────────────────────────────────
# AAR에 consumer 규칙 없음. liblitertlm_jni.so가 JNI로 Kotlin 콜백 클래스
# (Conversation$JniMessageCallbackImpl 등)를 이름으로 조회 → 전체 keep 필수.
-keep class com.google.ai.edge.litertlm.** { *; }

# ── 2) google-genai 1.56.0 ───────────────────────────────────────────────────
# 순수 JAR(내장 규칙 없음). types 패키지가 AutoValue + Jackson 리플렉션
# 직렬화 → 전체 keep이 안전.
-keep class com.google.genai.** { *; }
-dontwarn com.google.genai.**

# Jackson (genai의 JSON 직렬화 엔진)
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**

# genai가 컴파일타임에 참조하지만 이 앱에서 exclude/미포함된 의존성
-dontwarn com.google.protobuf.ProtocolStringList
-dontwarn com.google.protobuf.Descriptors**
-dontwarn com.google.protobuf.Message
-dontwarn com.google.protobuf.MessageOrBuilder
-dontwarn org.slf4j.**
-dontwarn javax.naming.**

# ── 3) gRPC 1.70.0 (전 모듈 강제 통일) ───────────────────────────────────────
# JAR/AAR 모두 consumer 규칙 없음. provider는 ServiceLoader/리플렉션 로드.
-keep class * extends io.grpc.ManagedChannelProvider { *; }
-keep class * extends io.grpc.NameResolverProvider { *; }
-keep class * extends io.grpc.LoadBalancerProvider { *; }
-keep class io.grpc.android.AndroidChannelBuilder { *; }
-keep class io.grpc.okhttp.OkHttpChannelBuilder { *; }
-dontwarn io.grpc.netty.**
-dontwarn io.netty.**
-dontwarn com.google.j2objc.annotations.**
# grpc-okhttp가 참조하는 레거시 Square okhttp2(com.squareup.okhttp.*) — 미포함, 런타임 미도달
-dontwarn com.squareup.okhttp.**

# ── 4) protobuf-javalite 3.25.5 (Firestore 와이어 프로토콜) ───────────────────
# lite 런타임은 schema에 인코딩된 필드 이름을 리플렉션으로 접근 →
# rename/strip 시 InvalidProtocolBufferException.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }

# ── 규칙이 불필요한 것 (근거 기록) ───────────────────────────────────────────
# - Firebase auth/firestore/messaging/analytics: AAR에 consumer 규칙 내장.
#   Firestore 모델은 전부 수동 매핑(toObject 리플렉션 미사용) → 앱 모델 keep 불필요.
# - Room 2.6.1: AAR consumer 규칙 내장. KSP 생성 코드는 정적 참조.
# - Compose / kotlinx.coroutines: META-INF/com.android.tools/r8 규칙 내장.
# - org.json: Android 플랫폼 API.
# - FcmService: AndroidManifest 등록 컴포넌트 → AGP가 자동 keep.

# ── 한계 (R8로 해결 불가) ────────────────────────────────────────────────────
# BuildConfig의 API 키 3종(WEATHER/GEMINI/KAKAO)은 문자열 상수라 R8이
# 난독화하지 못한다. APK를 unzip + strings만 해도 평문 노출된다.
# minify는 "코드 구조" 은닉일 뿐이며, 키 보호의 근본 해결은
#   (1) 콘솔 측 키 제한(패키지명 + SHA-1, API별 스코프) 및
#   (2) Gemini 호출의 서버(Cloud Function) 프록시 이전
# 으로만 가능하다. → 후속 과제.
