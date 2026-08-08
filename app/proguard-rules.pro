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

# ─── WorkManager ──────────────────────────────────────────────────────────────
# WorkManager builds an InputMerger by reflection in WorkerWrapper, before it
# ever calls doWork():
#     Class.forName(name).getDeclaredConstructor().newInstance()
#
# work-runtime's own consumer rules ship `-keep class * extends
# androidx.work.InputMerger`, which keeps the CLASS and says nothing about its
# MEMBERS. Nothing in our code calls that constructor directly, so R8 full mode
# (the AGP default) removed it as dead code. Reflection then threw,
# createInputMergerWithDefaultFallback() returned null, and WorkerWrapper called
# setFailedAndResolve() before doWork() ever ran.
#
# That killed every WorkManager job in the app, which in practice meant downloads
# never left "Queued" in any release build from 2.0.1 onward (issue #64). Debug
# builds do not minify, so this was invisible until R8's own usage.txt was read.
#
# The `verifyWorkManagerKeepRules*` tasks in app/build.gradle.kts fail the build
# if these rules ever stop taking. Do not delete one without the other.
#
# The base classes get their own rules on purpose. ProGuard's `extends` matches
# subclasses only, never the named class itself, so the wildcard rules below
# cover OverwritingInputMerger and ArrayCreatingInputMerger but not
# androidx.work.InputMerger. In practice the base constructor survives anyway
# because the kept subclass constructors call super(), but that is a transitive
# side effect, not a guarantee, and InputMerger's own <init> was one of the three
# R8 deleted in the broken build. Say it out loud instead of inheriting it.
-keepclassmembers class androidx.work.InputMerger {
    public <init>();
}
-keepclassmembers class * extends androidx.work.InputMerger {
    public <init>();
}
-keepclassmembers class androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keepclassmembers class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class androidx.work.impl.background.systemjob.SystemJobService
-keep class androidx.work.impl.foreground.SystemForegroundService

# ─── Coil ─────────────────────────────────────────────────────────────────────
-dontwarn coil.**

# ─── Domain Model (keep for reflection / serialization) ───────────────────────
-keep class com.ninelivesaudio.app.domain.model.** { *; }
-keep class com.ninelivesaudio.app.data.remote.dto.** { *; }
-keep class com.ninelivesaudio.app.data.local.entity.** { *; }

# ─── Security Crypto ─────────────────────────────────────────────────────────
-keep class androidx.security.crypto.** { *; }
