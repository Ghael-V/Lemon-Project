// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

import android.annotation.SuppressLint
import kotlin.collections.setOf
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType
import com.github.triplet.gradle.androidpublisher.ReleaseStatus

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-parcelize")
    kotlin("plugin.serialization") version "1.9.24"
    id("androidx.navigation.safeargs.kotlin")
    id("org.jlleitschuh.gradle.ktlint") version "11.4.0"
    id("com.github.triplet.play") version "3.8.6"
}

/**
 * Use the number of seconds/10 since Jan 1 2016 as the versionCode.
 * This lets us upload a new build at most every 10 seconds for the
 * next 680 years.
 */
val autoVersion = (((System.currentTimeMillis() / 1000) - 1451606400) / 10).toInt()

@Suppress("UnstableApiUsage")
android {
    namespace = "dev.ironlemon.ironlemon_emulator"

    compileSdkVersion = "android-34"
    ndkVersion = "26.1.10909125"

    buildFeatures {
        viewBinding = true
        compose = true
    }

    // Release builds run lint's "vital" checks, whose Instantiatable check can't see the app's
    // class hierarchy here and flags every Activity and View as not extending its base class.
    lint {
        checkReleaseBuilds = false
    }

    composeOptions {
        // Compose compiler paired with Kotlin 1.9.24.
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        // This is necessary for libadrenotools custom driver loading
        jniLibs.useLegacyPackaging = true
    }

    androidResources {
        generateLocaleConfig = true
    }

    defaultConfig {
        // Iron Lemon's own id: it installs next to Lemon instead of over it (and can import
        // Lemon's data through the migration screen, which finds it like any other emulator).
        applicationId = "dev.ironlemon.ironlemon_emulator"
        minSdk = 30
        targetSdk = 34
        // Iron Lemon's own numbering since it became an app of its own (tag v0.1.0 and on).
        versionName = System.getenv("GIT_TAG_NAME")?.removePrefix("v") ?: "0.1.0"

        // Seconds/10 since 2016: every build is newer than the one before.
        versionCode = autoVersion

        ndk {
            @SuppressLint("ChromeOsAbiSupport")
            abiFilters += listOf("arm64-v8a")
        }

        buildConfigField("String", "GIT_HASH", "\"${getGitHash()}\"")
        buildConfigField("String", "BRANCH", "\"${getBranch()}\"")
    }

    val keystoreFile = System.getenv("ANDROID_KEYSTORE_FILE")
    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASS")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEYSTORE_PASS")
            }
        }
        create("default") {
            storeFile = file("$projectDir/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    // Define build types, which are orthogonal to product flavors.
    buildTypes {

        // Signed by release key, allowing for upload to Play Store.
        release {
            signingConfig = if (keystoreFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("default")
            }

            resValue("string", "app_name_suffixed", "Iron Lemon")
            isMinifyEnabled = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )
        }

        // builds a release build that doesn't need signing
        // Attaches 'debug' suffix to version and package name, allowing installation alongside the release build.
        register("relWithDebInfo") {
            isDefault = true
            resValue("string", "app_name_suffixed", "Iron Lemon Debug Release")
            signingConfig = signingConfigs.getByName("default")
            isMinifyEnabled = true
            isDebuggable = true
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )
            versionNameSuffix = "-relWithDebInfo"
            applicationIdSuffix = ".relWithDebInfo"
            isJniDebuggable = true
        }

        // Signed by debug key disallowing distribution on Play Store.
        // Attaches 'debug' suffix to version and package name, allowing installation alongside the release build.
        debug {
            signingConfig = signingConfigs.getByName("default")
            resValue("string", "app_name_suffixed", "Iron Lemon Debug")
            isDebuggable = true
            isJniDebuggable = true
            versionNameSuffix = "-debug"
            applicationIdSuffix = ".debug"
        }
    }

    flavorDimensions.add("version")
    productFlavors {
        create("mainline") {
            isDefault = true
            dimension = "version"
            buildConfigField("Boolean", "PREMIUM", "false")
            // The release asset this build updates from (AppUpdater).
            buildConfigField("String", "UPDATE_APK_SUFFIX", "\"-standard.apk\"")
        }

        create("ea") {
            dimension = "version"
            buildConfigField("Boolean", "PREMIUM", "true")
            buildConfigField("String", "UPDATE_APK_SUFFIX", "\"-ea.apk\"")
            applicationIdSuffix = ".ea"
        }

        // Iron Lemon Spoofed: Iron Lemon, nothing else changed, under an AnTuTu package name,
        // which some phone makers (MediaTek devices above all) give their full performance
        // profile. Not Lemon Spoofed's com.antutu.ABenchMark, so the two can be installed together.
        create("spoofed") {
            dimension = "version"
            buildConfigField("Boolean", "PREMIUM", "false")
            buildConfigField("String", "UPDATE_APK_SUFFIX", "\"-spoofed.apk\"")
            applicationId = "com.antutu.benchmark.full"
        }
    }

    // Iron Lemon: -PprebuiltNativeDir=<dir> packages native libraries built outside Gradle
    // (<dir>/arm64-v8a/*.so, e.g. from a Linux/WSL CMake build) instead of running CMake here.
    val prebuiltNativeDir = project.findProperty("prebuiltNativeDir") as String?
    if (prebuiltNativeDir != null) {
        sourceSets.getByName("main").jniLibs.srcDir(prebuiltNativeDir)
        defaultConfig.ndk.abiFilters += "arm64-v8a"
    } else {
    externalNativeBuild {
        cmake {
            version = "3.22.1"
            path = file("../../../CMakeLists.txt")
        }
    }

    defaultConfig {
        externalNativeBuild {
            cmake {
                arguments(
                    "-DENABLE_QT=0", // Don't use QT
                    "-DENABLE_SDL2=0", // Don't use SDL
                    "-DENABLE_WEB_SERVICE=0", // Don't use telemetry
                    "-DBUNDLE_SPEEX=ON",
                    "-DANDROID_ARM_NEON=true", // cryptopp requires Neon to work
                    "-DYUZU_USE_BUNDLED_VCPKG=ON",
                    "-DYUZU_USE_BUNDLED_FFMPEG=ON",
                    "-DYUZU_ENABLE_LTO=ON",
                    "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON"
                )

                abiFilters("arm64-v8a", "x86_64")
            }
        }
    }
    }
}

// The label comes from the build type's resValue, which outranks a flavor's: the Spoofed variants
// get theirs here ("Iron Lemon Spoofed", with the build type's "Debug" endings).
androidComponents {
    onVariants { variant ->
        if (variant.flavorName == "spoofed") {
            val label = when (variant.buildType) {
                "debug" -> "Iron Lemon Spoofed Debug"
                "relWithDebInfo" -> "Iron Lemon Spoofed Debug Release"
                else -> "Iron Lemon Spoofed"
            }
            variant.resValues.put(
                variant.makeResValueKey("string", "app_name_suffixed"),
                com.android.build.api.variant.ResValue(label, null)
            )
        }
    }
}

tasks.create<Delete>("ktlintReset") {
    delete(File(buildDir.path + File.separator + "intermediates/ktLint"))
}

val showFormatHelp = {
    logger.lifecycle(
        "If this check fails, please try running \"gradlew ktlintFormat\" for automatic " +
            "codestyle fixes"
    )
}
tasks.getByPath("ktlintKotlinScriptCheck").doFirst { showFormatHelp.invoke() }
tasks.getByPath("ktlintMainSourceSetCheck").doFirst { showFormatHelp.invoke() }
tasks.getByPath("loadKtlintReporters").dependsOn("ktlintReset")

ktlint {
    version.set("0.47.1")
    android.set(true)
    ignoreFailures.set(false)
    disabledRules.set(
        setOf(
            "no-wildcard-imports",
            "package-name",
            "import-ordering"
        )
    )
    reporters {
        reporter(ReporterType.CHECKSTYLE)
    }
}

play {
    val keyPath = System.getenv("SERVICE_ACCOUNT_KEY_PATH")
    if (keyPath != null) {
        serviceAccountCredentials.set(File(keyPath))
    }
    track.set(System.getenv("STORE_TRACK") ?: "internal")
    releaseStatus.set(ReleaseStatus.COMPLETED)
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.recyclerview:recyclerview:1.3.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.fragment:fragment-ktx:1.6.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("io.coil-kt:coil:2.2.2")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.window:window:1.2.0-beta03")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.4")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.4")
    implementation("info.debatty:java-string-similarity:2.0.0")
    // Lemon Online identity: Argon2id.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.0")
    implementation("androidx.compose.ui:ui:1.7.8")
    implementation("androidx.compose.foundation:foundation:1.7.8")
    implementation("androidx.compose.ui:ui-graphics-android:1.7.8")
    implementation("androidx.compose.ui:ui-text-android:1.7.8")
}

fun runGitCommand(command: List<String>): String {
    return try {
        ProcessBuilder(command)
            .directory(project.rootDir)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start().inputStream.bufferedReader().use { it.readText() }
            .trim()
    } catch (e: Exception) {
        logger.error("Cannot find git")
        ""
    }
}

fun getGitVersion(): String {
    val gitVersion = runGitCommand(
        listOf(
            "git",
            "describe",
            "--always",
            "--long"
        )
    ).replace(Regex("(-0)?-[^-]+$"), "")
    val versionName = if (System.getenv("GITHUB_ACTIONS") != null) {
        System.getenv("GIT_TAG_NAME") ?: gitVersion
    } else {
        gitVersion
    }
    return versionName.ifEmpty { "0.0" }
}

fun getGitHash(): String =
    runGitCommand(listOf("git", "rev-parse", "--short", "HEAD")).ifEmpty { "dummy-hash" }

fun getBranch(): String =
    runGitCommand(listOf("git", "rev-parse", "--abbrev-ref", "HEAD")).ifEmpty { "dummy-hash" }
