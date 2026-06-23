# Consumer ProGuard / R8 rules exported from :shared KMP module
# These are automatically merged into consuming app's proguard (via consumerProguardFiles in shared/build.gradle.kts)
# Purpose: Protect ANC DSP core from aggressive shrinking/minification in release builds.
# Combined with app/proguard-rules.pro full package keep + @Keep source annotations on critical classes.

# English: Keep entire com.example.caranc.shared package and subpackages for DSP.
# 中文: 保留整個 shared DSP 核心套件，避免 release 時被 ProGuard/R8 移除 (P0 關鍵)。

-keep class com.example.caranc.shared.** { *; }
-keep class com.example.caranc.shared.model.** { *; }
-keep class com.example.caranc.shared.latency.** { *; }
-keep class com.example.caranc.shared.signal.** { *; }
-keep class com.example.caranc.shared.commercial.** { *; }  # if any public used, though commercial gated separately
-keep class com.example.caranc.shared.test.** { *; }

# Keep public facades and base interfaces (methods + fields for key entry points)
-keep interface com.example.caranc.shared.AudioProcessor {
    public <methods>;
    public <fields>;
}

-keep interface com.example.caranc.shared.AncProcessorFacade {
    public <methods>;
    public <fields>;
}

# Keep concrete main processor and common public API (MultiBand is the active impl)
-keep class com.example.caranc.shared.MultiBandANCProcessor {
    public <methods>;
    public <fields>;
    <init>(...);
}

-keep class com.example.caranc.shared.ANCProcessor {
    public <methods>;
    public <fields>;
    <init>(...);
}

# Data classes, enums, objects used across boundary (e.g. from calibration, state)
-keep class com.example.caranc.shared.**$* { *; }  # inner/nested like BandGains etc if top-level data
-keepclassmembers class com.example.caranc.shared.** {
    public <methods>;
    public <fields>;
}

# Keep for Fft/Spectrum/Audio utils (pure kotlin, called from hot paths + calibration)
-keep class com.example.caranc.shared.FftUtils { *; }
-keep class com.example.caranc.shared.SpectrumAnalyzer { *; }
-keep class com.example.caranc.shared.AudioSignalUtils { *; }
-keep class com.example.caranc.shared.ImpulseResponseEstimate { *; }

# Keep model public (CabinTransferModel and siblings used in facade)
-keep class com.example.caranc.shared.model.CabinTransferModel { *; }
-keep class com.example.caranc.shared.model.ResonancePeak { *; }
-keep class com.example.caranc.shared.model.CabinMimoProfile { *; }
-keep class com.example.caranc.shared.model.CabinZonePath { *; }
-keep class com.example.caranc.shared.model.CabinZoneId { *; }
-keep class com.example.caranc.shared.model.NoiseBandClassification { *; }
-keep class com.example.caranc.shared.model.BandGains { *; }
-keep class com.example.caranc.shared.model.ProfileAgingMonitor { *; }
-keep class com.example.caranc.shared.model.CabinResonanceDetector { *; }
-keep class com.example.caranc.shared.model.NoiseBandClassifier { *; }

# Latency sub (critical for delay, Fdaf, multirate etc.)
-keep class com.example.caranc.shared.latency.** { *; }
-keepclassmembers class com.example.caranc.shared.latency.** {
    public <methods>;
}

# Signal processing (AEC, subtractors, detectors)
-keep class com.example.caranc.shared.signal.** { *; }
-keepclassmembers class com.example.caranc.shared.signal.** {
    public <methods>;
}

# Enums and consts that may be used reflectively or in serialization
-keepclassmembers enum com.example.caranc.shared.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# CYCLE3_EXTRA: native low freq JNI keep (propagates to consumers via AAR)
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep for Compose / Android Auto if referenced via shared (car app uses some)
-keep class androidx.car.app.** { *; }
-keep class androidx.compose.** { *; }

# Allow access modification etc as in app rules if needed
# -repackageclasses ''
# -allowaccessmodification

# Assume no side effects for logs if desired (but usually in app level)
# -assumenosideeffects class android.util.Log { ... }

# END consumer rules for CarANC shared DSP
