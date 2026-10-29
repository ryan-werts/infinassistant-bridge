# ProGuard / R8 rules for release builds (REQ-042).
#
# Default order in build.gradle.kts:
#   1. getDefaultProguardFile("proguard-android-optimize.txt")  — base rules
#   2. proguard-rules.pro (this file)                            — app-specific
#
# Keep the additions below grouped by library + minimal in scope. Over-broad
# `-keep class **` rules silently disable R8's whole-program optimization and
# inflate the release APK by ~30-60%; reach for them only when a specific
# crash is reproducible in release mode.

# ──────────────────────────────────────────────────────────────────────────
# kotlinx-serialization (CBOR + JSON)
#   Keeps generated serializers reachable so the runtime can find them
#   reflectively for @Serializable data classes.
# ──────────────────────────────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Keep companion-object serializers for every @Serializable class.
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep all @Serializable data classes' fields so the serializer can populate them.
-keep @kotlinx.serialization.Serializable class * {
    *;
}

# ──────────────────────────────────────────────────────────────────────────
# Hilt / Dagger
#   The KSP-generated module + entry-point classes must survive R8.
# ──────────────────────────────────────────────────────────────────────────
-keep,includedescriptorclasses class dagger.hilt.** { *; }
-keep,includedescriptorclasses class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-keepclasseswithmembers class * {
    @dagger.hilt.android.AndroidEntryPoint *;
}
-keep @dagger.hilt.android.HiltAndroidApp class *
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }
-keep @dagger.Module class *
-keep @dagger.hilt.InstallIn class *
-keepclassmembers class * {
    @javax.inject.Inject <init>(...);
    @javax.inject.Inject <fields>;
}

# ──────────────────────────────────────────────────────────────────────────
# Jetpack Compose
#   Compose generally R8-safe; these rules cover the specific reflective
#   paths Compose runtime uses for Composable lookups.
# ──────────────────────────────────────────────────────────────────────────
-keep class androidx.compose.runtime.** { *; }
-keepclassmembers class androidx.compose.runtime.** { *; }
-keep,allowobfuscation class * {
    @androidx.compose.runtime.Composable <methods>;
}

# ──────────────────────────────────────────────────────────────────────────
# Nordic DFU (no.nordicsemi.android.dfu)
#   The DFU service uses reflection to load DfuBaseService subclasses.
# ──────────────────────────────────────────────────────────────────────────
-keep class no.nordicsemi.android.dfu.** { *; }
-keep class no.nordicsemi.android.error.** { *; }
-dontwarn no.nordicsemi.android.dfu.**

# ──────────────────────────────────────────────────────────────────────────
# BLE callbacks (Android system) — the BluetoothGattCallback methods are
# invoked reflectively from the OS; loss of these is a runtime crash.
# ──────────────────────────────────────────────────────────────────────────
-keepclassmembers class * extends android.bluetooth.BluetoothGattCallback {
    public *;
}
-keepclassmembers class * extends android.bluetooth.le.ScanCallback {
    public *;
}

# ──────────────────────────────────────────────────────────────────────────
# Kotlin Coroutines — keep the debug-only Job state machine details out of
# the warning list when minify is on (cosmetic).
# ──────────────────────────────────────────────────────────────────────────
-dontwarn kotlinx.coroutines.debug.**

# ──────────────────────────────────────────────────────────────────────────
# OkHttp — the HA WebSocket path uses OkHttp transitively. R8 strips its
# internal classes safely but it warns; silence the warnings rather than
# keep the whole package.
# ──────────────────────────────────────────────────────────────────────────
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------------------------------------------------------------------------
# Tink (pulled in by androidx.security / EncryptedSharedPreferences, used by
# TokenStore) references optional Google API-client and Joda-Time classes from
# KeysDownloader, a remote-key-fetching path this app never touches. Without
# these rules R8 fails the release build outright with "Missing class".
#
# This is why `assembleRelease` had never succeeded — only debug APKs are
# built here, so the failure went unnoticed.
# ---------------------------------------------------------------------------
-dontwarn com.google.api.client.http.GenericUrl
-dontwarn com.google.api.client.http.HttpHeaders
-dontwarn com.google.api.client.http.HttpRequest
-dontwarn com.google.api.client.http.HttpRequestFactory
-dontwarn com.google.api.client.http.HttpResponse
-dontwarn com.google.api.client.http.HttpTransport
-dontwarn com.google.api.client.http.javanet.NetHttpTransport$Builder
-dontwarn com.google.api.client.http.javanet.NetHttpTransport
-dontwarn org.joda.time.Instant
