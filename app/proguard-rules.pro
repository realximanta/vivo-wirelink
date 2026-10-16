# language: proguard, file: app/proguard-rules.pro
# Minification is disabled in release, so this file is inert today.
# Kept because app/build.gradle references it via proguardFiles.

# --- keep app entry points if you ever flip minifyEnabled true ---
-keep class com.vivo.wirelink.MainActivity { *; }
-keep class com.vivo.wirelink.KeeperActivity { *; }
-keep class com.vivo.wirelink.AdminActivity { *; }
-keep class com.vivo.wirelink.KeeperService { *; }

# --- OkHttp / Okio ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- kotlinx.coroutines ---
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# --- CameraX ---
-keep class androidx.camera.** { *; }

# --- GMS location ---
-keep class com.google.android.gms.location.** { *; }

# --- keep annotations ---
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
