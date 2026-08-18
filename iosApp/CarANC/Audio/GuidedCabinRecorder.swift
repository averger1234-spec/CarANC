import Foundation

/// 對齊 Android `GuidedCabinRecorder`：腳本步驟自動艙錄，供 40–80 Hz A/B。
/// 從 ANC mic tap 寫入 WAV（避免與 AVAudioEngine 搶第二路 mic）。
final class GuidedCabinRecorder: @unchecked Sendable {
    static let shared = GuidedCabinRecorder()

    private let queue = DispatchQueue(label: "caranc.cabin.recorder")
    private var fileHandle: FileHandle?
    private var path: URL?
    private var stepId: String?
    private var sampleRate: Double = 48_000
    private var framesWritten: Int64 = 0
    private var pendingStepId: String?
    private var recording = false

    private init() {}

    var isRecording: Bool { queue.sync { recording } }
    var hasPending: Bool { queue.sync { pendingStepId != nil } }

    func setPending(stepId: String, waitValidSpeed: Bool) {
        queue.sync {
            self.stopLocked(reason: "pending_replace")
            self.pendingStepId = stepId
        }
        if waitValidSpeed {
            let sid = stepId
            Task { @MainActor in
                SessionLogger.shared.event("cabin_record_pending", [
                    "stepId": sid,
                    "note": "wait_valid_speed_then_start_wav"
                ])
            }
        }
    }

    func clearPending() {
        queue.sync { pendingStepId = nil }
    }

    func startIfPending(sampleRate: Double) {
        let step: String? = queue.sync {
            let s = pendingStepId
            pendingStepId = nil
            return s
        }
        guard let step else { return }
        start(stepId: step, sampleRate: sampleRate)
    }

    func start(stepId: String, sampleRate: Double) {
        queue.sync {
            stopLocked(reason: "restart")
            self.sampleRate = sampleRate
            self.stepId = stepId
            framesWritten = 0
            let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("anc_logs", isDirectory: true)
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let ts = ISO8601DateFormatter().string(from: Date())
                .replacingOccurrences(of: ":", with: "")
                .replacingOccurrences(of: "-", with: "")
            let url = dir.appendingPathComponent("cabin_\(stepId)_\(ts).wav")
            do {
                FileManager.default.createFile(atPath: url.path, contents: nil)
                let fh = try FileHandle(forWritingTo: url)
                fh.write(Data(count: 44))
                fileHandle = fh
                path = url
                recording = true
                let pathStr = url.path
                let sid = stepId
                Task { @MainActor in
                    SessionLogger.shared.event("cabin_record_start", [
                        "stepId": sid,
                        "path": pathStr,
                        "format": "wav_int16_mono",
                        "note": "auto_cabin_wav_for_40_80Hz_A_B"
                    ])
                }
            } catch {
                let err = error.localizedDescription
                let sid = stepId
                Task { @MainActor in
                    SessionLogger.shared.event("cabin_record_error", [
                        "stepId": sid,
                        "error": err
                    ])
                }
                fileHandle = nil
                path = nil
                recording = false
            }
        }
    }

    /// 可由音訊執行緒呼叫：寫入 mono float −1…1
    func append(mono: [Float]) {
        guard !mono.isEmpty else { return }
        var pcm = [Int16](repeating: 0, count: mono.count)
        for i in 0..<mono.count {
            let s = max(-1, min(1, mono[i]))
            pcm[i] = Int16(clamping: Int(s * 32767))
        }
        let data = pcm.withUnsafeBufferPointer { Data(buffer: $0) }
        let n = mono.count
        queue.async { [weak self] in
            guard let self, self.recording, let fh = self.fileHandle else { return }
            fh.write(data)
            self.framesWritten += Int64(n)
        }
    }

    @discardableResult
    func stop(reason: String = "step_end") -> String? {
        queue.sync { stopLocked(reason: reason) }
    }

    @discardableResult
    private func stopLocked(reason: String) -> String? {
        let url = path
        let step = stepId
        let frames = framesWritten
        let sr = sampleRate
        try? fileHandle?.close()
        fileHandle = nil
        path = nil
        stepId = nil
        recording = false
        framesWritten = 0
        guard let url else { return nil }
        rewriteWavHeader(url: url, sampleRate: sr, frames: frames)
        let bytes = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value ?? 0
        let fields: [String: String] = [
            "stepId": step ?? "",
            "path": url.path,
            "bytes": "\(bytes)",
            "frames": "\(frames)",
            "durationSec": String(format: "%.1f", Double(frames) / max(sr, 1)),
            "reason": reason,
            "note": "compare_cabin_off_vs_on_40_80Hz"
        ]
        Task { @MainActor in
            SessionLogger.shared.event("cabin_record_stop", fields)
        }
        return url.path
    }

    private func rewriteWavHeader(url: URL, sampleRate: Double, frames: Int64) {
        let dataSize = frames * 2
        let sr = UInt32(sampleRate.rounded())
        var header = Data()
        func appendU32(_ v: UInt32) {
            var le = v.littleEndian
            withUnsafeBytes(of: &le) { header.append(contentsOf: $0) }
        }
        func appendU16(_ v: UInt16) {
            var le = v.littleEndian
            withUnsafeBytes(of: &le) { header.append(contentsOf: $0) }
        }
        header.append(contentsOf: Array("RIFF".utf8))
        appendU32(UInt32(36 + dataSize))
        header.append(contentsOf: Array("WAVE".utf8))
        header.append(contentsOf: Array("fmt ".utf8))
        appendU32(16)
        appendU16(1)
        appendU16(1)
        appendU32(sr)
        appendU32(sr * 2)
        appendU16(2)
        appendU16(16)
        header.append(contentsOf: Array("data".utf8))
        appendU32(UInt32(dataSize))
        guard let fh = try? FileHandle(forWritingTo: url) else { return }
        defer { try? fh.close() }
        fh.seek(toFileOffset: 0)
        fh.write(header)
    }
}
