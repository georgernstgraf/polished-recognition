plugins {
    id("com.android.application") version "9.1.1" apply false
    // #146: :core/:analyzer are pure-JVM Kotlin modules. Pinned to AGP
    // 9.1.1's built-in Kotlin version (:app's compile classpath resolves
    // kotlin-stdlib 2.2.10) so :app's compiler can read :core's metadata.
    kotlin("jvm") version "2.2.10" apply false
}

