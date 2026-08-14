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
        .onChange(of: runner.pendingAutoExportText) { newText in
            guard let newText, !newText.isEmpty else { return }
            sharePayload = SharePayload(text: newText)
            runner.pendingAutoExportText = nil
        }
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
                if runner.autoAdvance {
                    Text("全自動：達有效秒／壁鐘即跳下一步 · 不用勾清單、不必按「完成」")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.green)
                } else {
                    Text("模式：手動下一步")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
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
            Text("步驟概覽（1.2.9 P2 plantD + 50Hz + antiE + 強制 focus）：")
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
            Text("按「開始腳本」會自動開降噪；秒數滿自動換步；全部完成自動彈出分享 Log。")
                .font(.caption)
                .foregroundStyle(.green)
            Text("行駛步車速不足會暫停累秒（紅燈不計）。")
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
            // 對齊 Android：checklist 只是提示，不擋自動進階
            if !step.checklist.isEmpty {
                Text("本步觀察重點（僅提示，不用勾）")
                    .font(.caption.weight(.semibold))
                    .padding(.top, 4)
                ForEach(step.checklist, id: \.self) { item in
                    HStack(alignment: .top, spacing: 6) {
                        Text("·").foregroundStyle(.secondary)
                        Text(item).font(.caption).foregroundStyle(.secondary)
                        Spacer()
                    }
                }
            }
            if step.wallClockOnly {
                Text(String(format: "壁鐘 %d / %d 秒（自動跳下一步）", runner.wallSec, step.durationSec))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
            } else {
                Text(String(
                    format: "有效 %d / %d 秒 · 壁鐘 %d / 上限 %d（達標自動跳）",
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
                    Label("開始腳本（自動開降噪）", systemImage: "play.fill")
                        .frame(maxWidth: .infinity)
                        .padding()
                }
                .buttonStyle(.borderedProminent)
            }
            if runner.active {
                VStack(spacing: 8) {
                    Text(runner.autoAdvance
                         ? "請繼續開車；秒數滿會自動進階。下方「略過此步」僅緊急用。"
                         : "請按完成這步。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    HStack {
                        if runner.autoAdvance {
                            Button {
                                runner.completeManually()
                            } label: {
                                Text("略過此步")
                                    .frame(maxWidth: .infinity)
                                    .padding()
                            }
                            .buttonStyle(.bordered)
                        } else {
                            Button {
                                runner.completeManually()
                            } label: {
                                Text("完成這步")
                                    .frame(maxWidth: .infinity)
                                    .padding()
                            }
                            .buttonStyle(.borderedProminent)
                        }

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
            }
            if runner.finished || !runner.logLines.isEmpty {
                Button {
                    sharePayload = SharePayload(text: runner.exportText())
                } label: {
                    Label(runner.finished ? "再次匯出 / 分享 Log" : "匯出 / 分享 Log", systemImage: "square.and.arrow.up")
                        .frame(maxWidth: .infinity)
                        .padding()
                }
                .buttonStyle(.bordered)
                if runner.finished {
                    Text("結束時已自動彈出分享；若關掉可按上方再次匯出。")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
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

    /// 一律先開降噪再跑腳本（全自動路測）
    @MainActor
    private func ensureAncThenStart() async {
        if !model.safetyConsentAccepted {
            model.showSafetyConsent = true
            return
        }
        if !model.isRunning {
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
