// Desktop verification harness for the Moonshine engine.
//
//   ./gradlew -p tools/moonshine-harness installDist
//   tools/moonshine-harness/build/install/moonshine-harness/bin/moonshine-harness
//       transcribe <model-dir> <wav> [--mode final|stream|both] [--threads N] [--accel cpu|xnnpack|nnapi]
//   ... package <model-package.zip> <scratch-dir>    (exercise the model package importer)
//
// <model-dir> is an extracted model package (tools/package-models.py --no-zip
// leaves one per model in its cache directory). reference.py runs the official
// moonshine-voice Python package on the same WAV for comparison, and build with
// -PortVersion=1.23.2 to use the ONNX Runtime version that package bundles
// (outputs then match word for word).
//
// On Windows, ONNX Runtime >= 1.2x crashes in msvcp140.dll when the JVM's own
// (older) msvcp140.dll is loaded first. Run with a JDK copy whose bin/
// msvcp140.dll, vcruntime140.dll and vcruntime140_1.dll are replaced by the
// current ones from C:\Windows\System32.

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.0"
    application
}

// Compile the engine sources directly from voiceinput-shared, so the harness
// always tests exactly the code that ships in the app. Only pure-JVM packages
// are included (no Android APIs).
val shared = "../../voiceinput-shared/src/main/java/org/futo/voiceinput/shared"

sourceSets {
    main {
        kotlin.srcDir("src/main/kotlin")
        kotlin.srcDir("$shared/moonshine/core")
        kotlin.srcDir("$shared/modelpackage")
        kotlin.srcDir("$shared/punctuation")
        kotlin.srcDir("$shared/context")  // ModelPackageStore validates context packages
        kotlin.exclude("**/ModelPackages.kt")
    }
}

dependencies {
    // Desktop build of the same ONNX Runtime version the app uses
    // (com.microsoft.onnxruntime:onnxruntime-android).
    implementation("com.microsoft.onnxruntime:onnxruntime:${findProperty("ortVersion") ?: "1.31.0"}")
    // Same version as voiceinput-shared (model package manifest parsing).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("MainKt")
    applicationDefaultJvmArgs = listOf("-Xmx4g")
}
