import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM: every wire format, state machine and reducer, no Android.
// Transports (sockets, TLS, HTTP) are interfaces that :app implements.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.wire)
    // The device simulator (src/testFixtures) is shared with :app's tests.
    `java-test-fixtures`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Protos live in src/main/proto (Wire's default root); the plugin adds wire-runtime as `api`.
wire {
    kotlin {}
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.bouncycastle.pkix)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnit()
}
