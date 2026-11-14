plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.nexus.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.nexus.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 16
        versionName = "2.5.0"
        // The on-device brain ships native code; arm64 covers every modern phone and keeps the APK small.
        ndk { abiFilters += setOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs { pickFirsts += setOf("**/libc++_shared.so", "**/libonnxruntime.so") }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module",
                "META-INF/versions/9/previous-compilation-data.bin", "META-INF/INDEX.LIST",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Free on-device language model runtime (Google MediaPipe LLM Inference).
    implementation("com.google.mediapipe:tasks-genai:0.10.27")
    // Free, fully offline speech recognition with streaming partial results (always-on "Hey Nexus").
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    // Unpacks offline voice packs (.tar.bz2). The neural voice runtime (sherpa-onnx) is fetched by CI.
    implementation("org.apache.commons:commons-compress:1.27.1")
}
