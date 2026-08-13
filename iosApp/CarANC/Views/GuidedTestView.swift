import SwiftUI

struct GuidedTestView: View {
    @ObservedObject var model: AncAppModel
    @ObservedObject var engine: AncAudioEngine
    @StateObject private var runner = GuidedTestRunner()
    @State private var sharePayload: SharePayload?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                header
                if runner.active || runner.finished, let step = runner.currentStep {
                    stepCard(step)
                } else {
                    intro
                }
                controls
                if !runner.logLines.isEmpty {
                    logPreview
                }
            }
            .padding()
        }
        .navigationTitle("測試腳本")
        .onAppear { runner.bind(model: model, engine: engine) }
        .sheet(item: $sharePayload) { payload in
            ActivityView(activityItems: [payload.text])
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(CarRoadTuningScript.scriptName)
                .font(.headline)
            Text("腳本 ID：\(CarRoadTuningScript.scriptId)")
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(runner.statusLine)
                .font(.subheadline)
                .foregroundStyle(runner.active ? .primary : .secondary)
            if runner.active, !runner.pauseReason.isEmpty {
                Text(runner.pauseReason)
                    .font(.caption)
                    .foregroundStyle(.orange)
            }
            if runner.active {
                ProgressView(value: runner.progress)
                Text(runner.autoAdvance ? "模式：自動進階（有效秒）" : "模式：手動下一步")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.blue.opacity(0.12))
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }

    private var intro: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("對齊 Android `CarRoadTuningScript` + `GuidedTestController`")
                .font(.subheadline.weight(.semibold))
            Text("自動進階：有效行駛秒達標（或 maxWall）→ 下一步（同 Android autoAdvance=true）")
                .font(.caption)
                .foregroundStyle(.cyan)
            Text("步驟概覽（1.2.3 三目標 + notch）：")
                .font(.caption)
                .foregroundStyle(.secondary)
            ForEach(Array(CarRoadTuningScript.steps.enumerated()), id: \.element.id) { idx, step in
                HStack(alignment: .top) {
                    Text("\(idx + 1).")
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(step.title).font(.subheadline)
                        Text(step.wallClockOnly
                             ? "壁鐘 \(step.durationSec)s · 自動"
                             : "有效 \(step.durationSec)s（≥\(Int(step.minSpeedKmh))）· 自動")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            Text("建議先在「狀態」啟動降噪。行駛步車速不足會暫停累秒（紅燈不計）。")
                .font(.caption)
                .foregroundStyle(.orange)
        }
    }

    private func stepCard(_ step: GuidedTestStep) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("步驟 \(runner.stepIndex + 1)/\(CarRoadTuningScript.steps.count)")
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(step.title)
                .font(.title3.weight(.semibold))
            ForEach(step.instructions, id: \.self) { line in
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "arrow.right.circle.fill")
                        .foregroundStyle(.cyan)
                        .font(.caption)
                    Text(line).font(.subheadline)
                }
            }
            if !step.checklist.isEmpty {
                Text("檢查清單").font(.caption.weight(.semibold)).padding(.top, 4)
                ForEach(step.checklist, id: \.self) { item in
                    Button {
                        runner.toggleCheck(item)
                    } label: {
                        HStack {
                            Image(systemName: runner.checked.contains(item) ? "checkmark.square.fill" : "square")
                            Text(item).font(.subheadline)
                            Spacer()
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
            if !step.wallClockOnly {
                Text(String(
                    format: "有效 %d / %d 秒 · 壁鐘 %d / 上限 %d",
                    runner.validSec, step.durationSec, runner.wallSec, step.maxWallSec
                ))
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
            }
        }
        .padding()
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }

    private var controls: some View {
        VStack(spacing: 10) {
            if !runner.active && !runner.finished {
                Button {
                    Task { await ensureAncThenStart() }
                } label: {
                    Label("開始腳本", systemImage: "play.fill")
                        .frame(maxWidth: .infinity)
                        .padding()
                }
                .buttonStyle(.borderedProminent)
            }
            if runner.active {
                HStack {
                    Button {
                        runner.completeManually()
                    } label: {
                        Text("完成這步")
                            .frame(maxWidth: .infinity)
                            .padding()
                    }
                    .buttonStyle(.borderedProminent)

                    Button(role: .destructive) {
                        runner.abort()
                    } label: {
                        Text("中止")
                            .frame(maxWidth: .infinity)
                            .padding()
                    }
                    .buttonStyle(.bordered)
                }
            }
            if runner.finished || !runner.logLines.isEmpty {
                Button {
                    sharePayload = SharePayload(text: runner.exportText())
                } label: {
                    Label("匯出 / 分享 Log", systemImage: "square.and.arrow.up")
                        .frame(maxWidth: .infinity)
                        .padding()
                }
                .buttonStyle(.bordered)
            }
        }
    }

    private var logPreview: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Session log（最近）").font(.caption.weight(.semibold))
            ForEach(runner.logLines.suffix(12), id: \.self) { line in
                Text(line)
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundStyle(.secondary)
            }
        }
    }

    @MainActor
    private func ensureAncThenStart() async {
        if let step = CarRoadTuningScript.steps.first, step.requiresAncRunning == false {
            // prep 可先不開
        } else if !model.isRunning {
            if !model.safetyConsentAccepted {
                model.showSafetyConsent = true
                return
            }
            do {
                try await engine.start(preferCarAudio: AppController.shared.routeMonitor.linkType.isCarPlay)
            } catch {
                model.phase = .error
                model.lastError = error.localizedDescription
                return
            }
        }
        runner.start()
    }
}

struct SharePayload: Identifiable {
    let id = UUID()
    let text: String
}

struct ActivityView: UIViewControllerRepresentable {
    let activityItems: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: activityItems, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
