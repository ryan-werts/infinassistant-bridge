plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.serialization)
}

// Owner-specific values come from an untracked android/owner-overrides.properties (see
// owner-overrides.properties.example). Missing file or key = empty value. The file is read as
// plain "key=value" lines, so a UNC path needs no escaping.
val ownerOverrides: Map<String, String> = providers
    .fileContents(rootProject.layout.projectDirectory.file("owner-overrides.properties"))
    .asText
    .map { text ->
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
    }
    .getOrElse(emptyMap())

// Release signing comes from these environment variables only, and only when all four are set;
// with any of them absent assembleRelease still builds, unsigned. Nothing about the key is kept
// in a file of this tree.
val releaseKeystorePath: String? = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val releaseKeystorePassword: String? = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias: String? = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val releaseKeyPassword: String? = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
val releaseSigningAvailable: Boolean =
    listOf(releaseKeystorePath, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }

fun buildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.w6tech.infinassistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.w6tech.infinassistant"
        minSdk = 31
        targetSdk = 36
        versionCode = 144
        versionName = "2.1.127"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Comma-separated home subnets; empty unless owner-overrides.properties sets home.subnets.
        buildConfigField("String", "HOME_SUBNETS", buildConfigString(ownerOverrides["home.subnets"].orEmpty()))
    }

    signingConfigs {
        if (releaseSigningAvailable) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // The SMB share for debug bundle uploads; empty unless debug.smb.share is set.
            buildConfigField("String", "DEBUG_SMB_SHARE", buildConfigString(ownerOverrides["debug.smb.share"].orEmpty()))
        }
        release {
            // Release builds never carry a share address.
            buildConfigField("String", "DEBUG_SMB_SHARE", "\"\"")
            // REQ-042 — enable R8 + resource shrinking. ProGuard rules live in
            // proguard-rules.pro. Mapping artifact is uploaded to
            // artifacts/mapping/mapping.txt by CI; release-smoke result is
            // captured in artifacts/release-smoke.log per the Phase D plan.
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseSigningAvailable) signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
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

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core-common"))
    implementation(project(":core-domain"))
    implementation(project(":core-ha"))
    implementation(project(":core-ble"))
    implementation(project(":core-data"))
    implementation(project(":core-ui"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)

    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.nordic.dfu)

    implementation(libs.kotlinx.serialization.json)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // SMB debug bundle upload. These are debugImplementation, not
    // implementation: the uploader lives in src/debug/ and a no-op stand-in
    // (a different class) lives in src/release/, so release builds ship
    // neither the SMB client nor the Bouncy Castle provider.
    debugImplementation(libs.smbj)
    debugImplementation(libs.slf4j.nop)
    debugImplementation(libs.bouncycastle)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
