# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.yani.client.**$$serializer { *; }
-keepclassmembers class com.yani.client.** { *** Companion; }
-keepclasseswithmembers class com.yani.client.** { kotlinx.serialization.KSerializer serializer(...); }

# JavaScript bridge used by the player and captcha WebViews
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
