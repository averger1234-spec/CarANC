# ProGuard / R8 rules for CarANC (COMPLETELY REWRITTEN per task for P0 ProGuard hardening)
#
# 中文說明:
# 此檔案已完全重寫，以保護 ANC DSP 核心，防止在 assembleRelease (R8 minify + shrinkResources) 時
# MultiBandANCProcessor 及所有 shared DSP (latency/*、model/*、signal/* 等) 被移除或名稱混淆而導致 App 崩壞。
# 之前僅保留 legacy simple ANCProcessor 是不夠的。
# 包含任務要求的全面 -keep 規則 + 雙語註解。
# 與 shared 模組的 @Keep 及 consumer-rules 搭配使用。
#
# English:
# This file has been completely rewritten to protect the ANC DSP core from R8 stripping in release builds.
# Prevents removal/mangling of MultiBandANCProcessor and all shared DSP (latency/* model/* signal/* etc).
# Previous legacy-only ANCProcessor keep was insufficient.
# Comprehensive -keep rules exactly as specified, with bilingual comments.
# Complements @Keep annotations in shared commonMain + consumer-proguard export.

# =============================================
# AGGRESSIVE KEEPS FOR ANC DSP CORE (as per strict task requirements)
# =============================================

# 1. Specific for MultiBandANCProcessor
-keep class com.example.caranc.shared.MultiBandANCProcessor {
    public <methods>;
    public <fields>;
    <init>(...);
}

# 2. Specific for AncProcessorFacade
-keep class com.example.caranc.shared.AncProcessorFacade {
    public <methods>;
    public <fields>;
    <init>(...);
}

# 3. All classes under com.example.caranc.shared.latency.*
-keep class com.example.caranc.shared.latency.** { *; }

# 4. com.example.caranc.shared.model.*
-keep class com.example.caranc.shared.model.** { *; }

# 5. com.example.caranc.shared.signal.*
-keep class com.example.caranc.shared.signal.** { *; }

# 6. Broad com.example.caranc.shared.* (for AncState*, FftUtils, SpectrumAnalyzer, AudioSignalUtils, Cabin*, Tier*, etc. and all other shared DSP)
-keep class com.example.caranc.shared.** { *; }

# Keep public methods, fields, constructors for the processor and facades (explicit as required)
-keepclassmembers class com.example.caranc.shared.MultiBandANCProcessor {
    public <methods>;
    public <fields>;
}

-keepclassmembers class com.example.caranc.shared.AncProcessorFacade {
    public <methods>;
    public <fields>;
}

# Keepclassmembers for all shared to preserve public API (data classes, utils used across DSP)
-keepclassmembers class com.example.caranc.shared.** {
    public <methods>;
    public <fields>;
}

-keepclassmembers class com.example.caranc.shared.model.** {
    public <methods>;
    public <fields>;
}

-keepclassmembers class com.example.caranc.shared.latency.** {
    public <methods>;
    public <fields>;
}

-keepclassmembers class com.example.caranc.shared.signal.** {
    public <methods>;
    public <fields>;
}

# Explicit keeps for listed DSP utils (FftUtils, SpectrumAnalyzer, AudioSignalUtils) + common ones
-keep class com.example.caranc.shared.FftUtils {
    public <methods>;
    public <fields>;
    <init>(...);
}
-keep class com.example.caranc.shared.SpectrumAnalyzer {
    public <methods>;
    public <fields>;
    <init>(...);
}
-keep class com.example.caranc.shared.AudioSignalUtils {
    public <methods>;
    public <fields>;
    <init>(...);
}

# Optionally: -keep for ANCService for service entry (registered in manifest, name + entry points must survive)
# (under shared.service but covered by broad; explicit for safety)
-keep class com.example.caranc.shared.service.ANCService {
    public <methods>;
    public <fields>;
    <init>(...);
}

# Legacy ANCProcessor keep (for any remaining refs; broad would cover but explicit)
-keep class com.example.caranc.shared.ANCProcessor {
    public <methods>;
    public <fields>;
    <init>(...);
}

# Enums preservation (for when() / reflection in DSP logic)
-keepclassmembers enum com.example.caranc.shared.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# CYCLE3_EXTRA native low-freq: keep JNI native methods (for NativeLowBandProcessor + any future native LMS)
# This ensures R8 does not strip the native declarations even under aggressive minify.
-keepclasseswithmembernames class * {
    native <methods>;
}

# =============================================
# MINIMAL SUPPORTING RULES (Compose, AA, logs - required for build but not core DSP)
# =============================================
# Compose (R8 defaults may suffice but explicit for minify safety)
-keep class androidx.compose.** { *; }
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }

# Android Auto / Car App Library
-keep class androidx.car.app.** { *; }

# KMM helpers if used
-keep class com.rickclephas.kmm.** { *; }

# Remove side-effect free Log in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
}

# Serialization if used in shared
-keep class kotlinx.serialization.** { *; }

# END of completely rewritten proguard-rules.pro for CarANC ANC DSP protection
