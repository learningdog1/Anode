# Project-specific ProGuard / R8 rules for NodePulse

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
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

# Jsoup
-keeppackagenames org.jsoup.nodes

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
