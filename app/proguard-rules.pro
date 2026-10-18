# Kotlin + Coroutines
-keepclassmembers class **$WhenMappings { <fields>; }
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** { volatile <fields>; }

# Room
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# org.json (used for router REST parsing)
-keep class org.json.** { *; }

# App data models (parsed via org.json / Room)
-keep class com.example.data.model.** { *; }

# Compose compiler artifacts
-dontwarn kotlin.**
