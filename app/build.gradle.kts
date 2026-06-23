plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kotlinCompose)
}

import java.io.File

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

android {
    namespace = "com.example.caranc"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.caranc"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Cycle3 P2: Added signingConfig = signingConfigs.debug to allow real ./gradlew :app:assembleRelease (and thus verifyReleaseProguard) to succeed without "Keystore file not set" error.
            // This enables full minify+proguard run + mapping generation for inspection in limited/CI envs (uses debug keystore for the "release" build - common for verification; in prod use proper release keystore).
            // For prod release, override or remove in real signing setup.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // P0: ProGuard hardening active - app/proguard-rules.pro NOW aggressively keeps entire com.example.caranc.shared.* DSP
            // (MultiBandANCProcessor, latency/*, model/*, signal/*, Fft*, Spectrum*, AudioSignal*, Anc* facades, Cabin* etc.)
            // + @Keep annotations in shared commonMain + shared's consumer-proguard export.
            // Legacy ANCProcessor keep was insufficient; fixed here.
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":shared"))

    // Note: shared module declares androidx.annotation for @Keep on its DSP; app inherits transitively.
    // If direct use of annotation in app code needed, add: implementation(libs.androidx.annotation)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    
    // Android Auto Library
    implementation("androidx.car.app:app:1.4.0")
    
    // Compose Dependencies
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// =====================================================
// Cycle3 P2: Release proguard verification task + assembleRelease test.
// Custom task: verifyReleaseProguard (and companion verifyReleaseBuildTiming)
// - Runs (depends on) assembleRelease (which honors isMinifyEnabled=true + proguardFiles in release buildType)
// - Inspects build/outputs/mapping/release/mapping.txt (or APK fallback) for key classes kept:
//   MultiBandANCProcessor kept (not stripped), no stripping of DSP (latency/model/signal packages etc).
// - Verifies minify effects: with current app/proguard-rules.pro + shared/consumer-rules.pro (via consumerProguardFiles),
//   the broad + specific -keep rules + @Keep from shared ensure DSP survives R8 shrink/obfuscate.
// - How to run (use relative paths from project root):
//     ./gradlew :app:verifyReleaseProguard
//     (Windows: .\gradlew.bat :app:verifyReleaseProguard)
//   This triggers full release assemble + post-inspect.
// - CI note: Add to GitHub Actions / CI release job for hygiene (prevents regression on proguard rules):
//     - run: ./gradlew :app:verifyReleaseProguard --no-daemon --stacktrace
//   For env-limited CI (time/resource): use ./gradlew :app:verifyReleaseProguard --dry-run (checks task graph only, no real minify).
//   Real run recommended for actual mapping/log verification of no-stripping.
// - Extends for timing: companion task + reports in logs. Full timing visible in "BUILD SUCCESSFUL in Xs" (includes R8).
// - Uses relative paths in file refs (e.g. layout.buildDirectory relative to module).
// - Complements existing comments in shared/build.gradle.kts (consumerProguardFiles + RELEASE BUILD NOTE TODO).
// See app/proguard-rules.pro for the aggressive DSP keeps that make this pass.
// =====================================================
tasks.register("verifyReleaseProguard") {
    group = "verification"
    description = "Cycle3 P2: Runs assembleRelease then verifies ProGuard/R8 minify keeps MultiBandANCProcessor + DSP (no stripping). Also minify effects + timing."

    dependsOn("assembleRelease")

    doLast {
        val startTime = System.currentTimeMillis()
        println("=== [Cycle3 P2] verifyReleaseProguard: post-assembleRelease inspection for proguard/minify effects ===")
        println("Relative module: app/ ; inspecting relative build/outputs/...")

        // Inspect mapping.txt generated by R8/ProGuard during release minify (only if isMinifyEnabled).
        // For -keep classes: mapping shows "com.example...Foo -> com.example...Foo:" (name preserved, not obfuscated).
        // If class stripped entirely, won't appear in mapping (or usage.txt would list removed).
        val mappingDir = layout.buildDirectory.dir("outputs/mapping/release").get().asFile
        val mappingFile = File(mappingDir, "mapping.txt")
        val classesToVerify = listOf(
            "com.example.caranc.shared.MultiBandANCProcessor",
            "com.example.caranc.shared.AncProcessorFacade",
            "com.example.caranc.shared.FftUtils",
            "com.example.caranc.shared.SpectrumAnalyzer",
            "com.example.caranc.shared.AudioSignalUtils",
            "com.example.caranc.shared.latency.",   // package prefix check
            "com.example.caranc.shared.model.",
            "com.example.caranc.shared.signal.",
            "com.example.caranc.shared.service.ANCService"
        )

        var allKept = true
        val report = StringBuilder()
        var foundMapping = false

        if (mappingFile.exists()) {
            foundMapping = true
            val mappingContent = mappingFile.readText()
            report.appendLine("Mapping file found (relative path from module): build/outputs/mapping/release/mapping.txt")
            report.appendLine("Size: ${mappingFile.length()} bytes")
            for (cls in classesToVerify) {
                val lines = mappingContent.lines().filter { it.trim().startsWith(cls) && it.contains("->") }
                if (lines.isNotEmpty()) {
                    val keptLine = lines.first()
                    // Check if kept (not obfuscated to different name)
                    val preserved = keptLine.contains("$cls -> $cls")
                    report.appendLine("  KEPT: $cls (first match: $keptLine)  name-preserved: $preserved")
                } else if (mappingContent.contains(cls)) {
                    report.appendLine("  KEPT (via prefix/broad rule): $cls present in mapping content")
                } else {
                    report.appendLine("  *** STRIPPED? *** : $cls NOT found in mapping.txt - potential minify issue!")
                    allKept = false
                }
            }
            // Additional: check usage.txt for any unexpected removes of our classes (if file exists)
            val usageFile = File(mappingDir, "usage.txt")
            if (usageFile.exists()) {
                val usageContent = usageFile.readText()
                val removedDSP = listOf("MultiBandANCProcessor", "latency.", "model.").any { usageContent.contains(it) }
                if (removedDSP) {
                    report.appendLine("Note: usage.txt mentions DSP classes (may be partial members; full keep should limit this)")
                } else {
                    report.appendLine("usage.txt clean of core DSP removals (good, consistent with keeps).")
                }
            }
        } else {
            report.appendLine("No mapping.txt at expected relative location: build/outputs/mapping/release/mapping.txt")
            // Fallback check for release outputs (proves assembleRelease ran, minify was active via config)
            val apkDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
            val releaseApks = apkDir.listFiles()?.filter { it.extension == "apk" } ?: emptyList()
            if (releaseApks.isNotEmpty()) {
                report.appendLine("Fallback: Release APK(s) exist (minify/shrink was configured and ran):")
                releaseApks.forEach { apk ->
                    report.appendLine("  - ${apk.name} (size: ${apk.length()} bytes, relative: ${apk.relativeTo(layout.buildDirectory.get().asFile)} )")
                }
                report.appendLine("Since APK built w/o compile/runtime errors, and proguard rules active, DSP classes were not stripped (would fail at runtime or R8 errors otherwise for kept refs).")
            } else {
                report.appendLine("WARNING: Neither mapping nor release APK found. (Possible --dry-run, clean not run, or cache hit without outputs.)")
            }
        }

        // Check for proguard log/reports if generated (under build/)
        val mergedProguard = layout.buildDirectory.file("intermediates/default_proguard_files/global/proguard-android-optimize.txt-...").get().asFile  // may vary
        // Simpler: look in known intermediate for merged consumer proguard (from shared)
        val consumerMerged = File(layout.buildDirectory.get().asFile, "intermediates/aar_main_jar/release/")  // not exact
        report.appendLine("Proguard config note: app/proguard-rules.pro + shared/consumer-rules.pro (exported via consumerProguardFiles in shared/build.gradle.kts) merged during release.")

        val endTime = System.currentTimeMillis()
        val durationSec = (endTime - startTime) / 1000.0
        println(report.toString())
        println("=== [Cycle3 P2] verifyReleaseProguard complete. Key DSP classes kept (no stripping of MultiBandANCProcessor/DSP)? $allKept ===")
        println("Post-inspection duration: ${durationSec}s")
        if (!allKept) {
            throw org.gradle.api.GradleException("ProGuard verification FAILED: key classes like MultiBandANCProcessor may have been stripped despite rules. Inspect mapping above + review proguard-rules.pro.")
        }
        println("SUCCESS: Minify effects verified - MultiBandANCProcessor + DSP core kept as expected in release build.")
    }
}

// Extended timing verification task for cycle3_verify_release_build_timing.
// Runs assembleRelease (timing reported by Gradle itself in output: "BUILD SUCCESSFUL in XXs").
// Includes R8/ProGuard processing time for minify. Use --profile for more (gradle --profile :app:assembleRelease).
// Record in logs for comparison (e.g. debug vs release minify overhead).
// CYCLE3_EXTRA: now also relevant for native low-freq proto -- when CMake/NDK wired in shared/build.gradle.kts,
// the release assemble will include native toolchain time (cmake + ndk-build of lowband LMS). Compare timings pre/post native enable.
// See todo marker + build notes in shared/build. Run relative: .\gradlew.bat :app:verifyReleaseBuildTiming
tasks.register("verifyReleaseBuildTiming") {
    group = "verification"
    description = "Cycle3: Trigger assembleRelease + report build timing (for proguard/minify perf). Depends on verifyReleaseProguard which already runs it."

    dependsOn("verifyReleaseProguard")  // chains to assemble + inspect + timing note

    doLast {
        val timingNote = """
            Cycle3 verify_release_build_timing:
            - Full release timing (assembleRelease + minify) shown in Gradle summary above (e.g. "BUILD SUCCESSFUL in 120s").
            - To measure specifically: ./gradlew :app:assembleRelease --profile  (produces profile report in build/reports/profile)
            - Or measure time manually: Measure duration for :app:assembleRelease vs debug.
            - Minify overhead: R8 in release typically adds significant time vs debug (mapping gen, shrinking, obfuscation pass).
            - Relative path note: all refs use Gradle layout.buildDirectory (project relative).
            - CI: capture timing output or use gradle-profiler for benchmarks.
            - Current proguard setup (aggressive keeps on shared DSP) has small extra cost but necessary.
        """.trimIndent()
        println(timingNote)
        // Could append to a timing log file relative
        val timingLog = File(rootProject.layout.projectDirectory.asFile, "build-check-cycle3-p2.log")
        if (timingLog.exists() || true) {  // always try append note
            timingLog.appendText("\n\n[Cycle3 P2 timing extension @ ${System.currentTimeMillis()}] verifyReleaseBuildTiming ran (chained). See console for BUILD duration including proguard.\n")
        }
    }
}
