// #146: PC-side bundle analyzer (Kotlin-JVM CLI) — module stub only in this
// phase; the CLI itself is Phase 2. Depends on :core once the CLI lands.
plugins {
    kotlin("jvm")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
