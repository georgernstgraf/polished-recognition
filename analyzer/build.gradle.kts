// #146: PC-side corpus-first analyzer — a Kotlin-JVM CLI compiling the REAL
// production pipeline sources via :core (zero drift: FragmentPreparer,
// FragmentTranscriber, SttRequestRunner are the same classes the device runs).
plugins {
    kotlin("jvm")
    application
}

java {
    toolchain {
        // Same pin as :app/:core (#107): MockK/ASM cannot read newer bytecode.
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(project(":core"))
    // Explicit pins of the transitive versions :core resolves — the analyzer
    // uses gson + okhttp directly, so they must not float.
    implementation("com.google.code.gson:gson:2.8.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.truth:truth:1.4.4")
}

application {
    mainClass.set("com.georgernstgraf.polishedrecognition.analyzer.MainKt")
}

tasks.withType<Test> {
    // Same headroom as :app/:core unit tests (PITFALLS: executor exit 2 on
    // undersized heap).
    maxHeapSize = "2048m"
    jvmArgs("-Xshare:off")
}
