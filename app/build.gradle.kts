plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fishmerge.macro"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fishmerge.macro"
        minSdk = 30            // AccessibilityService.takeScreenshot 는 Android 11(API 30)부터
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        ndk {
            // 요즘 폰(arm64)만 포함해서 APK 용량을 줄임
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("org.opencv:opencv:4.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
