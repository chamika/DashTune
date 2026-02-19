# ============================================
# DashTune ProGuard/R8 Rules
# ============================================

# --- Crashlytics: preserve line numbers for readable stack traces ---
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- AccountAuthenticator (accessed by Android system via service binding) ---
-keep class com.chamika.dashtune.auth.Authenticator { *; }
-keep class com.chamika.dashtune.auth.AuthenticatorService { *; }

# --- SLF4J (service loader discovery) ---
-keep class org.slf4j.impl.** { *; }
-keep class org.slf4j.LoggerFactory { *; }
-dontwarn org.slf4j.spi.CallerBoundaryAware
-dontwarn org.slf4j.spi.LoggingEventBuilder

# --- Jellyfin SDK (kotlinx.serialization) ---
-keepattributes *Annotation*
-keep class org.jellyfin.sdk.model.api.** { *; }
-keep class org.jellyfin.sdk.model.serializer.** { *; }

# --- OkHttp ---
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Kotlin Serialization ---
-keepattributes InnerClasses
-keep,includedescriptorclasses class org.jellyfin.sdk.**$$serializer { *; }
-keepclassmembers class org.jellyfin.sdk.** {
    *** Companion;
}
-keepclasseswithmembers class org.jellyfin.sdk.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Ktor (used by Jellyfin SDK) ---
-dontwarn io.ktor.**
-keep class io.ktor.** { *; }

# --- Hilt (DI reflection/code generation) ---
-keep,allowobfuscation,allowshrinking class dagger.hilt.** { *; }
-keep,allowobfuscation,allowshrinking class javax.inject.** { *; }
-keep,allowobfuscation,allowshrinking class dagger.** { *; }
-dontwarn dagger.hilt.internal.aggregatedroot.codegen.**

# --- Media3 MediaLibraryService (system-bound service) ---
-keep class com.chamika.dashtune.DashTuneMusicService { *; }

# --- Kotlin Coroutines ---
-dontwarn kotlinx.coroutines.**

# --- Guava (LocalCache breaks under R8 optimization) ---
-keep class com.google.common.cache.** { *; }
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
