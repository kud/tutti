import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// The provisional product name lives in gradle.properties only.
val appLabel: String = providers.gradleProperty("app.label").get()
val appId: String = providers.gradleProperty("app.applicationId").get()

// Release signing comes from CI (see README, "Release secrets"). Locally, and on
// any build without the variables, no release signing config exists, and the
// CI release job refuses to publish an unsigned APK.
val releaseKeystore: String? = providers.environmentVariable("TUTTI_KEYSTORE_PATH").orNull

android {
    namespace = "io.kud.tutti"
    compileSdk = 37

    defaultConfig {
        applicationId = appId
        minSdk = 29
        targetSdk = 37
        // CI passes a monotonically increasing code (the run number) so each release installs over the last.
        versionCode = providers.environmentVariable("TUTTI_VERSION_CODE").orElse("1").get().toInt()
        versionName = providers.environmentVariable("TUTTI_VERSION_NAME").orElse("0.0.0-dev").get()
        resValue("string", "app_name", appLabel)
    }

    signingConfigs {
        // Public by design (password "android"): every CI debug build carries the same
        // signature, so a newer debug APK installs over the last. Never a release key.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // R8 shrinks and optimises, names stay readable (docs/architecture.md, "Release build: R8").
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        resValues = true
        buildConfig = true
    }

    packaging {
        resources {
            // BouncyCastle jars each ship this file; AGP refuses duplicates.
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            // BC 1.86's every-jar packaging (root build.gradle) bundles LICENSE.md into
            // bcprov, bcutil and bcpkix alike, which mergeJavaRes then refuses to merge.
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // BouncyCastle's Picnic (post-quantum signature) tables, 1.2 MB: only its picnic
            // classes read them, and tutti only builds an RSA certificate (ClientCertificates).
            excludes += "/org/bouncycastle/pqc/crypto/picnic/**"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

composeCompiler {
    // The group mapping only decodes Compose stack traces in obfuscated builds, and release
    // keeps its names (proguard-rules.pro). Its tool is not in the offline Gradle cache either.
    includeComposeMappingFile.set(false)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":protocol"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.browser)
    implementation(libs.okhttp)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":protocol")))
    testImplementation(libs.kotlinx.coroutines.test)
}
