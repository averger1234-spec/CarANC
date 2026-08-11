import SwiftUI

struct PlanTabView: View {
    @ObservedObject var model: AncAppModel
    @ObservedObject var engine: AncAudioEngine

    var body: some View {
        List {
            Section("訂閱方案（本機測試閘道）") {
                ForEach(SubscriptionPlan.allCases, id: \.rawValue) { plan in
                    Button {
                        model.setPlan(plan)
                    } label: {
                        HStack {
                            VStack(alignment: .leading) {
                                Text(plan.displayName)
                                Text("最高等級：\(plan.maxTier.displayName)")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            if model.plan == plan {
                                Image(systemName: "checkmark.circle.fill")
                                    .foregroundStyle(.blue)
                            }
                        }
                    }
                }
            }

            Section("降噪強度") {
                ForEach(UserTier.allCases) { tier in
                    let locked = tier.rawValue.tierRank > model.plan.maxTier.rawValue.tierRank
                    Button {
                        guard !locked else { return }
                        model.setTier(tier)
                        // 運行中熱切換
                        // processor 在 engine 內；重啟較穩
                    } label: {
                        HStack {
                            Text(tier.displayName)
                            Spacer()
                            if locked {
                                Image(systemName: "lock.fill")
                                    .foregroundStyle(.secondary)
                            } else if model.tier == tier {
                                Image(systemName: "checkmark.circle.fill")
                                    .foregroundStyle(.blue)
                            }
                        }
                    }
                    .foregroundStyle(locked ? .secondary : .primary)
                }
            }

            Section("關於") {
                LabeledContent("平台", value: "iOS（本機 AVAudioEngine）")
                LabeledContent("Android 對應", value: "CarANC KMP shared DSP")
                Link("GitHub 專案", destination: URL(string: "https://github.com/averger1234-spec/CarANC")!)
                Text("CarPlay 音訊路由與完整 KMP framework 連結為下一階段。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("方案")
    }
}

private extension String {
    var tierRank: Int {
        switch self {
        case "LIGHT": return 0
        case "STANDARD": return 1
        case "PRO": return 2
        default: return 0
        }
    }
}
