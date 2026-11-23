# Project-specific ProGuard / R8 rules for Anode

# Keep metadata required by Kotlinx Serialization, Navigation Compose routes, and Room
-keepattributes *Annotation*, RuntimeVisibleAnnotations, AnnotationDefault, Signature, InnerClasses, EnclosingMethod

# Kotlinx Serialization
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.nodeseek.app.**$$serializer { *; }
-keepclassmembers class com.nodeseek.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.nodeseek.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers @kotlinx.serialization.Serializable enum com.nodeseek.app.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Room (ensure generated _Impl database and converters survive R8 full mode)
-keep class * extends androidx.room.RoomDatabase {
    void <init>();
}
-keep class com.nodeseek.app.data.local.NodePulseDatabase_Impl { *; }

# WebView JavascriptInterface (Turnstile & same-origin fetch bridges)
-keepattributes JavascriptInterface
-keepclassmembers class com.nodeseek.app.** {
    @android.webkit.JavascriptInterface <methods>;
}

# JSch (com.github.mwiede:jsch dynamically loads KEX, ciphers, MACs, signatures,
# UserAuth, compression, and Random implementations via Class.forName)
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.kerberos.**
-dontwarn org.bouncycastle.**
-dontwarn com.sun.jna.**
-dontwarn org.newsclub.net.unix.**

# Jsoup
-keeppackagenames org.jsoup.nodes

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
