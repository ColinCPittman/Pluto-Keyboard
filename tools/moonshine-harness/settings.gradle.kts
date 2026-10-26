// Standalone desktop harness for the Moonshine engine (not part of the app build).
// Run with the repo's wrapper:  ./gradlew -p tools/moonshine-harness run --args="..."
rootProject.name = "moonshine-harness"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
