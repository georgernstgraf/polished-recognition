import org.gradle.jvm.toolchain.JavaLanguageVersion
import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
}

// --- Bundled in-app help (#107) ---------------------------------------------
// docs/help.html is the single source of the help text: the project site serves
// it as the "Help" page, and this task stages it (plus the images it
// references) into a generated assets directory so it ships inside the APK.
// Nothing is committed twice. Wired via the Variant API below.
abstract class StageHelpAssets : DefaultTask() {
    @get:InputFile
    abstract val helpHtml: RegularFileProperty

    @get:InputFile
    abstract val demoGif: RegularFileProperty

    @get:InputFile
    abstract val imePng: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun stage() {
        val out = outputDir.get().asFile
        fun copy(src: File, relative: String) {
            val target = out.resolve(relative)
            target.parentFile?.mkdirs()
            src.copyTo(target, overwrite = true)
        }
        copy(helpHtml.get().asFile, "help.html")
        copy(demoGif.get().asFile, "marketing/mastodon-demo.gif")
        copy(imePng.get().asFile, "img/ime-recording.png")
    }
}

val stageHelpAssets = tasks.register<StageHelpAssets>("stageHelpAssets") {
    description = "Stages docs/help.html and its images for the in-app help."
    helpHtml.set(rootProject.file("docs/help.html"))
    demoGif.set(rootProject.file("docs/marketing/mastodon-demo.gif"))
    imePng.set(rootProject.file("docs/img/ime-recording.png"))
    outputDir.set(layout.buildDirectory.dir("generated/helpAssets"))
}

android {
    namespace = "com.georgernstgraf.polishedrecognition"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.georgernstgraf.polishedrecognition"
        minSdk = 30
        targetSdk = 36
        // === RELEASE INSTRUCTIONS ===
        // 1. Bump versionCode + versionName here (and the static GIT_HASH
        //    fallback above — release.yml passes the true hash regardless)
        // 2. git commit -m "chore: bump version to X.Y.Z"
        // 3. git tag vX.Y.Z && git push origin vX.Y.Z
        // 4. release.yml → signs + uploads to Play Store
        // 5. F-Droid auto-update picks up new tag automatically
        versionCode = 10501
        versionName = "1.5.1"

        // The build hash comes from Gradle properties (-PbuildGitHash / -PbuildCommitCount,
        // passed by the CI release build). The STATIC default keeps local installs
        // cache-stable: a per-commit BuildConfig value (the old `git rev-parse HEAD`
        // value source) rebuilt compile → R8 → package on every push. Bump the static
        // GIT_HASH at release time; release.yml always passes the true values.
        val gitHash = providers.gradleProperty("buildGitHash").orElse("a9fc136").get()
        buildConfigField("String", "GIT_HASH", "\"$gitHash\"")

        val commitCount = providers.gradleProperty("buildCommitCount").orElse("0").get()
        val versionDisplay = if (commitCount == "0") versionName else "$versionName+$commitCount"
        buildConfigField("String", "VERSION_DISPLAY", "\"$versionDisplay\"")

        // #125: the shadow comparison and the full-context-at-stop strategy
        // were DIAGNOSTICS, not delivery features. REMOVED (#143) — the
        // offline bundle analysis (Epic #141, PC-side) replaces both; the
        // fragment join is the only delivery path.

        // #142: the audio-evidence RETENTION DEFAULT (seconds; 0 = no
        // retention — dictated audio stays off the disk in release). The
        // owner's test build (debug) defaults to 30 days (overridden below),
        // and the user can slide 0 → 50 years in Settings at any time.
        buildConfigField("long", "AUDIO_EVIDENCE_RETENTION_DEFAULT_SECONDS", "0L")

        // #142: the log streams are LIVE DEBUGGING only now — the forensic
        // record lives in `session.json` per session (Epic #141). One
        // unified depth for every build type.
        buildConfigField("int", "LOG_ROTATION_DEPTH", "32")
    }

    val keystoreProperties = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    val releaseKeystore = file(keystoreProperties.getProperty("storeFile", "release.keystore"))
    val releaseStorePassword = keystoreProperties.getProperty("storePassword") ?: System.getenv("RELEASE_STORE_PASSWORD")
    if (releaseKeystore.exists() && releaseStorePassword != null) {
        signingConfigs {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = keystoreProperties.getProperty("keyAlias") ?: System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = keystoreProperties.getProperty("keyPassword") ?: System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            if (releaseKeystore.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            // #142: the owner's test build (debug) defaults to 30 days of
            // evidence.
            buildConfigField("long", "AUDIO_EVIDENCE_RETENTION_DEFAULT_SECONDS", "2592000L")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    dependenciesInfo {
        // AGP embeds a "Dependency metadata" APK signing block by default, which
        // F-Droid's scanner rejects. Disable it for APKs (the Play AAB keeps its
        // own metadata via includeInBundle's default).
        includeInApk = false
    }

    buildFeatures {
        buildConfig = true
    }

    // #163/#193/#197: there is NO lint baseline. The 77-entry backlog the tree
    // carried when lint first ran (#163) was burned down to zero in #194–#197,
    // and the gate is total from here on: every finding either fails the build
    // or is an inline `@Suppress`/`lint.xml` ignore that names its reason at the
    // line. `warningsAsErrors` makes a warning as fatal as an error — a baseline
    // re-introduced to get a build green would silently re-legalise all of them,
    // so `XmlColorPolicyTest` fails if the file ever comes back.
    // TextContrast is an ERROR in `lint.xml` and is never suppressed: contrast
    // regressions must break the build, not join a backlog.
    lint {
        warningsAsErrors = true
    }

    testOptions {
        execution = "HOST"
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
            // 2048m of headroom for the Robolectric sandboxes (the JVM default
            // 512m is tight once a run carries hundreds of them). This is NOT
            // the cause of the `exit value 2` fork deaths #86/#131 blamed on
            // the heap: #198 found the real one — the app's JVM-global crash
            // handler (`PolishedRecognitionApp.onCreate`) turns ONE uncaught
            // background throwable into `Process.killProcess` +
            // `System.exit(2)`, and a unit-test JVM hosts that Application.
            // The audio pipeline no longer lets a throwable escape its
            // capture/fragment threads, and the IME tests capture the
            // default handler so a recurrence fails readably. Test-only
            // setting, no effect on release artifacts.
            all {
                it.jvmArgs("-Xshare:off")
                it.maxHeapSize = "2048m"
                // #162: the pixel render smoke test is opt-in — without
                // -Pcontrast.render=true it is skipped, never failed.
                it.systemProperty(
                    "contrast.render",
                    providers.gradleProperty("contrast.render").getOrElse("false")
                )
            }
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            stageHelpAssets,
            StageHelpAssets::outputDir
        )
    }
}

// Pin the build/test JVM to Java 21 (#107). Gradle otherwise uses whatever JDK
// it runs on; on JDK 25 MockK/ASM cannot read the newer bytecode
// ("Unsupported class file major version 69") and most tests fail. A JDK 21
// must be installed (auto-detected); CI already provides Temurin 21.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    // #146: the transcription/audio/API core — shared with :analyzer.
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.14.4")
    testImplementation("com.google.truth:truth:1.4.4")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
