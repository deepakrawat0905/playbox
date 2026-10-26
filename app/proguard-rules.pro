# The app's own classes are kept whole: models and caches are read and
# written by Gson through reflection, and the fragments are created by name.
-keep class com.playboxpro.tv.** { *; }
-keepclassmembers class com.playboxpro.tv.** { *; }

# Gson
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-dontwarn com.google.gson.**

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** { **[] $VALUES; public *; }
-dontwarn com.bumptech.glide.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# FFmpeg audio decoder: created by reflection from DefaultRenderersFactory.
-keep class androidx.media3.decoder.ffmpeg.** { *; }
