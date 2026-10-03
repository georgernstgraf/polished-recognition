plugins {
    id("com.android.application")
}

android {
    namespace = "com.georgernstgraf.polishedrecognition.testcaller"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.georgernstgraf.polishedrecognition.testcaller"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
