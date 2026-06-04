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
        versionCode = providers.gradleProperty("versionCode")
            .map(String::toInt).orElse(1).get()
        versionName = providers.gradleProperty("versionName")
            .orElse("1.0.0").get()
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = System.getenv("RELEASE_STORE_PASSWORD") ?: "android"
            keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "androiddebugkey"
            keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
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
