import SwiftUI
import WidgetKit

@main
struct AIusageApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
                // A widget tap opens the card over the app and refreshes it, like
                // Android's tap card; unpaired, it opens pairing.
                .onOpenURL { url in
                    if url.host == "card", model.store.paired { model.showCard = true }
                    Task { await model.refresh(.force) }
                }
        }
    }
}

/// The app's view of `Store`. It re-reads after every write it makes; the
/// widget extension's writes are picked up on the next refresh or minute tick.
@MainActor
final class AppModel: ObservableObject {
    let store = Store.shared
    @Published private(set) var busy = false
    /// The widget tap card, shown as a sheet over the app.
    @Published var showCard = Fixtures.enabled && ProcessInfo.processInfo.arguments.contains("-card")
    /// Bumped on every change, so views re-read `store`.
    @Published private(set) var version = 0

    func look(at now: Date) -> Look { store.look(now: now) }

    /// Claude then Codex, as the app and the card draw them.
    func views(at now: Date) -> [FamilyView] { PoolFamily.allCases.map { store.familyView($0, now: now) } }

    func refresh(_ reason: Sync.Reason) async {
        if busy { return }
        busy = true
        await Sync.shared.run(reason)
        busy = false
        changed()
    }

    func pair(base: String, code: String) async throws {
        let p = try await Bridge.pair(base: base, code: code, name: "\(Device.model) widgets")
        store.clear()
        // Use the tunnel from now on, if the bridge has one.
        store.baseUrl = p.publicUrl ?? base
        if let pub = p.publicUrl, pub != base { store.lanUrl = base }
        store.host = p.host
        store.token = p.token
        changed()
        await refresh(.force)
    }

    /// Every cache is warm, so this is only a redraw: never gated on a refresh.
    func selectProvider(_ provider: UsageProvider) async {
        guard provider != store.selected else { return }
        await Sync.shared.selectProvider(provider)
        changed()
    }

    func pickChart(_ family: PoolFamily) {
        store.chartFamily = family
        changed()
    }

    func unpair() {
        store.clear()
        changed()
    }

    private func changed() {
        version += 1
        WidgetCenter.shared.reloadAllTimelines()
    }
}

enum Device {
    /// The hardware model ("iPhone17,1"): what the bridge lists this phone as,
    /// like Android's Build.MODEL. The user-set name needs an entitlement.
    static var model: String {
        var u = utsname()
        uname(&u)
        return withUnsafeBytes(of: &u.machine) { raw in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
    }
}
