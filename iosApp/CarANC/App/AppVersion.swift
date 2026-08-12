import Foundation

/// iOS 版號（對齊 Android 主畫面 `v{VERSION_NAME}` 顯示習慣）。
/// - Marketing：`CFBundleShortVersionString` / Xcode `MARKETING_VERSION`
/// - Build：`CFBundleVersion` / `CURRENT_PROJECT_VERSION`（每次可路測包 +1）
/// - Android 另用根目錄 `version.properties`；跨平台功能包兩邊應同 `VERSION_NAME`，平台專屬功能可 iOS build 超前。
enum AppVersion {
    static var marketing: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
    }

    static var build: String {
        Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"
    }

    /// 例：`v1.2.0 (13)`
    static var display: String {
        "v\(marketing) (\(build))"
    }

    /// 例：`v1.2.0`
    static var shortDisplay: String {
        "v\(marketing)"
    }

    static var platformLabel: String { "iOS" }
}
