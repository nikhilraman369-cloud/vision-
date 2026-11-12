pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.7.3"
        id("org.jetbrains.kotlin.android") version "2.1.20"
        id("org.jetbrains.kotlin.jvm") version "2.1.20"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.1.20"
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "nexus"

// The platform-independent brain of NEXUS (language understanding, planning,
// memory, workflow engine, Claude integration). Builds on any JVM.
include(":core")

// The Android application. Only included when an Android SDK is available so
// that `:core` can be built and tested on machines without one.
val localProps = java.util.Properties().apply {
    val f = file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    localProps.getProperty("sdk.dir") != null
if (hasAndroidSdk) {
    include(":app")
} else {
    logger.lifecycle("NEXUS: no Android SDK found, building :core only")
}
