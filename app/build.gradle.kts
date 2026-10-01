import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    id("com.google.gms.google-services")
}

// 외부 API 키(Gemini·카카오·기상청)는 APK에 넣지 않는다 — Firebase Functions 시크릿에만 존재하고
// 앱은 service/CloudProxy 를 통해 서버 프록시를 호출한다 (DEVLOG 2026-09-05 참조).
//
// 예외: 카카오맵 JavaScript 키(추천 결과 지도 표시용). 이 키는 브라우저에 노출되도록 설계된
// 클라이언트 키로, 카카오 콘솔에 등록한 웹 도메인에서만 동작한다(REST 키와 달리 비밀이 아니다).
// 그래도 저장소에는 넣지 않는다 — local.properties(git 제외)에서만 읽고, 없으면 빈 값으로 빌드돼
// 앱은 "지도를 불러올 수 없음 + 주소/카카오맵 링크"로 대체 표시한다 (docs/KAKAO_MAP_KEY.md).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localProp(key: String, default: String = ""): String =
    (localProps.getProperty(key) ?: default).trim().replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.navoodi.morimi"
    compileSdk = 36
    // NDK r28+ 는 16KB 페이지 정렬을 기본 지원. libc++_shared.so 복사 태스크의 소스이기도 함.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.navoodi.morimi"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 카카오맵 JavaScript 키 — local.properties의 kakao.map.js.key (없으면 빈 값 → 지도 대체 표시)
        buildConfigField("String", "KAKAO_MAP_JS_KEY", "\"${localProp("kakao.map.js.key")}\"")
        // 지도 HTML의 출처(origin). 카카오 콘솔 [플랫폼 > Web 사이트 도메인]에 같은 값을 등록해야 한다
        buildConfigField("String", "KAKAO_MAP_ORIGIN", "\"${localProp("kakao.map.origin", "http://localhost")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            // android.util.Log 등 스텁 호출이 예외 대신 기본값을 반환하도록 (JVM 단위 테스트)
            isReturnDefaultValues = true
            // Robolectric(마이그레이션 런타임 검증)이 리소스·매니페스트를 읽어야 한다
            isIncludeAndroidResources = true
        }
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md",
                // DJL tokenizers jar의 데스크톱 네이티브(dll/dylib/linux so) — 안드로이드에선
                // ai.djl.android:tokenizer-native AAR의 libdjl_tokenizer.so를 쓰므로 죽은 무게(~30MB)
                "native/lib/**"
            )
        }
    }
    // DJL tokenizer-native AAR은 libc++_shared.so를 동봉하지 않아 런타임 UnsatisfiedLinkError 발생.
    // NDK sysroot에서 복사해 주입한다(30MB 바이너리를 git에 넣지 않기 위해 생성 디렉토리 사용).
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/libcxx-jniLibs"))
}

// libc++_shared.so를 NDK에서 ABI별로 복사 (DEVLOG 2026-07-12 / DJL 16KB·STL 이슈 참조)
val copyLibcxxShared by tasks.registering {
    val ndkDir = android.ndkDirectory
    val outDir = layout.buildDirectory.dir("generated/libcxx-jniLibs")
    outputs.dir(outDir)
    doLast {
        val prebuilt = ndkDir.resolve("toolchains/llvm/prebuilt").listFiles()
            ?.firstOrNull { it.isDirectory }
            ?: error("NDK prebuilt 디렉토리를 찾을 수 없음: $ndkDir (ndkVersion 설치 확인)")
        val libBase = prebuilt.resolve("sysroot/usr/lib")
        val abiToTriple = mapOf(
            "arm64-v8a" to "aarch64-linux-android",
            "armeabi-v7a" to "arm-linux-androideabi",
            "x86_64" to "x86_64-linux-android",
            "x86" to "i686-linux-android",
        )
        abiToTriple.forEach { (abi, triple) ->
            val src = libBase.resolve("$triple/libc++_shared.so")
            require(src.exists()) { "libc++_shared.so 없음: $src" }
            val dst = outDir.get().dir(abi).asFile.apply { mkdirs() }
            src.copyTo(dst.resolve("libc++_shared.so"), overwrite = true)
        }
    }
}
tasks.named("preBuild") { dependsOn(copyLibcxxShared) }

dependencies {

    implementation(platform("com.google.firebase:firebase-bom:34.15.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation(libs.firebase.messaging)
    // 외부 API 프록시(callable) — service/CloudProxy
    implementation(libs.firebase.functions)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.litertlm.android)
    // [스파이크 1] EmbeddingGemma 임베딩 경로 — raw LiteRT + DJL 토크나이저 (DEVLOG 2026-07-12 참조)
    implementation(libs.litert)
    implementation(libs.litert.support)
    implementation(libs.djl.tokenizers)
    implementation(libs.djl.tokenizer.native.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    // ── 고도화 이식: 마이그레이션 런타임 검증 + UI/통합 계측 테스트 ──────────
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation(libs.androidx.room.testing)
    // JVM 단위 테스트에서 org.json.JSONObject 실제 구현 사용 (android.jar 스텁은 예외 발생)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}