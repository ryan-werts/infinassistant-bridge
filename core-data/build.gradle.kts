plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.w6tech.infinassistant.core.data"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(project(":core-common"))
    implementation(project(":core-domain"))
    implementation(project(":core-ha"))
    implementation(project(":core-ble"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    implementation(libs.kotlinx.serialization.json)

    // Testing
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // A test certificate for a wss:// MockWebServer (HaWebSocketTlsRedirectTest).
    testImplementation(libs.okhttp.tls)
    // PayloadSizeMeasurementTest builds the defaults-included encoding the shared encoder does not produce.
    testImplementation(libs.kotlinx.serialization.cbor)
    // org.json ships only as a non-functional stub in the JVM unit-test android.jar; the real
    // implementation lets DfuVersionGuard's manifest parser actually run under test.
    testImplementation("org.json:json:20231013")
    // mockk can mock final classes (e.g. HaApi) that preflightCheck never invokes but the
    // WatchFaceOrchestrator constructor requires.
    testImplementation("io.mockk:mockk:1.13.13")
}
