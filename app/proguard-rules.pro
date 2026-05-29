-keepattributes Signature
-keepattributes *Annotation*

-keep class com.georgernstgraf.polishedrecognition.api.dto.** { *; }
-keepclassmembers class com.georgernstgraf.polishedrecognition.api.dto.** { *; }

-dontwarn okhttp3.**
-dontwarn retrofit2.**
