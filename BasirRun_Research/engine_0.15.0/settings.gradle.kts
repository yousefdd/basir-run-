// Added for the research harness: the original zip ships only the engine module's build file.
pluginManagement { repositories { maven("https://maven-central.storage-download.googleapis.com/maven2/"); gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { maven("https://maven-central.storage-download.googleapis.com/maven2/"); mavenCentral() } }
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0" }
rootProject.name = "basirrun"
include(":engine")
include(":replay")
include(":appcheck")
project(":appcheck").projectDir = file("tools/appcheck")
project(":replay").projectDir = file("tools/replay")
