import java.util.Properties


plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    id("com.google.gms.google-services")
}

val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    namespace = "com.navoodi.morimi"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.navoodi.morimi"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "WEATHER_API_KEY", "\"${localProperties.getProperty("WEATHER_API_KEY") ?: ""}\"")
        buildConfigField("String", "GEMINI_API_KEY", "\"${localProperties.getProperty("GEMINI_API_KEY") ?: ""}\"")
        buildConfigField("String", "KAKAO_REST_API_KEY", "\"${localProperties.getProperty("KAKAO_REST_API_KEY") ?: ""}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md"
            )
        }
    }
}

// ─── 의존성 충돌 해소 ───────────────────────────────────────────────────────
// 1) protobuf: google-genai → protobuf-java(full), firebase → protobuf-javalite
//    Android에서는 javalite만 사용하므로 protobuf-java 전체 제거
// 2) gRPC 버전 불일치: firebase-firestore가 grpc-core:1.62.2를 쓰지만
//    google-genai가 grpc-api:1.70.0을 요구 → grpc-core만 구버전에 고정되고
//    grpc-api만 올라가 InternalGlobalInterceptors(1.65에서 제거)를 못 찾아 크래시.
//    모든 io.grpc 모듈을 1.70.0으로 통일해 버전 불일치를 없앤다.
configurations.all {
    exclude(group = "com.google.protobuf", module = "protobuf-java")
    resolutionStrategy {
        eachDependency {
            if (requested.group == "io.grpc") {
                useVersion("1.70.0")
            }
        }
    }
}

dependencies {

    implementation(platform("com.google.firebase:firebase-bom:34.15.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation(libs.firebase.messaging)
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
    implementation(libs.google.genai)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}