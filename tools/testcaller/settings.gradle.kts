pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// Standalone project on purpose: it is NOT included by the root settings.gradle.kts,
// so CI (build.yml / fdroid-apk.yml / release.yml) never builds or bundles it.
rootProject.name = "polished-testcaller"
include(":app")
