import Foundation

/// Android `running_snapshot` 欄位名（必須與 shared `AncRunningSnapshotSchema` 一致）
/// 來源：Android AudioEngine ~2s JSONL — iOS 迭代複用同一套 key，不另發明名字。
enum AndroidSnapshotKeys {
    static let schemaVersion = "1"
    static let phase = "running_snapshot"

    static let rawDb = "rawDb"
    static let antiNoiseDb = "antiNoiseDb"
    static let cancelledDb = "cancelledDb"
    static let reductionDb = "reductionDb"
    static let reductionDbLegacy = "reductionDbLegacy"
    static let lowBandRumbleReduction = "lowBandRumbleReduction"
    static let primaryReductionKpi = "primaryReductionKpi"

    static let rawLowBandDb = "rawLowBandDb"
    static let residualLowBandDb = "residualLowBandDb"
    static let plantResidualLowBandDb = "plantResidualLowBandDb"
    static let plantResidualReductionDb = "plantResidualReductionDb"
    static let bandE60Db = "bandE60Db"
    static let bandE80Db = "bandE80Db"
    static let bandE100Db = "bandE100Db"
    static let bandE120Db = "bandE120Db"
    static let outputPathActive = "outputPathActive"
    static let plantDelayForResidual = "plantDelayForResidual"

    static let nvhFocus = "nvhFocus"
    static let nvhTargetHz = "nvhTargetHz"
    // Android 1.1.0 SpeedScheduledNvhGains
    static let speedNvhBinKmh = "speedNvhBinKmh"
    static let speedNvhLowGain = "speedNvhLowGain"
    static let speedNvhMidGain = "speedNvhMidGain"
    static let speedNvhTotalAnti = "speedNvhTotalAnti"
    static let speedNvhTableId = "speedNvhTableId"

    static let vehicleSpeedKmh = "vehicleSpeedKmh"
    static let vehicleSpeedValid = "vehicleSpeedValid"
    static let isDrivingRumble = "isDrivingRumble"
    static let rumbleAccel = "rumbleAccel"
    static let roadRoughness = "roadRoughness"

    static let estimatedLatencyMs = "estimatedLatencyMs"
    static let measuredLatencyMs = "measuredLatencyMs"
    static let maxCancelFrequencyHz = "maxCancelFrequencyHz"
    static let latencyMidEnabled = "latencyMidEnabled"
    static let latencyHighEnabled = "latencyHighEnabled"
    static let latencyStrategy = "latencyStrategy"
    static let plantElectricalDelaySamples = "plantElectricalDelaySamples"

    static let lmsLowUpdates = "lmsLowUpdates"
    static let lmsMidUpdates = "lmsMidUpdates"
    static let weightFrozen = "weightFrozen"
    static let processingMode = "processingMode"
    static let tier = "tier"
    static let effectiveLowMu = "effectiveLowMu"
    static let effectiveMidMu = "effectiveMidMu"

    static let imuMicCoherence = "imuMicCoherence"
    static let bankMatchQuality = "bankMatchQuality"
    static let fixedBankOut = "fixedBankOut"
    static let neuralLatentEnabled = "neuralLatentEnabled"
    static let fdafDelayless = "fdafDelayless"

    static let audioBackend = "audioBackend"
    static let aaLinkType = "aaLinkType"
    static let wirelessAaSuspected = "wirelessAaSuspected"
    static let platform = "platform"
    static let snapshotSchemaVersion = "snapshotSchemaVersion"

    static let guidedTestStepId = "guidedTestStepId"
    static let guidedTestActive = "guidedTestActive"

    static let blockCount = "blockCount"
    static let blockRms = "blockRms"
}

/// iOS session log — **複用 Android running_snapshot schema**，不是另起一套。
@MainActor
final class SessionLogger: ObservableObject {
    static let shared = SessionLogger()

    @Published private(set) var lines: [String] = []
    @Published private(set) var sessionId: String = ""
    /// 目前引導腳本步驟（由 GuidedTestRunner 寫入）
    var guidedTestStepId: String = ""
    var guidedTestActive: Bool = false

    private var snapshotCounter = 0
    private let iso = ISO8601DateFormatter()

    private init() {}

    func startSession(meta: [String: String] = [:]) {
        lines.removeAll()
        snapshotCounter = 0
        let id = "ios_\(Int(Date().timeIntervalSince1970))"
        sessionId = id
        var fields = meta
        fields["sessionId"] = id
        fields[AndroidSnapshotKeys.platform] = "ios"
        fields[AndroidSnapshotKeys.snapshotSchemaVersion] = AndroidSnapshotKeys.schemaVersion
        fields["appVersion"] = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        fields["build"] = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"
        fields["reuseNote"] = "android_running_snapshot_schema_v\(AndroidSnapshotKeys.schemaVersion)"
        append(phase: "session_start", fields: fields)
    }

    func endSession() {
        append(phase: "session_end", fields: [
            "sessionId": sessionId,
            "lineCount": "\(lines.count)",
            AndroidSnapshotKeys.platform: "ios"
        ])
    }

    func event(_ phase: String, _ fields: [String: String] = [:]) {
        append(phase: phase, fields: fields)
    }

    /**
     對齊 Android AudioEngine running_snapshot。
     - 能算的：填真值
     - 尚未 port 的 Android 專屬模組：填 `n/a`（保留 key，方便同一套分析腳本）
     */
    func runningSnapshot(
        model: AncAppModel,
        antiDb: Float,
        lowBandKpi: Float,
        reductionDb: Float,
        lmsLow: Int64,
        lmsMid: Int64,
        latencyStrategy: String,
        blockCount: Int64,
        blockRms: Float,
        lowBandEnergyIn: Float,
        lowBandEnergyAnti: Float,
        speedNvhBinKmh: Int = 0,
        speedNvhLowGain: Float = 1,
        speedNvhMidGain: Float = 0.25,
        speedNvhTotalAnti: Float = 1,
        speedNvhTableId: String = "none"
    ) {
        snapshotCounter += 1
        let driving = model.vehicleSpeedValid && model.vehicleSpeedKmh > 40 && model.rumbleAccel > 0.5
        let outputActive = model.isRunning && antiDb > -80

        // 低頻代理 dB（iOS 尚未 plant residual：用 spectrum energy 映射，標記 source）
        let rawLow = model.rawDb - 3 // rough low emphasis placeholder
        let residualLow = model.rawDb // no residual mic mix on iOS yet

        var f: [String: String] = [
            AndroidSnapshotKeys.snapshotSchemaVersion: AndroidSnapshotKeys.schemaVersion,
            AndroidSnapshotKeys.platform: "ios",
            "n": "\(snapshotCounter)",
            "phaseUi": model.phase.rawValue,

            // Core KPI (Android names)
            AndroidSnapshotKeys.rawDb: f2(model.rawDb),
            AndroidSnapshotKeys.antiNoiseDb: f2(antiDb),
            AndroidSnapshotKeys.cancelledDb: f2(model.rawDb), // iOS: no separate residual mic; same as raw until plant
            AndroidSnapshotKeys.reductionDb: f2(reductionDb),
            AndroidSnapshotKeys.reductionDbLegacy: f2(max(0, reductionDb)),
            AndroidSnapshotKeys.lowBandRumbleReduction: f2(lowBandKpi),
            AndroidSnapshotKeys.primaryReductionKpi: AndroidSnapshotKeys.lowBandRumbleReduction,

            // Plant residual — not yet on iOS Swift DSP (will come via KMP MultiBand path)
            AndroidSnapshotKeys.rawLowBandDb: f2(rawLow),
            AndroidSnapshotKeys.residualLowBandDb: f2(residualLow),
            AndroidSnapshotKeys.plantResidualLowBandDb: "n/a",
            AndroidSnapshotKeys.plantResidualReductionDb: "n/a",
            AndroidSnapshotKeys.bandE60Db: "n/a",
            AndroidSnapshotKeys.bandE80Db: "n/a",
            AndroidSnapshotKeys.bandE100Db: "n/a",
            AndroidSnapshotKeys.bandE120Db: "n/a",
            AndroidSnapshotKeys.outputPathActive: "\(outputActive)",
            AndroidSnapshotKeys.plantDelayForResidual: "n/a",
            "kpiSource": "ios_spectrum_proxy", // 分析時可知非 Android plant

            // NVH + Android 1.1.0 speed-scheduled gains (from KMP)
            AndroidSnapshotKeys.nvhFocus: model.nvhFocus.rawValue,
            AndroidSnapshotKeys.nvhTargetHz: nvhTargetLabel(model.nvhFocus),
            AndroidSnapshotKeys.speedNvhBinKmh: "\(speedNvhBinKmh)",
            AndroidSnapshotKeys.speedNvhLowGain: f2(speedNvhLowGain),
            AndroidSnapshotKeys.speedNvhMidGain: f2(speedNvhMidGain),
            AndroidSnapshotKeys.speedNvhTotalAnti: f2(speedNvhTotalAnti),
            AndroidSnapshotKeys.speedNvhTableId: speedNvhTableId,

            // Vehicle / IMU（gps | gps_hold | imu_proxy）
            AndroidSnapshotKeys.vehicleSpeedKmh: f1(model.vehicleSpeedKmh),
            AndroidSnapshotKeys.vehicleSpeedValid: "\(model.vehicleSpeedValid)",
            "speedSource": model.speedSource,
            "speedHoldAgeSec": f1(model.speedHoldAgeSec),
            "imuProxyKmh": f1(model.imuProxyKmh),
            "speedValidForRoadTest": "\(model.speedValidForRoadTest)",
            AndroidSnapshotKeys.isDrivingRumble: "\(driving)",
            AndroidSnapshotKeys.rumbleAccel: f2(model.rumbleAccel),
            AndroidSnapshotKeys.roadRoughness: "n/a",

            // Latency
            AndroidSnapshotKeys.estimatedLatencyMs: f1(model.estimatedLatencyMs),
            AndroidSnapshotKeys.measuredLatencyMs: f1(model.estimatedLatencyMs),
            AndroidSnapshotKeys.maxCancelFrequencyHz: f1(model.maxCancelHz),
            AndroidSnapshotKeys.latencyMidEnabled: "\(model.midEnabled)",
            AndroidSnapshotKeys.latencyHighEnabled: "\(model.highEnabled)",
            AndroidSnapshotKeys.latencyStrategy: latencyStrategy,
            AndroidSnapshotKeys.plantElectricalDelaySamples: "n/a",

            // Learning
            AndroidSnapshotKeys.lmsLowUpdates: "\(lmsLow)",
            AndroidSnapshotKeys.lmsMidUpdates: "\(lmsMid)",
            AndroidSnapshotKeys.weightFrozen: "false",
            AndroidSnapshotKeys.processingMode: model.phase == .driving ? "ROAD_NOISE_GPS" : "NORMAL",
            AndroidSnapshotKeys.tier: model.tier.rawValue,
            AndroidSnapshotKeys.effectiveLowMu: "n/a",
            AndroidSnapshotKeys.effectiveMidMu: "n/a",

            // Bank / FDAF / coherence — Android modules; KMP 接上後改真值
            AndroidSnapshotKeys.imuMicCoherence: "n/a",
            AndroidSnapshotKeys.bankMatchQuality: "n/a",
            AndroidSnapshotKeys.fixedBankOut: "n/a",
            AndroidSnapshotKeys.neuralLatentEnabled: "n/a",
            AndroidSnapshotKeys.fdafDelayless: "n/a",

            // Route（對齊 Android aaLinkType；CarPlay = carplay_wired / carplay_wireless / local）
            AndroidSnapshotKeys.audioBackend: model.carPlayConnected
                ? "AVAudioEngine_carplay"
                : "AVAudioEngine_local",
            AndroidSnapshotKeys.aaLinkType: model.aaLinkType,
            AndroidSnapshotKeys.wirelessAaSuspected: "\(model.wirelessCarPlaySuspected)",

            // Guided
            AndroidSnapshotKeys.guidedTestStepId: guidedTestStepId,
            AndroidSnapshotKeys.guidedTestActive: "\(guidedTestActive)",

            AndroidSnapshotKeys.blockCount: "\(blockCount)",
            AndroidSnapshotKeys.blockRms: f3(blockRms),
            "lowBandEnergyIn": f3(lowBandEnergyIn),
            "lowBandEnergyAnti": f3(lowBandEnergyAnti)
        ]
        append(phase: AndroidSnapshotKeys.phase, fields: f)
    }

    func exportText(headerNote: String = "") -> String {
        var out: [String] = [
            "CarANC Session Log (Android-schema parity)",
            "sessionId=\(sessionId)",
            "exportedAt=\(iso.string(from: Date()))",
            "platform=ios",
            "snapshotSchemaVersion=\(AndroidSnapshotKeys.schemaVersion)",
            "reuse=android_AudioEngine.running_snapshot field names",
            "sharedRef=com.example.caranc.shared.AncRunningSnapshotSchema",
            ""
        ]
        if !headerNote.isEmpty {
            out.append(headerNote)
            out.append("")
        }
        out.append("--- ANDROID PARITY KEYS (same names as Android JSONL) ---")
        out.append("lowBandRumbleReduction  primary KPI")
        out.append("antiNoiseDb             anti output (Android name)")
        out.append("rawDb / reductionDb     levels")
        out.append("plantResidual*          n/a until KMP plant path")
        out.append("imuMicCoherence/bank*   n/a until KMP MultiBand wired")
        out.append("isDrivingRumble         speed>40 & accel>0.5 (same rule)")
        out.append("outputPathActive        anti energy gate")
        out.append("kpiSource=ios_spectrum_proxy  until plant residual ported")
        out.append("")
        out.append("--- LOG LINES ---")
        out.append(contentsOf: lines)
        return out.joined(separator: "\n")
    }

    private func nvhTargetLabel(_ f: NvhFocus) -> String {
        switch f {
        case .roadRumble: return "40-200Hz"
        case .tireNoise: return "80-350Hz"
        case .windShear: return ">500Hz_no_chase"
        case .idle: return "idle"
        case .mixedCabin: return "mixed"
        }
    }

    private func append(phase: String, fields: [String: String]) {
        let ts = iso.string(from: Date())
        let body = fields.keys.sorted().map { "\($0)=\(fields[$0] ?? "")" }.joined(separator: " ")
        lines.append("[\(ts)] phase=\(phase) \(body)")
        if lines.count > 4000 {
            lines.removeFirst(lines.count - 4000)
        }
    }

    private func f1(_ v: Float) -> String { String(format: "%.1f", v) }
    private func f2(_ v: Float) -> String { String(format: "%.2f", v) }
    private func f3(_ v: Float) -> String { String(format: "%.3f", v) }
}
