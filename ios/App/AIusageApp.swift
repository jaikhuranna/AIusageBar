import SwiftUI
import WidgetKit

@main
struct AIusageApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
                // A widget tap: the card is what's on screen, so just refresh it.
                .onOpenURL { _ in Task { await model.refresh(.force) } }
        }
    }
}

/// The app's view of `Store`. It re-reads after every write it makes; the
/// widget extension's writes are picked up on the next refresh or minute tick.
@MainActor
final class AppModel: ObservableObject {
    let store = Store.shared
    @Published private(set) var busy = false
    /// Bumped on every change, so views re-read `store`.
    @Published private(set) var version = 0

    func look(at now: Date) -> Look { store.look(now: now) }

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

    func selectProvider(_ provider: UsageProvider) async {
        guard !busy, provider != store.provider else { return }
        busy = true
        await Sync.shared.selectProvider(provider)
        busy = false
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
