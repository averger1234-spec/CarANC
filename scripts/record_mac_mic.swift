#!/usr/bin/env swift
import AVFoundation
import Foundation

/// Record the Mac built-in mic to 16-bit mono WAV (independent acoustic witness).
let outPath = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "/tmp/caranc_mac_mic.wav"
let seconds = CommandLine.arguments.count > 2 ? (Double(CommandLine.arguments[2]) ?? 8.0) : 8.0
let url = URL(fileURLWithPath: outPath)
let settings: [String: Any] = [
    AVFormatIDKey: Int(kAudioFormatLinearPCM),
    AVSampleRateKey: 48000,
    AVNumberOfChannelsKey: 1,
    AVLinearPCMBitDepthKey: 16,
    AVLinearPCMIsFloatKey: false,
    AVLinearPCMIsBigEndianKey: false,
]

enum RecError: Error { case prepare, start, denied }

let rec: AVAudioRecorder
do {
    rec = try AVAudioRecorder(url: url, settings: settings)
} catch {
    fputs("AVAudioRecorder init failed: \(error)\n", stderr)
    exit(1)
}
rec.isMeteringEnabled = true
if !rec.prepareToRecord() {
    fputs("prepareToRecord failed\n", stderr)
    exit(1)
}

let sem = DispatchSemaphore(value: 0)
var allowed = false
AVCaptureDevice.requestAccess(for: .audio) { ok in
    allowed = ok
    sem.signal()
}
_ = sem.wait(timeout: .now() + 20)
if !allowed {
    fputs("mic permission denied — enable Microphone for Terminal / Grok in System Settings > Privacy\n", stderr)
    exit(2)
}

if !rec.record() {
    fputs("record() failed\n", stderr)
    exit(1)
}
FileHandle.standardError.write(Data("recording \(seconds)s -> \(outPath)\n".utf8))
Thread.sleep(forTimeInterval: max(1.0, seconds))
rec.stop()
Thread.sleep(forTimeInterval: 0.2)
print(outPath)
