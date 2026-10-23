plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.wire) apply false
}

// The device simulators bind real loopback ports and release them when a simulated device sleeps;
// with the two modules' tests running at once, the other module's simulator could take a released
// port and answer in its place (a "pinned TV reached" or "sleeping TV connected" that never was).
// Test tasks therefore run one at a time across modules; compilation stays parallel.
abstract class SerialTests : BuildService<BuildServiceParameters.None>

val serialTests = gradle.sharedServices.registerIfAbsent("serialTests", SerialTests::class) {
    maxParallelUsages.set(1)
}

subprojects {
    tasks.withType<Test>().configureEach { usesService(serialTests) }
}
