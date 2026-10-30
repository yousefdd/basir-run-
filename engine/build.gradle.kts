// Virtual Path Engine: line detection, line tracking, path planning, guidance and the haptic
// language, in pure Kotlin. No Android dependencies, so it is tested on the JVM with real
// images and used as-is by the app.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.json:json:20240303")
}

kotlin { jvmToolchain(17) }

tasks.test {
    useJUnitPlatform()
    // Real test images live in the repo (see src/test/resources/guideline/README.md)
    systemProperty("basir.testdata", project.file("src/test/resources").absolutePath)
    testLogging { events("failed"); showStandardStreams = true }
}
