// #146: shared pure-JVM module — the transcription/audio/API core both :app
// and :analyzer compile against. Package names are UNCHANGED from :app so
// :app keeps its imports; :analyzer compiles the REAL production sources.
// Pure JVM by contract: no android.* / androidx.* anywhere under src/.
plugins {
    kotlin("jvm")
}

java {
    toolchain {
        // Same pin as :app (#107): MockK/ASM cannot read newer bytecode.
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    // Same versions :app resolves (retrofit 2.9.0 pulls gson 2.8.5) so the
    // merged :app classpath is byte-identical — no version drift.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    api("com.squareup.retrofit2:retrofit:2.9.0")
    api("com.squareup.retrofit2:converter-gson:2.9.0")
    api("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.14.4")
    testImplementation("com.google.truth:truth:1.4.4")
}

tasks.withType<Test> {
    // Same headroom as :app's unit tests (PITFALLS: executor exit 2 on
    // undersized heap).
    maxHeapSize = "2048m"
    jvmArgs("-Xshare:off")
}
