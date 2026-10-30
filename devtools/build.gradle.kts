// Desktop tools: run the Virtual Path Engine on recorded images or video frames and write
// overlays and JSON (javax.imageio is JVM-only, so this stays out of the app).
//   ./gradlew :devtools:run --args="photo.jpg --hfov 65 --height 1.3 --pitch 22"
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

dependencies {
    implementation(project(":engine"))
    implementation("org.json:json:20240303")
}

kotlin { jvmToolchain(17) }

application {
    mainClass.set(providers.gradleProperty("mainClass").orElse("run.basir.devtools.AnalyzeImageKt"))
}
