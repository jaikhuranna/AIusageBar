import Foundation

/// The one fetcher. Everything else renders what this leaves in `Store`.
/// Each run asks for every provider and both families' pools in parallel and
/// caches them all, like Android's `Sync`. Same cadence as the watch and the
/// Android widgets (`Plan.nextCheck`, the soonest of the providers'), minus
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

    // Actors are reentrant across network awaits. Coalesce concurrent runs into one.
    private var running: Task<Void, Never>?

    /// Every cache is kept warm, so switching is only a redraw. It never waits for a fetch.
    func selectProvider(_ provider: UsageProvider) {
        Store.shared.selectProvider(provider)
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
        case .manual:
            let recent = UsageProvider.allCases.allSatisfy { p in
                let c = store.cache(p)
                return c.lastError == nil && c.fetchedAt.map { now.timeIntervalSince($0) < Sync.manualThrottle } == true
            }
            fetch = !recent
        case .due: fetch = (store.nextFetchAt ?? .distantPast) <= now
        }
        guard fetch else { return }

        var bases = [base]
        if let lan = store.lanUrl, lan != base { bases.append(lan) }
        // Each provider and family has its own cache, so the requests never touch the same keys.
        await withTaskGroup(of: Void.self) { group in
            for provider in UsageProvider.allCases {
                group.addTask { await Sync.fetch(store.cache(provider), bases: bases, token: token, now: now) }
            }
            for family in PoolFamily.allCases {
                group.addTask { await Sync.fetchPool(store.pool(family), bases: bases, token: token, now: now) }
            }
        }

        // Unpaired or re-paired while the requests were out: their answers belong to no one.
        guard store.token == token else { return }
        let caches = UsageProvider.allCases.map(store.cache)
        for c in caches { c.record(backAt: c.snapshot().flatMap { Plan.comeback($0, Date()) }) }
        // Polling can't fix a revoked token; the app asks to pair again.
        store.nextFetchAt = caches.contains { $0.lastError == .unpaired }
            ? .distantFuture
            // Soonest of them all: the card shows every provider, so none may go stale.
            : Date() + caches.map { Plan.nextCheck($0.snapshot(), Date(), failures: $0.failures) }.min()!
    }

    /// Tries the tunnel, then the LAN address; the first one that answers wins.
    private static func fetch(_ cache: ProviderCache, bases: [String], token: String, now: Date) async {
        let etag = cache.snapshotJson != nil ? cache.etag : nil
        var r = Bridge.Usage.failed("no address")
        for b in bases {
            r = await Bridge.usage(base: b, token: token, etag: etag, provider: cache.provider)
            if case .failed = r { continue }
            break
        }
        guard Store.shared.token == token else { return }
        switch r {
        case let .fresh(body, tag):
            if let snap = try? parseSnapshot(body), snap.provider == cache.provider {
                cache.recordFresh(body: body, etag: tag, at: now)
            } else {
                cache.recordFailure(.offline)
            }
        case .notModified: cache.recordFresh(body: nil, etag: nil, at: now)
        case .unauthorized: cache.recordFailure(.unpaired)
        case .failed, .notFound: cache.recordFailure(.offline)
        }
    }

    /// Same addresses as `fetch`. A 404 is an older bridge without `/pool`: the
    /// cache is dropped so everything falls back to the per-provider look.
    private static func fetchPool(_ cache: PoolCache, bases: [String], token: String, now: Date) async {
        let etag = cache.json != nil ? cache.etag : nil
        var r = Bridge.Usage.failed("no address")
        for b in bases {
            r = await Bridge.pool(base: b, token: token, etag: etag, family: cache.family)
            if case .failed = r { continue }
            break
        }
        guard Store.shared.token == token else { return }
        switch r {
        case let .fresh(body, tag):
            if let p = try? parsePool(body), p.family == cache.family {
                cache.recordFresh(body: body, etag: tag, at: now)
            } else {
                cache.recordFailure()
            }
        case .notModified: cache.recordFresh(body: nil, etag: nil, at: now)
        case .notFound: cache.recordMissing()
        // A revoked token shows on the snapshots, which ask to pair again.
        case .unauthorized, .failed: cache.recordFailure()
        }
    }
}
