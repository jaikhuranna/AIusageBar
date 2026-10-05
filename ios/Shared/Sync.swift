import Foundation

/// The one fetcher. Everything else renders what this leaves in `Store`.
/// Same cadence as the watch and the Android widgets (`Plan.nextCheck`), minus
/// the alerts: the bridge's events are the watch's job.
///
/// It runs in two processes: the widget extension (on each timeline reload)
/// and the app (on open and on Refresh). The actor serialises runs within one.
actor Sync {
    static let shared = Sync()

    enum Reason {
        /// A widget timeline reload: fetch only once `Store.nextFetchAt` has come.
        case due
        /// Opening the app: fetch unless there was a good fetch in the last minute.
        case manual
        /// Refresh, or a widget tap: always fetch.
        case force
    }

    private static let manualThrottle: TimeInterval = 60

    // Actors are reentrant across network awaits. Coalesce work and discard
    // replies after a provider/account switch instead of mixing cached data.
    private var running: Task<Void, Never>?

    func selectProvider(_ provider: UsageProvider) async {
        Store.shared.selectProvider(provider)
        if let running { await running.value }
        await run(.force)
    }

    func run(_ reason: Reason) async {
        if let running {
            await running.value
            return
        }
        let task = Task {
            await perform(reason)
            running = nil
        }
        running = task
        await task.value
    }

    private func perform(_ reason: Reason) async {
        let store = Store.shared
        guard let base = store.baseUrl, let token = store.token else { return }

        let now = Date()
        let fetch: Bool
        switch reason {
        case .force: fetch = true
        case .manual: fetch = !(store.lastError == nil && store.fetchedAt.map { now.timeIntervalSince($0) < Sync.manualThrottle } == true)
        case .due: fetch = (store.nextFetchAt ?? .distantPast) <= now
        }
        guard fetch else { return }

        var bases = [base]
        if let lan = store.lanUrl, lan != base { bases.append(lan) }
        let provider = store.provider
        await self.fetch(store, provider: provider, bases: bases, token: token, now: now)

        guard store.provider == provider, store.token == token else { return }
        let snap = store.snapshot()
        // Polling can't fix a revoked token; the app asks to pair again.
        let next = store.lastError == .unpaired
            ? Date.distantFuture
            : Date() + Plan.nextCheck(snap, Date(), failures: store.failures)
        store.schedule(backAt: snap.flatMap { Plan.comeback($0, Date()) }, nextFetchAt: next, for: provider)
    }

    /// Tries the tunnel, then the LAN address; the first one that answers wins.
    private func fetch(_ store: Store, provider: UsageProvider, bases: [String], token: String, now: Date) async {
        let etag = store.snapshotJson != nil ? store.etag : nil
        var r = Bridge.Usage.failed("no address")
        for b in bases {
            r = await Bridge.usage(base: b, token: token, etag: etag, provider: provider)
            if case .failed = r { continue }
            break
        }
        guard store.provider == provider, store.token == token else { return }
        switch r {
        case let .fresh(body, tag):
            if let snap = try? parseSnapshot(body), snap.provider == provider {
                store.recordFresh(body: body, etag: tag, at: now, for: provider)
            } else {
                store.recordFailure(.offline, for: provider)
            }
        case .notModified: store.recordFresh(body: nil, etag: nil, at: now, for: provider)
        case .unauthorized: store.recordFailure(.unpaired, for: provider)
        case .failed: store.recordFailure(.offline, for: provider)
        }
    }

}
