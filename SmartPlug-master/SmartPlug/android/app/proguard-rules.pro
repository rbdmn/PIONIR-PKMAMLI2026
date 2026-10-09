# Moshi reflection-based adapters need generic signatures + annotations kept.
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.smartplug.app.data.remote.dto.** { *; }
-dontwarn okhttp3.**
-dontwarn retrofit2.**
