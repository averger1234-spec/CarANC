import SwiftUI

/// 對齊 Android「測試平台」精簡版（iOS 本機可調）
struct TestLabView: View {
    @ObservedObject var model: AncAppModel
    @ObservedObject var engine: AncAudioEngine
    @State private var sharePayload: SharePayload?
    @State private var carModel = ""
    @State private var phonePlacement = "floor"
    @State private var scenarioNote = ""
    @State private var subjectiveRumble = 5.0

    var body: some View {
        Form {
            Section("實車紀錄（寫入匯出 Log）") {
                TextField("車型（例 Skoda Octavia）", text: $carModel)
                Picker("手機位置", selection: $phonePlacement) {
                    Text("地板 floor").tag("floor")
                    Text("座椅 seat").tag("seat")
                    Text("杯架 cup").tag("cup")
                    Text("其他 other").tag("other")
                }
                TextField("情境備註", text: $scenarioNote)
                VStack(alignment: .leading) {
                    Text("主觀 rumble \(Int(subjectiveRumble))/10")
                    Slider(value: $subjectiveRumble, in: 0...10, step: 1)
                }
            }

            Section("即時讀數") {
                LabeledContent("狀態", value: model.statusText)
                LabeledContent("輸入 dB", value: String(format: "%.1f", model.rawDb))
                LabeledContent("反噪 dB", value: String(format: "%.1f", model.antiDb))
                LabeledContent("低頻 KPI", value: String(format: "%.2f", model.lowBandRumbleReduction))
                LabeledContent("延遲 ms", value: String(format: "%.0f", model.estimatedLatencyMs))
                LabeledContent("NVH", value: model.nvhFocus.rawValue)
                LabeledContent("車速", value: model.speedText)
                LabeledContent("等級", value: model.tier.displayName)
            }

            Section("快捷") {
                Button("切到測試腳本") { model.selectedTab = 2 }
                Toggle("顯示工程資訊（狀態頁）", isOn: $model.showAdvanced)
            }

            Section("匯出（路測驗證）") {
                Button {
                    sharePayload = SharePayload(text: buildLabLog())
                } label: {
                    Label("匯出完整 Session Log", systemImage: "square.and.arrow.up")
                }
                Text("含 running_snapshot：lowBandRumbleReduction / rawDb / antiDb / speed / IMU / latency / LMS updates。路測後用分享傳到電腦分析。")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            Section("說明") {
                Text("Android 完整 TestLogPanel（mu 滑桿、FDAF 等）在 iOS 為精簡版；路測主流程請用「測試腳本」。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("測試平台")
        .sheet(item: $sharePayload) { payload in
            ActivityView(activityItems: [payload.text])
        }
    }

    private func buildLabLog() -> String {
        let meta = """
        === ROAD META (fill before drive) ===
        time=\(ISO8601DateFormatter().string(from: Date()))
        carModel=\(carModel)
        placement=\(phonePlacement)
        scenario=\(scenarioNote)
        subjectiveRumble=\(Int(subjectiveRumble))/10
        platform=ios
        """
        SessionLogger.shared.event("lab_export", [
            "carModel": carModel,
            "placement": phonePlacement,
            "scenario": scenarioNote,
            "subjectiveRumble": "\(Int(subjectiveRumble))"
        ])
        return meta + "\n" + SessionLogger.shared.exportText()
    }
}
