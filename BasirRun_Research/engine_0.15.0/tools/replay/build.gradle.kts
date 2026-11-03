// Research harness: replays recorded clips through the app's own vision and decision code on the
// JVM. It compiles the Android-free files of app_vision/ and app_decision/ unchanged, next to a
// stub of android.os.SystemClock (a replay clock) and a copy of the SurfaceResult holder.
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

dependencies {
    implementation(project(":engine"))
    implementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform(); testLogging { events("failed"); showStandardStreams = true } }

kotlin { jvmToolchain(17) }

sourceSets {
    main {
        kotlin {
            srcDir("../../app_vision")
            srcDir("../../app_decision")
            // Files that need the Android SDK (camera, bitmaps, ONNX Runtime for Android, threads)
            exclude("GuidancePipeline.kt", "ObstacleDetector.kt", "seg/SurfaceModels.kt", "seg/SurfaceChannel.kt",
                "depth/DeepChannel.kt", "depth/OnnxModels.kt")
        }
    }
}

application { mainClass.set("run.basir.replay.ReplayMainKt") }
