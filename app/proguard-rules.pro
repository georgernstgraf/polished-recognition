# General
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# App components
-keep class com.georgernstgraf.polishedrecognition.PolishedRecognitionApp { *; }
-keep class com.georgernstgraf.polishedrecognition.ui.** { *; }
-keep class com.georgernstgraf.polishedrecognition.service.** { *; }
-keep class com.georgernstgraf.polishedrecognition.audio.** { *; }
-keep class com.georgernstgraf.polishedrecognition.config.** { *; }
-keep class com.georgernstgraf.polishedrecognition.pipeline.** { *; }

# API DTOs (Gson)
-keep class com.georgernstgraf.polishedrecognition.api.dto.** { *; }
-keepclassmembers class com.georgernstgraf.polishedrecognition.api.dto.** { *; }

# Retrofit interfaces
-keep,allowobfuscation interface com.georgernstgraf.polishedrecognition.api.** { *; }

# Gson
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Retrofit
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes RuntimeInvisibleAnnotations, RuntimeInvisibleParameterAnnotations
-keepattributes Exceptions
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn retrofit2.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# Kotlin
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }

# AppCompat / Material
-dontwarn com.google.android.material.**
-keep class com.google.android.material.** { *; }
-dontwarn androidx.appcompat.**
-keep class androidx.appcompat.** { *; }
