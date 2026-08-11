import SwiftUI

struct ContentView: View {
    @StateObject private var model: AncAppModel
    @StateObject private var engine: AncAudioEngine

    init() {
        let shared = AncAppModel()
        _model = StateObject(wrappedValue: shared)
        _engine = StateObject(wrappedValue: AncAudioEngine(model: shared))
    }

    var body: some View {
        TabView(selection: $model.selectedTab) {
            NavigationStack {
                StatusTabView(model: model, engine: engine)
                    .navigationTitle("CarANC")
            }
            .tabItem { Label("狀態", systemImage: "house.fill") }
            .tag(0)

            NavigationStack {
                PlanTabView(model: model, engine: engine)
            }
            .tabItem { Label("方案", systemImage: "cart.fill") }
            .tag(1)

            NavigationStack {
                GuidedTestView(model: model, engine: engine)
            }
            .tabItem { Label("測試腳本", systemImage: "list.bullet.clipboard.fill") }
            .tag(2)

            NavigationStack {
                TestLabView(model: model, engine: engine)
            }
            .tabItem { Label("測試平台", systemImage: "wrench.and.screwdriver.fill") }
            .tag(3)
        }
        .sheet(isPresented: $model.showSafetyConsent) {
            SafetyConsentView(model: model)
                .interactiveDismissDisabled(true)
        }
    }
}

#Preview {
    ContentView()
}
