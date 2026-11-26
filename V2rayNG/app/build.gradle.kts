plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.jaredsburrows.license")
}

/**
 * The commit this build was made from, so that a build can tell which build it is.
 * Two builds can carry the same version and still be different — a tester's build
 * and the published one both say 1.0.1 — and without this there is no way for the
 * phone to know whether the release it is looking at is the one it is running.
 */
fun commitOf(dir: File): String = runCatching {
    val process = ProcessBuilder("git", "rev-parse", "HEAD")
        .directory(dir)
        .redirectErrorStream(false)
        .start()
    val out = process.inputStream.bufferedReader().readText().trim()
    if (process.waitFor() == 0) out else ""
}.getOrDefault("")

fun branchOf(dir: File): String = runCatching {
    val process = ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
        .directory(dir)
        .redirectErrorStream(false)
        .start()
    val out = process.inputStream.bufferedReader().readText().trim()
    if (process.waitFor() == 0 && out != "HEAD") out else "master"
}.getOrDefault("master")

val buildCommit = commitOf(rootDir).ifEmpty { commitOf(rootDir.parentFile) }
val buildBranch = branchOf(rootDir).ifEmpty { branchOf(rootDir.parentFile) }

android {
    namespace = "com.v2ray.ang"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mobix.vpn"
        minSdk = 24
        targetSdk = 29
        // 4 was a mistake to freeze: a phone only updates an installed app when
        // the new file has a higher number, and every build this project shipped
        // carried the same one, so an installed copy could never be replaced. The
        // number now comes from the build itself (-Prx.versionCode=<run number>,
        // passed by the workflow) so that each build is newer than the last. CI
        // then refuses to ship a file whose number is not above 4000004, which is
        // the highest number any earlier build ever had — no phone can be stuck on
        // a version that is newer than everything after it.
        versionCode = (project.findProperty("rx.versionCode") as String?)?.toIntOrNull() ?: 5
        // A tester who checks the phone's Settings screen can tell two builds apart
        // from here on, which is how the version question is settled without a log.
        versionName = (project.findProperty("rx.versionName") as String?) ?: "1.2.1"

        buildConfigField("String", "BUILD_COMMIT", "\"$buildCommit\"")
        buildConfigField("String", "BUILD_BRANCH", "\"$buildBranch\"")

        val abiFilterList = (properties["ABI_FILTERS"] as? String)?.split(';')
        splits {
            abi {
                isEnable = true
                reset()
                if (!abiFilterList.isNullOrEmpty()) {
                    include(*abiFilterList.toTypedArray())
                } else {
                    include(
                        "arm64-v8a",
                        "armeabi-v7a",
                        "x86_64",
                        "x86"
                    )
                }
                isUniversalApk = abiFilterList.isNullOrEmpty()
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("rxRelease") {
            // Supplied by CI (or by a maintainer) with -Prx.storeFile=... and friends. Without it the
            // release build simply stays unsigned instead of failing on a missing keystore.
            val storePath = (project.findProperty("rx.storeFile") as String?)?.takeIf { it.isNotBlank() }
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = project.findProperty("rx.storePassword") as String?
                keyAlias = project.findProperty("rx.keyAlias") as String?
                keyPassword = project.findProperty("rx.keyPassword") as String?
            }
            // A JAR signature as well as v2/v3: installers on older Android rejects v2-only packages
            // with "package appears to be invalid", which is exactly the report we are chasing.
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            // A runner's default debug key is new on every CI run, so a debug APK could never be
            // installed over an earlier build. With the release key supplied it shares the release
            // signature; without it (local builds) the default debug key still applies.
            signingConfigs.getByName("rxRelease").takeIf { it.storeFile != null }
                ?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("rxRelease").takeIf { it.storeFile != null }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        jniLibs {
            // Compressed, extract-at-install native libraries (extractNativeLibs="true").
            // Both AetherEngine (libaether.so) and RootProxyManager (libhevsockstun.so) execute
            // binaries directly from context.applicationInfo.nativeLibraryDir, which stays empty
            // when useLegacyPackaging = false. Uncompressed packaging with 4 KB NDK r26 binaries
            // and ELF executables in lib/ is also rejected by device installers on both fresh
            // installs and updates ("the package appears to be invalid").
            useLegacyPackaging = true
        }
    }

    lint {
        abortOnError = false
    }

    flavorDimensions.add("distribution")
    productFlavors {
        create("fdroid") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION", "\"F-Droid\"")
        }
        create("playstore") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION", "\"Play Store\"")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    applicationVariants.all {
        val variant = this
        val isFdroid = variant.productFlavors.any { it.name == "fdroid" }
        if (isFdroid) {
            val versionCodes =
                mapOf(
                    "armeabi-v7a" to 2, "arm64-v8a" to 1, "x86" to 4, "x86_64" to 3, "universal" to 0
                )

            variant.outputs
                .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
                .forEach { output ->
                    val abi = output.getFilter("ABI") ?: "universal"
                    output.outputFileName = "RXVPN_${variant.versionName}-fdroid_${abi}.apk"
                    if (versionCodes.containsKey(abi)) {
                        output.versionCodeOverride =
                            (100 * variant.versionCode + versionCodes[abi]!!).plus(5000000)
                    } else {
                        return@forEach
                    }
                }
        } else {
            val versionCodes =
                mapOf("armeabi-v7a" to 4, "arm64-v8a" to 4, "x86" to 4, "x86_64" to 4, "universal" to 4)

            variant.outputs
                .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
                .forEach { output ->
                    val abi = if (output.getFilter("ABI") != null)
                        output.getFilter("ABI")
                    else
                        "universal"

                    output.outputFileName = "RXVPN_${variant.versionName}_${abi}.apk"
                    if (versionCodes.containsKey(abi)) {
                        output.versionCodeOverride =
                            (1000000 * versionCodes[abi]!!).plus(variant.versionCode)
                    } else {
                        return@forEach
                    }
                }
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    androidResources {
        generateLocaleConfig = true
        localeFilters += listOf(
            "en",
            "zh-rCN",
            "zh-rTW",
            "vi",
            "ru",
            "fa",
            "ar",
            "az",
            "bn",
            "bqi-rIR"
        )
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
            // Treat Jupiter discovery warnings as failures: JUnit 4 failed on an invalid @Test
            // method, such as one that returns a value, while Jupiter only warns and skips it.
            // The key is @API(status = EXPERIMENTAL) in JUnit 6; after a JUnit upgrade, check
            // that a temporary `@Test fun probe() = 1` still fails the unit test task.
            it.systemProperty("junit.platform.discovery.issue.severity.critical", "WARNING")
        }
    }

}

dependencies {
    // Core Libraries
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // AndroidX Core Libraries
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    // Compose Libraries
    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coil.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // Data and Storage Libraries
    implementation(libs.mmkv.static)
    implementation(libs.gson)
    implementation(libs.okhttp)

    // Reactive and Utility Libraries
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // QR Code: CameraX + ZXing
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.compose)
    implementation(libs.core) // zxing core

    // AndroidX Lifecycle and Architecture Components
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.ktx)

    // Background Task Libraries
    implementation(libs.work.runtime.ktx)
    implementation(libs.work.multiprocess)

    // Reorderable list
    implementation(libs.reorderable)

    // Testing Libraries
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
