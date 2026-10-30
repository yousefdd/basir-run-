plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.basir.run"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.basir.run"
        minSdk = 26
        targetSdk = 35
        versionCode = 29
        versionName = "0.9.20"

        // Phones only: keeps the native libraries of MediaPipe and ONNX Runtime to two ABIs.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        // Committed debug key: every cloud build installs over the previous one.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Models are read straight from the APK, so they stay uncompressed.
    androidResources {
        noCompress += listOf("tflite", "onnx")
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
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")

    val cameraX = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    // On-device object detection (Apache 2.0).
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // Depth Anything V2 and YOLO26n from the blind-navigation tool, on the phone's CPU.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    // Virtual Path Engine (lines, lanes, path planning, guidance, haptic language).
    implementation(project(":engine"))

    testImplementation("junit:junit:4.13.2")
}
