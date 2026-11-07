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
        // CI derives both from the tag (.github/workflows/android.yml; never change the formula).
        // F-Droid's build server sets no environment, so its recipe passes the same two values as
        // Gradle properties (docs/fdroid.md). Nothing here reads the clock or git: the same commit
        // and the same two values give the same APK.
        versionCode = providers.environmentVariable("TUTTI_VERSION_CODE")
            .orElse(providers.gradleProperty("tutti.versionCode")).orElse("1").get().toInt()
        versionName = providers.environmentVariable("TUTTI_VERSION_NAME")
            .orElse(providers.gradleProperty("tutti.versionName")).orElse("0.0.0-dev").get()
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
            // A dev pre-release passes -Ptutti.devRelease: the same id and signature, so it still
            // installs over the last with every pairing kept, but not debuggable, so ART compiles
            // it instead of running every first frame (the Quick Settings overlay's above all)
            // through the debuggable runtime.
            isDebuggable = !providers.gradleProperty("tutti.devRelease").isPresent
        }
        release {
            // R8 shrinks and optimises, names stay readable (docs/architecture.md, "Release build: R8").
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // Two builds of the same app (docs/updates.md). `github`: the APKs on GitHub Releases, with the
    // in-app updater (src/github: REQUEST_INSTALL_PACKAGES, PackageInstaller, GitHub's API). `fdroid`:
    // no updater at all, F-Droid updates it. Same applicationId and versionCode in both; the
    // signature is what tells them apart. checkDistribution<Variant> guards the merged manifests.
    flavorDimensions += "distribution"
    productFlavors {
        create("github") { dimension = "distribution" }
        create("fdroid") { dimension = "distribution" }
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric's native graphics (the render tests) outgrow the 512 MB default.
        unitTests.all { it.maxHeapSize = "2g" }
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

// The fdroid build carries no updater: its merged manifest must not ask to install packages or
// declare the updater's receiver. The github build must still carry both, or every install
// stops updating. Read before each variant assembles or lints.
androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val check = tasks.register<DistributionCheck>("checkDistribution$name") {
            manifest.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST))
            updater.set(variant.flavorName == "github")
        }
        afterEvaluate {
            tasks.named("assemble$name") { dependsOn(check) }
            tasks.findByName("lint$name")?.dependsOn(check)
        }
    }
}

abstract class DistributionCheck : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    /** The github flavour: the updater must be there. The fdroid flavour: it must not. */
    @get:Input
    abstract val updater: Property<Boolean>

    @TaskAction
    fun check() {
        val text = manifest.get().asFile.readText()
        val marks = UPDATER_MARKS.filter { it in text }
        if (updater.get()) {
            check(marks.size == UPDATER_MARKS.size) { "The github build lost its updater: missing ${UPDATER_MARKS - marks.toSet()} (${manifest.get().asFile})" }
        } else {
            check(marks.isEmpty()) { "The fdroid build must carry no updater, found $marks (${manifest.get().asFile})" }
        }
    }

    private companion object {
        val UPDATER_MARKS = listOf("android.permission.REQUEST_INSTALL_PACKAGES", "platform.update.UpdateInstallReceiver", "platform.update.UpdatedReceiver")
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
    testImplementation(libs.robolectric)
}
