import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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
    compileSdk = 36

    defaultConfig {
        applicationId = appId
        minSdk = 29
        targetSdk = 36
        // CI passes a monotonically increasing code (the run number) so each release installs over the last.
        versionCode = providers.environmentVariable("TUTTI_VERSION_CODE").orElse("1").get().toInt()
        versionName = providers.environmentVariable("TUTTI_VERSION_NAME").orElse("0.0.0-dev").get()
        resValue("string", "app_name", appLabel)
    }

    signingConfigs {
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
        }
        release {
            // R8 stays off until a first signed release has run in CI; turn it on with keep rules then.
            isMinifyEnabled = false
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
    }

    packaging {
        resources {
            // BouncyCastle jars each ship this file; AGP refuses duplicates.
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":protocol"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.browser)
    implementation(libs.okhttp)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
