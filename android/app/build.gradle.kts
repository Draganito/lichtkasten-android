import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------- Rust core
// TIFF/RAW decoding and EXIF/IPTC/XMP come from a Rust cdylib
// (../rust/lichtkasten-mobile, which uses lichtkasten-core from the desktop
// repository checked out next to this one). Gradle drives cargo-ndk and the
// UniFFI Kotlin generator so that a plain `./gradlew assembleDebug` builds
// everything.

val rustDir = rootProject.file("../rust/lichtkasten-mobile")
val rustProfile = (project.findProperty("lichtkasten.rustProfile") as String?) ?: "release"
val rustAbi = "arm64-v8a"
val rustJniLibs = layout.buildDirectory.dir("rust/jniLibs")
val uniffiOut = layout.buildDirectory.dir("generated/uniffi/kotlin")
val androidPlatform = libs.versions.minSdk.get()

// One version number: rust/lichtkasten-mobile/Cargo.toml.
val appVersion: String = rustDir.resolve("Cargo.toml").readLines()
    .map { it.trim() }
    .firstOrNull { it.startsWith("version = \"") }
    ?.removePrefix("version = \"")?.removeSuffix("\"")
    ?: error("version missing in ${rustDir.resolve("Cargo.toml")}")
val appVersionCode: Int = appVersion.split(".").let { (major, minor, patch) ->
    major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt()
}

fun ndkHome(): String {
    System.getenv("ANDROID_NDK_HOME")?.let { return it }
    val sdk = System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: run {
            val lp = rootProject.file("local.properties")
            if (lp.exists()) {
                Properties().also { p -> lp.inputStream().use { p.load(it) } }.getProperty("sdk.dir")
            } else null
        }
        ?: "${System.getProperty("user.home")}/.local/opt/android-sdk"
    val ndks = file("$sdk/ndk").listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
    return ndks.lastOrNull()?.absolutePath ?: error("No NDK found under $sdk/ndk; install one with sdkmanager")
}

val cargoBuild = tasks.register<Exec>("cargoBuild") {
    group = "rust"
    description = "cargo-ndk build of liblichtkasten_mobile.so ($rustAbi, $rustProfile)"
    workingDir = rustDir
    val ndk = ndkHome()
    inputs.dir(rustDir.resolve("src"))
    inputs.file(rustDir.resolve("Cargo.toml"))
    inputs.file(rustDir.resolve("Cargo.lock"))
    inputs.file(rustDir.resolve("uniffi.toml"))
    // The core is a path dependency outside this repository; cargo knows
    // what is stale, Gradle does not.
    outputs.upToDateWhen { false }
    inputs.property("profile", rustProfile)
    inputs.property("platform", androidPlatform)
    inputs.property("ndk", ndk)
    outputs.dir(rustJniLibs)
    environment("ANDROID_NDK_HOME", ndk)
    val args = mutableListOf(
        "cargo", "ndk", "-t", rustAbi, "--platform", androidPlatform,
        "-o", rustJniLibs.get().asFile.absolutePath, "build", "--lib",
    )
    if (rustProfile == "release") args += "--release"
    commandLine(args)
}

val generateUniffiBindings = tasks.register<Exec>("generateUniffiBindings") {
    group = "rust"
    description = "UniFFI Kotlin bindings from the compiled library (library mode)"
    dependsOn(cargoBuild)
    workingDir = rustDir
    val lib = rustJniLibs.map { it.file("$rustAbi/liblichtkasten_mobile.so") }
    inputs.file(lib)
    inputs.file(rustDir.resolve("uniffi.toml"))
    outputs.dir(uniffiOut)
    commandLine(
        "cargo", "run", "--quiet", "--features", "bindgen", "--bin", "uniffi-bindgen", "--",
        "generate", "--library", lib.get().asFile.absolutePath,
        "--language", "kotlin", "--no-format",
        "--out-dir", uniffiOut.get().asFile.absolutePath,
    )
}

// ------------------------------------------------------------------ Android

android {
    namespace = "ch.bojovic.lichtkasten"
    compileSdk = libs.versions.compileSdk.get().toInt()
    compileSdkMinor = libs.versions.compileSdkMinor.get().toInt()
    // Same NDK cargo-ndk links against, so AGP can strip the Rust library.
    ndkVersion = file(ndkHome()).name

    defaultConfig {
        applicationId = "ch.bojovic.lichtkasten"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = appVersionCode
        versionName = appVersion
        ndk { abiFilters += rustAbi }
    }

    androidResources {
        localeFilters += listOf("en", "de")
    }

    // A release key, when android/keystore.properties exists (git-ignored):
    //   storeFile=/absolute/path/to/lichtkasten.jks
    //   storePassword=...
    //   keyAlias=...
    //   keyPassword=...
    // Without it the release APK is signed with the debug key.
    val releaseKey = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
        Properties().also { p -> f.inputStream().use { p.load(it) } }
    }
    signingConfigs {
        if (releaseKey != null) {
            create("release") {
                storeFile = file(releaseKey.getProperty("storeFile"))
                storePassword = releaseKey.getProperty("storePassword")
                keyAlias = releaseKey.getProperty("keyAlias")
                keyPassword = releaseKey.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (releaseKey != null) "release" else "debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            kotlin.directories.add(uniffiOut.get().asFile.absolutePath)
            jniLibs.directories.add(rustJniLibs.get().asFile.absolutePath)
        }
    }

    packaging {
        jniLibs {
            // JNA ships its own natives per ABI; keep them uncompressed for JNA's loader.
            useLegacyPackaging = false
        }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
    }
}

tasks.named("preBuild") {
    dependsOn(generateUniffiBindings)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation("${libs.jna.get()}@aar")
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
}
