// Compile check of the app's vision and decision code (including the Android-bound files changed in
// research round 6) against minimal API stubs of the Android SDK and CameraX. The stubs only declare the
// signatures the app uses; nothing here runs. ObstacleDetector.kt (MediaPipe) is replaced by a stub class.
plugins { id("org.jetbrains.kotlin.jvm") }

dependencies {
    implementation(project(":engine"))
    implementation("org.json:json:20240303")
    implementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")
}

kotlin { jvmToolchain(17) }

sourceSets {
    main {
        kotlin {
            srcDir("../../app_vision")
            srcDir("../../app_decision")
            exclude("ObstacleDetector.kt")
        }
    }
}
