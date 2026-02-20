# ═══════════════════════════════════════════════════════════════════════════════
#  Nine Lives Audio — ProGuard / R8 Rules
# ═══════════════════════════════════════════════════════════════════════════════

# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ─── Kotlinx Serialization ────────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep @Serializable classes
-keep,includedescriptorclasses class com.ninelivesaudio.app.**$$serializer { *; }
-keepclassmembers class com.ninelivesaudio.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.ninelivesaudio.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ─── Retrofit ─────────────────────────────────────────────────────────────────
-keepattributes Signature, Exceptions
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*

# Keep Retrofit API interfaces
-keep interface com.ninelivesaudio.app.data.remote.AudiobookshelfApi { *; }

# ─── OkHttp ───────────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ─── Room ─────────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ─── Hilt ─────────────────────────────────────────────────────────────────────
# Hilt's own consumer ProGuard rules handle most cases; only keep entry points
-keep @dagger.hilt.android.AndroidEntryPoint class *
-dontwarn dagger.hilt.**

# ─── Media3 ───────────────────────────────────────────────────────────────────
# Keep session/common classes needed by Media3 internals via reflection
-keep class androidx.media3.session.** { *; }
-keep class androidx.media3.common.** { *; }
-dontwarn androidx.media3.**

# ─── Coil ─────────────────────────────────────────────────────────────────────
-dontwarn coil.**

# ─── Domain Model (keep for reflection / serialization) ───────────────────────
-keep class com.ninelivesaudio.app.domain.model.** { *; }
-keep class com.ninelivesaudio.app.data.remote.dto.** { *; }
-keep class com.ninelivesaudio.app.data.local.entity.** { *; }

# ─── Security Crypto ─────────────────────────────────────────────────────────
-keep class androidx.security.crypto.** { *; }
