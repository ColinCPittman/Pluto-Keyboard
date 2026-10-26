// Standalone desktop harness for the voice-input context stage (not part of the app build).
// Run with the repo's wrapper:  ./gradlew -p tools/context-harness installDist
rootProject.name = "context-harness"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
