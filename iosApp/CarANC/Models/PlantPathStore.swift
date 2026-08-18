import Foundation

/// 對齊 Android `PlantPathStore`（1.2.9+）：profile+route 持久化 plant D 與 boom 極性。
enum PlantPathStore {
    struct Snapshot: Codable, Equatable {
        var profileId: String
        var routeLabel: String
        var electricalDelaySamples: Int
        var probeCorrMs: Float
        var cabinAcousticDelaySamples: Int
        var updatedEpochMs: Int64
        var boomPolarity: Float
    }

    private static let prefsKey = "plant_path_store_v1"
    private static let defaults = UserDefaults.standard

    static func routeLabel(carPlay: Bool, wireless: Bool) -> String {
        if carPlay {
            return wireless ? "carplay_wireless" : "carplay_wired"
        }
        return "local"
    }

    static func save(_ snap: Snapshot) {
        var all = loadAll()
        all[key(snap.profileId, snap.routeLabel)] = snap
        if let data = try? JSONEncoder().encode(all) {
            defaults.set(data, forKey: prefsKey)
        }
    }

    static func load(profileId: String, routeLabel: String) -> Snapshot? {
        loadAll()[key(profileId, routeLabel)]
    }

    static func loadBest(profileId: String, routeLabel: String) -> Snapshot? {
        if let exact = load(profileId: profileId, routeLabel: routeLabel) { return exact }
        return loadAll().values
            .filter { $0.profileId == profileId }
            .max(by: { $0.updatedEpochMs < $1.updatedEpochMs })
    }

    static func persistPolarityWinner(
        winner: Float,
        profileId: String,
        routeLabel: String,
        electricalDelaySamples: Int
    ) {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        if var prev = loadBest(profileId: profileId, routeLabel: routeLabel) {
            prev.boomPolarity = winner
            prev.updatedEpochMs = now
            if electricalDelaySamples > 0 {
                prev.electricalDelaySamples = electricalDelaySamples
            }
            save(prev)
        } else {
            save(Snapshot(
                profileId: profileId,
                routeLabel: routeLabel,
                electricalDelaySamples: max(electricalDelaySamples, 1),
                probeCorrMs: 0,
                cabinAcousticDelaySamples: 0,
                updatedEpochMs: now,
                boomPolarity: winner
            ))
        }
    }

    private static func key(_ profileId: String, _ routeLabel: String) -> String {
        let r = routeLabel
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .replacingOccurrences(of: "[^a-z0-9_]+", with: "_", options: .regularExpression)
        let clipped = String(r.prefix(48))
        return "plant_\(profileId)_\(clipped.isEmpty ? "default" : clipped)"
    }

    private static func loadAll() -> [String: Snapshot] {
        guard let data = defaults.data(forKey: prefsKey),
              let decoded = try? JSONDecoder().decode([String: Snapshot].self, from: data) else {
            return [:]
        }
        return decoded
    }
}
