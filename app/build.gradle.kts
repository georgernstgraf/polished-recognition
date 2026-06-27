import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.io.File

abstract class GitHashSource : ValueSource<String, ValueSourceParameters.None> {
    override fun obtain(): String {
        val process =
            ProcessBuilder("git", "rev-parse", "--short=7", "HEAD")
                .directory(File(System.getProperty("user.dir")))
                .start()
        process.waitFor()
        return process.inputStream.bufferedReader().readText().trim()
    }
}

abstract class CommitCountSource : ValueSource<String, ValueSourceParameters.None> {
    override fun obtain(): String {
        val tagProcess =
            ProcessBuilder("git", "describe", "--tags", "--match", "v*", "--abbrev=0")
                .directory(File(System.getProperty("user.dir")))
                .start()
        tagProcess.waitFor()
        val tag = tagProcess.inputStream.bufferedReader().readText().trim()
        if (!tag.startsWith("v")) return "0"
        val countProcess =
            ProcessBuilder("git", "rev-list", "$tag..HEAD", "--count")
                .directory(File(System.getProperty("user.dir")))
                .start()
        countProcess.waitFor()
        return countProcess.inputStream.bufferedReader().readText().trim()
    }
}

plugins {
    id("com.android.application")
}

android {
    namespace = "com.georgernstgraf.polishedrecognition"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.georgernstgraf.polishedrecognition"
        minSdk = 30
        targetSdk = 36
        // === RELEASE INSTRUCTIONS ===
        // 1. Bump versionCode + versionName here
        // 2. git commit -m "chore: bump version to X.Y.Z"
        // 3. git tag vX.Y.Z && git push origin vX.Y.Z
        // 4. release.yml → signs + uploads to Play Store
        // 5. F-Droid auto-update picks up new tag automatically
        versionCode = 10001
        versionName = "1.0.1"

        val gitHash = providers.of(GitHashSource::class.java) {}.get().trim()
        buildConfigField("String", "GIT_HASH", "\"$gitHash\"")

        val commitCount = providers.of(CommitCountSource::class.java) {}.get().trim()
        val versionDisplay = if (commitCount == "0") versionName else "$versionName+$commitCount"
        buildConfigField("String", "VERSION_DISPLAY", "\"$versionDisplay\"")
    }

    val releaseKeystore = file("release.keystore")
    if (releaseKeystore.exists()) {
        signingConfigs {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("RELEASE_STORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: "android"
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
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        execution = "HOST"
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
            all { it.jvmArgs("-Xshare:off") }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.14.4")
    testImplementation("com.google.truth:truth:1.4.4")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
