plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.anurag.voxa"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.anurag.voxa"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "∞"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Voice Recognition
    implementation("com.google.android.gms:play-services-mlkit-speech-recognition:17.0.1")

    // Wake Word (Porcupine)
    implementation("ai.picovoice:porcupine-android:3.0.2")

    // Gemini AI
    implementation("com.google.ai.client.generativeai:generativeai:0.2.2")

    // OCR & Vision
    implementation("com.google.mlkit:text-recognition:16.0.0")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.0")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.0")
    implementation("com.google.mlkit:text-recognition-korean:16.0.0")

    // Work Manager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // HTTP Client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON
    implementation("com.google.code.gson:gson:2.10.1")

    // Root (optional)
    implementation("com.github.topjohnwu.libsu:core:5.2.1")

    // Screen Capture
    implementation("androidx.media:media:1.7.0")

    // Floating Window
    implementation("com.github.tbruyelle:rxpermissions:0.12")
    implementation(libs.androidx.activity)
    implementation(libs.androidx.uiautomator.shell)
}