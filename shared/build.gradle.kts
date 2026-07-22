plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
}

kotlin {
    androidTarget()

    // iOS targets - add for Mac build
    iosArm64 {
        binaries.framework {
            baseName = "CarANC"
        }
    }
    iosSimulatorArm64 {
        binaries.framework {
            baseName = "CarANC"
        }
    }

    sourceSets {
        iosMain.dependencies {
            // Add iOS-specific deps here if needed later (e.g. for coroutines native or platform audio)
        }
        commonMain.dependencies {
            // P1 #4: coroutines version now from libs.versions.toml (unified; was direct "1.8.1")
            implementation(libs.kotlinx.coroutines.core)
            // androidx.annotation for @Keep annotations on DSP core (see commonMain files: MultiBandANCProcessor.kt etc.)
            // Ensures @Keep survives even if proguard rules are bypassed; added for P0 ProGuard hardening
            implementation(libs.androidx.annotation)
        }

        androidMain.dependencies {
            // P1 #4 unify: all direct version strings moved to gradle/libs.versions.toml catalog refs
            // (core-ktx 1.13.1, lifecycles 2.8.6, car.app 1.4.0, play-services 21.3.0)
            // Behavior kept identical via version refs in [versions] + [libraries]
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.service)
            implementation(libs.androidx.lifecycle.runtime.ktx)
            implementation(libs.androidx.lifecycle.viewmodel.ktx)
            implementation(libs.androidx.car.app)
            implementation(libs.play.services.location)
        }

        // Start test sourceSet setup for core DSP unit tests (P1).
        // Using androidUnitTest for JVM-local unit tests of pure commonMain DSP (FftUtils, Spectrum, AudioSignalUtils, estimators).
        // Includes kotlin.test (JUnit adapter) + coroutines-test for potential async in tests.
        // Tests live under shared/src/androidUnitTest/kotlin/... ; run via :shared:testDebugUnitTest or compileDebugKotlin.
        val androidUnitTest by getting {
            dependencies {
                implementation(kotlin("test"))
                // P1 #4: coroutines-test now catalog ref (same version 1.8.1 as core from toml)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

android {
    namespace = "com.example.caranc.shared"
    compileSdk = 34
    defaultConfig {
        minSdk = 26
        // Export consumer proguard rules so that when app (or other consumers) builds release,
        // the shared DSP classes are protected from stripping. The rules file (consumer-rules.pro)
        // should contain -keep for com.example.caranc.shared.* public API.
        // Combined with app/proguard-rules.pro aggressive keeps + @Keep annotations.
        consumerProguardFiles("consumer-rules.pro")
    }
    // JVM unit tests call android.util.Log / Audio* without full framework — return defaults
    // instead of "Method e in android.util.Log not mocked" (breaks AudioEngineTest).
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // RELEASE BUILD NOTE (P0 ProGuard):
    // For release (minify+shrink), the DSP core (MultiBandANCProcessor + subpackages latency/model/signal + facades)
    // MUST be kept. See app/proguard-rules.pro for full package keep + consumer from shared.
    // Test with: ./gradlew assembleRelease (after proguard rules + annotations applied).
    // Cycle3 P2 DONE: explicit verifyReleaseProguard (and verifyReleaseBuildTiming) task added in app/build.gradle.kts .
    // See :app:verifyReleaseProguard - runs assembleRelease + inspects mapping for MultiBandANCProcessor kept + no DSP stripping.
    // Run: ./gradlew :app:verifyReleaseProguard (relative); CI: include for proguard regression guard.
    // Uses shared's consumerProguardFiles + app rules + now-enabled signing for release builds. Timing extension included.
    // Old TODO resolved here. (P0 ProGuard hardening complete.)

    // CYCLE3_EXTRA: Native low-freq path prototype build notes (CMake / NDK).
    // - New: NativeLowBandProcessor (expect/actual) + JNI bridge.
    // - Prototype C++ sources: shared/src/androidMain/cpp/CMakeLists.txt + NativeLowBandLms.cpp (detailed LMS impl comments inside).
    // - JNI target lib name: "caranc_lowband" (System.loadLibrary in android actual).
    // - To ENABLE native build (currently left COMMENTED to preserve pure-kotlin relative compile for debug + cycle checks):
    //     Inside the `android {` block, add/uncomment:
    //
    //     externalNativeBuild {
    //         cmake {
    //             path = "src/androidMain/cpp/CMakeLists.txt"
    //             version = "3.22.1"
    //         }
    //     }
    //     ndk {
    //         abiFilters += listOf("armeabi-v7a", "arm64-v8a")
    //     }
    //     defaultConfig {
    //         externalNativeBuild {
    //             cmake {
    //                 arguments += listOf("-DANDROID_STL=c++_shared")
    //                 // cppFlags for opt: "-std=c++17 -O3 -ffast-math -DUSE_NEON=1"
    //             }
    //         }
    //     }
    //
    // - Then run relative: .\gradlew.bat :shared:assembleRelease  (or debug) -- will invoke cmake/ndk-build for .so
    // - The .so ends up in intermediates/merged_jni_libs + packaged in AAR/APK.
    // - Proguard: broad -keep com.example.caranc.shared.** already covers Native* + native methods kept via -keepclasseswithmembernames (added in app/proguard).
    // - For cycle3_verify_release_build_timing: enabling native adds NDK cmake time to assembleRelease (visible in "BUILD SUCCESSFUL in XXs").
    //   Re-run :app:verifyReleaseBuildTiming after enabling to compare release build duration delta (native toolchain overhead).
    // - iOS note: no cmake here; use separate XCFramework or kotlin native cinterop for future Accelerate port.
    // - Safety: native is opt-in in code (isNativeAvailable() false until lib present + explicit switch in MultiBandANCProcessor).
    // - TODO follow-up: add to shared/consumer-rules.pro explicit native method keep if needed beyond broad rule.
    // This keeps kotlin compile (compileDebugKotlinAndroid) unaffected until explicitly wired.

    // P4: Prepared for native low band build (CYCLE3_EXTRA). 
    // Uncomment the block below when NDK available to build the .so (see comments for details).
    // externalNativeBuild { ... } etc. (kept commented to not break pure-kotlin builds in this env).
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}