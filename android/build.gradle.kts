// AGP 9 compiles Kotlin itself (built-in Kotlin). The Compose compiler
// plugin must match the Kotlin Gradle plugin in use, so both are pinned to
// the same Kotlin version here.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
