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
    // 1.2.3 AdaptiveNarrowbandBank
    static let tireNotchEnergy = "tireNotchEnergy"
    static let windNotchEnergy = "windNotchEnergy"
    static let tireNotchF0Hz = "tireNotchF0Hz"
    static let windNotchActiveCount = "windNotchActiveCount"
    static let notchMixAnti = "notchMixAnti"
    static let roadNotchEnergy = "roadNotchEnergy"
    static let roadBoomWeightEnergy = "roadBoomWeightEnergy"
    static let boomPressureOut = "boomPressureOut"
    static let boomPlantCorr = "boomPlantCorr"
    static let plantElectricalDelaySamples = "plantElectricalDelaySamples"
    static let boomPolarity = "boomPolarity"
    static let muteAnti = "muteAnti"
    static let userAncGain = "userAncGain"
    static let forceBoomPolarity = "forceBoomPolarity"
    static let effectiveLowMu = "effectiveLowMu"
    static let effectiveMidMu = "effectiveMidMu"

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

    static let lmsLowUpdates = "lmsLowUpdates"
    static let lmsMidUpdates = "lmsMidUpdates"
    static let weightFrozen = "weightFrozen"
    static let processingMode = "processingMode"
    static let tier = "tier"

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
/// 對齊 Android `AncSessionLogger`：寫入 `Documents/anc_logs/anc_session_*.log` 檔案。
@MainActor
final class SessionLogger: ObservableObject {
    static let shared = SessionLogger()

    @Published private(set) var lines: [String] = []
    @Published private(set) var sessionId: String = ""
    /// 目前磁碟上的 session log（對齊 Android getLatestLogFile）
    @Published private(set) var currentLogFileURL: URL?
    /// 目前引導腳本步驟（由 GuidedTestRunner 寫入）
    var guidedTestStepId: String = ""
    var guidedTestActive: Bool = false

    private var snapshotCounter = 0
    private let iso = ISO8601DateFormatter()
    private let fileQueue = DispatchQueue(label: "caranc.session.log.file")

    private init() {}

    /// 對齊 Android `filesDir/anc_logs`
    static var logsDirectory: URL {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("anc_logs", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    func startSession(meta: [String: String] = [:]) {
        lines.removeAll()
        snapshotCounter = 0
        let id = "ios_\(Int(Date().timeIntervalSince1970))"
        sessionId = id
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.dateFormat = "yyyyMMdd_HHmmss"
        let fileName = "anc_session_\(df.string(from: Date())).log"
        let url = Self.logsDirectory.appendingPathComponent(fileName)
        currentLogFileURL = url
        let header = """
        # CarANC Session Log
        # format=text_lines
        # platform=ios
        # sessionId=\(id)
        # ---

        """
        fileQueue.async {
            try? header.write(to: url, atomically: true, encoding: .utf8)
        }
        var fields = meta
        fields["sessionId"] = id
        fields[AndroidSnapshotKeys.platform] = "ios"
        fields[AndroidSnapshotKeys.snapshotSchemaVersion] = AndroidSnapshotKeys.schemaVersion
        fields["appVersion"] = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        fields["build"] = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"
        fields["reuseNote"] = "android_running_snapshot_schema_v\(AndroidSnapshotKeys.schemaVersion)"
        fields["logFileName"] = fileName
        fields["logPath"] = url.path
        append(phase: "session_start", fields: fields)
    }

    func endSession() {
        append(phase: "session_end", fields: [
            "sessionId": sessionId,
            "lineCount": "\(lines.count)",
            AndroidSnapshotKeys.platform: "ios",
            "logPath": currentLogFileURL?.path ?? ""
        ])
    }

    /// 寫入完整匯出文字（導測 finish）；回傳檔案 URL（對齊 Android saveLatestLog）
    @discardableResult
    func saveExportFile(text: String, prefix: String = "anc_guided_export") -> URL? {
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.dateFormat = "yyyyMMdd_HHmmss"
        let name = "\(prefix)_\(df.string(from: Date())).log"
        let url = Self.logsDirectory.appendingPathComponent(name)
        do {
            try text.write(to: url, atomically: true, encoding: .utf8)
            event("log_file_saved", [
                "path": url.path,
                "bytes": "\((try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value ?? 0)",
                "note": "ios_parity_android_anc_logs"
            ])
            return url
        } catch {
            event("log_file_save_error", ["error": error.localizedDescription])
            return nil
        }
    }

    static func latestSessionLogURL() -> URL? {
        let files = (try? FileManager.default.contentsOfDirectory(
            at: logsDirectory,
            includingPropertiesForKeys: [.contentModificationDateKey],
            options: [.skipsHiddenFiles]
        )) ?? []
        return files
            .filter { $0.lastPathComponent.hasPrefix("anc_session_") || $0.lastPathComponent.hasPrefix("anc_guided_") }
            .filter { $0.pathExtension == "log" }
            .sorted { a, b in
                let da = (try? a.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                let db = (try? b.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                return da > db
            }
            .first
    }

    /// 本次 session 期間寫下的 cabin wav（依檔名時間排序）
    static func recentCabinWavURLs(limit: Int = 8) -> [URL] {
        let files = (try? FileManager.default.contentsOfDirectory(
            at: logsDirectory,
            includingPropertiesForKeys: [.contentModificationDateKey],
            options: [.skipsHiddenFiles]
        )) ?? []
        return files
            .filter { $0.lastPathComponent.hasPrefix("cabin_") && $0.pathExtension.lowercased() == "wav" }
            .sorted { a, b in
                let da = (try? a.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                let db = (try? b.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                return da > db
            }
            .prefix(limit)
            .reversed()
            .map { $0 }
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
            AndroidSnapshotKeys.plantResidualLowBandDb: f2(model.rawDb - model.plantResidualReductionDb),
            AndroidSnapshotKeys.plantResidualReductionDb: f2(model.plantResidualReductionDb),
            AndroidSnapshotKeys.bandE60Db: "n/a",
            AndroidSnapshotKeys.bandE80Db: "n/a",
            AndroidSnapshotKeys.bandE100Db: "n/a",
            AndroidSnapshotKeys.bandE120Db: "n/a",
            AndroidSnapshotKeys.outputPathActive: "\(outputActive)",
            AndroidSnapshotKeys.plantDelayForResidual: "\(model.plantElectricalDelaySamples)",
            AndroidSnapshotKeys.plantElectricalDelaySamples: "\(model.plantElectricalDelaySamples)",
            "kpiSource": "ios_spectrum_proxy", // plant residual = mic−(mic+anti) band proxy

            // NVH + Android 1.1.0 speed-scheduled gains (from KMP)
            AndroidSnapshotKeys.nvhFocus: model.nvhFocus.rawValue,
            AndroidSnapshotKeys.nvhTargetHz: nvhTargetLabel(model.nvhFocus),
            "forcedNvhFocus": model.forcedNvhFocus,
            AndroidSnapshotKeys.speedNvhBinKmh: "\(speedNvhBinKmh)",
            AndroidSnapshotKeys.speedNvhLowGain: f2(speedNvhLowGain),
            AndroidSnapshotKeys.speedNvhMidGain: f2(speedNvhMidGain),
            AndroidSnapshotKeys.speedNvhTotalAnti: f2(speedNvhTotalAnti),
            AndroidSnapshotKeys.speedNvhTableId: speedNvhTableId,

            // 1.2.3–1.2.5 notch / boom
            AndroidSnapshotKeys.tireNotchEnergy: f3(model.tireNotchEnergy),
            AndroidSnapshotKeys.windNotchEnergy: f3(model.windNotchEnergy),
            AndroidSnapshotKeys.tireNotchF0Hz: f1(model.tireNotchF0Hz),
            AndroidSnapshotKeys.windNotchActiveCount: "\(model.windNotchActiveCount)",
            AndroidSnapshotKeys.notchMixAnti: f3(model.notchMixAnti),
            AndroidSnapshotKeys.roadNotchEnergy: f3(model.roadNotchEnergy),
            AndroidSnapshotKeys.roadBoomWeightEnergy: f3(model.roadBoomWeightEnergy),
            AndroidSnapshotKeys.boomPressureOut: f3(model.boomPressureOut),
            AndroidSnapshotKeys.boomPlantCorr: f3(model.boomPlantCorr),
            AndroidSnapshotKeys.boomPolarity: f1(model.boomPolarity),
            AndroidSnapshotKeys.muteAnti: "\(model.muteAnti)",
            AndroidSnapshotKeys.userAncGain: f2(model.userAncGain),
            AndroidSnapshotKeys.forceBoomPolarity: f1(model.forceBoomPolarity),
            AndroidSnapshotKeys.plantResidualReductionDb: f2(model.plantResidualReductionDb),

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

            // Learning
            AndroidSnapshotKeys.lmsLowUpdates: "\(lmsLow)",
            AndroidSnapshotKeys.lmsMidUpdates: "\(lmsMid)",
            AndroidSnapshotKeys.weightFrozen: "false",
            AndroidSnapshotKeys.processingMode: model.phase == .driving ? "ROAD_NOISE_GPS" : "NORMAL",
            AndroidSnapshotKeys.tier: model.tier.rawValue,
            AndroidSnapshotKeys.effectiveLowMu: f3(model.effectiveLowMu),
            AndroidSnapshotKeys.effectiveMidMu: f3(model.effectiveMidMu),

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
        let line = "[\(ts)] phase=\(phase) \(body)"
        lines.append(line)
        if lines.count > 4000 {
            lines.removeFirst(lines.count - 4000)
        }
        // 對齊 Android：即時 append 到 anc_logs/*.log
        if let url = currentLogFileURL {
            fileQueue.async {
                if let data = (line + "\n").data(using: .utf8) {
                    if let handle = try? FileHandle(forWritingTo: url) {
                        defer { try? handle.close() }
                        handle.seekToEndOfFile()
                        handle.write(data)
                    } else {
                        try? (line + "\n").write(to: url, atomically: true, encoding: .utf8)
                    }
                }
            }
        }
    }

    private func f1(_ v: Float) -> String { String(format: "%.1f", v) }
    private func f2(_ v: Float) -> String { String(format: "%.2f", v) }
    private func f3(_ v: Float) -> String { String(format: "%.3f", v) }
}
