// AGP 9 compiles Kotlin itself (built-in Kotlin); this pins the Kotlin version it uses
// so it matches the Compose and serialization compiler plugins below.
buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
