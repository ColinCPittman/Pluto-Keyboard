// Desktop verification harness for the context stage (voiceinput-shared/.../context).
//
// Compiles the stage's Kotlin sources directly from voiceinput-shared (pure JVM, no Android
// APIs) and runs them against the desktop build of libfutoctxlm (same C++ and llama.cpp
// submodule as the app, see voiceinput-shared/src/main/cpp/CMakeLists.txt):
//
//   ./gradlew -p tools/context-harness installDist
//   tools/context-harness/build/install/context-harness/bin/context-harness run \
//       --lib <libfutoctxlm.dll> --model <model.gguf> --lexicon <soundalike-en.lex> \
//       --testset tools/context-bench/testset.jsonl --out <results.jsonl> [--threads 8]
//   ... compare <kotlin results.jsonl> <rescore.py results.jsonl>
//   ... lexicon <soundalike-en.lex> [words...]       (lexicon load time / lookups)
//
// On Windows, use a JDK whose VC++ runtime is current (D:\FUTO-models\jdk-24-harness).

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.0"
    application
}

val shared = "../../voiceinput-shared/src/main/java/org/futo/voiceinput/shared"

sourceSets {
    main {
        kotlin.srcDir("src/main/kotlin")
        kotlin.srcDir("$shared/context")
        resources.srcDir("../../voiceinput-shared/src/main/resources")
    }
}

dependencies {
    // same version as voiceinput-shared
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
    applicationDefaultJvmArgs = listOf("-Xmx2g")
}
