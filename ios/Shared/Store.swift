import Foundation
import Security

/// Everything the app remembers, shared with the widget extension through the
/// App Group. The token is in the Keychain, this device only, so it never
/// leaves the phone in a backup.
///
/// One cache per provider and one per family (`/pool`), many readers: the app
/// and every widget render from here, and only `Sync` fetches. Every cache is
/// kept warm, so switching what the widgets show is only a redraw.
final class Store: @unchecked Sendable {
    static let appGroup = "group.com.jaikhurana.aiusagewidget"
    static let shared = Store()

    fileprivate let d = UserDefaults(suiteName: Store.appGroup) ?? .standard

    /// The provider the widgets show.
    var selected: UsageProvider {
        UsageProvider(rawValue: d.string(forKey: "provider") ?? "claude") ?? .claude
    }

    /// Only the pick changes: every provider's cache stays as it is.
    func selectProvider(_ provider: UsageProvider) {
        d.set(provider.rawValue, forKey: "provider")
    }

    /// The family the card's five-hour graph shows: the last one picked there, Claude at first.
    var chartFamily: PoolFamily {
        get { PoolFamily(rawValue: d.string(forKey: "card_chart") ?? "") ?? .claude }
        set { d.set(newValue.rawValue, forKey: "card_chart") }
    }

    var baseUrl: String? {
        get { d.string(forKey: "base_url") }
        set { d.set(newValue, forKey: "base_url") }
    }
    /// The LAN address pairing happened on, tried when `baseUrl` (the tunnel) fails.
    var lanUrl: String? {
        get { d.string(forKey: "lan_url") }
        set { d.set(newValue, forKey: "lan_url") }
    }
    var host: String? {
        get { d.string(forKey: "host") }
        set { d.set(newValue, forKey: "host") }
    }
    var token: String? {
        get { Keychain.read() }
        set { Keychain.write(newValue) }
    }

    /// When the widgets should next go to the network: the soonest of every
    /// provider's `Plan.nextCheck`, since the card shows them all.
    var nextFetchAt: Date? {
        get { d.object(forKey: "next_fetch_at") as? Date }
        set { d.set(newValue, forKey: "next_fetch_at") }
    }

    var paired: Bool { baseUrl != nil && token != nil }

    /// One provider's cache.
    func cache(_ provider: UsageProvider) -> ProviderCache { ProviderCache(d: d, provider: provider) }
    /// One family's `/pool` cache.
    func pool(_ family: PoolFamily) -> PoolCache { PoolCache(d: d, family: family) }

    /// The widgets' look: the selected provider's own.
    func look(now: Date = Date()) -> Look { look(selected, now: now) }

    func look(_ provider: UsageProvider, now: Date = Date()) -> Look {
        if Fixtures.enabled {
            var look = Look.of(paired: true, snap: Fixtures.snapshot(provider, now: now), fetchedAt: now - 120, lastError: nil, backAt: nil, now: now)
            look.provider = provider
            return look
        }
        let c = cache(provider)
        var look = Look.of(paired: paired, snap: c.snapshot(), fetchedAt: c.fetchedAt, lastError: c.lastError, backAt: c.backAt, now: now)
        look.provider = provider
        return look
    }

    /// A family as the app and the card show it: from its `/pool` when the
    /// bridge serves one, else from its providers' snapshots. Its age is the
    /// oldest of the family's snapshots (`cache_fetched_at`, the numbers' true
    /// age), falling back to when they were fetched.
    func familyView(_ family: PoolFamily, now: Date) -> FamilyView {
        if Fixtures.enabled, let p = Fixtures.pool(family, now: now) {
            return FamilyView.of(p, fetchedAt: now - 120, offline: false, now: now)
        }
        let caches = family.providers.map(cache)
        let snaps = caches.map { $0.snapshot() }
        let ages = zip(caches, snaps).compactMap { c, s in s?.cacheFetchedAt ?? c.fetchedAt }
        let pc = pool(family)
        if let p = pc.pool() {
            return FamilyView.of(p, fetchedAt: ages.min() ?? pc.fetchedAt, offline: pc.offline, now: now)
        }
        return FamilyView.of(family, snapshots: snaps, fetchedAt: ages.min(), offline: caches.contains { $0.lastError == .offline }, now: now)
    }

    /// The pooled ring of the family the widgets show (picking Claude 2 still
    /// pools every Claude account); nil without a pool from this bridge.
    func pooledRing(now: Date = Date()) -> Look? {
        let family = PoolFamily.of(selected)
        let pc = pool(family)
        let caches = family.providers.map(cache)
        let fetched = caches.compactMap { $0.snapshot()?.cacheFetchedAt }.min() ?? pc.fetchedAt
        if Fixtures.enabled { return poolRing(Fixtures.pool(family, now: now), fetchedAt: now - 120, offline: false, backAts: [], now: now) }
        return poolRing(pc.pool(), fetchedAt: fetched, offline: pc.offline, backAts: caches.map(\.backAt), now: now)
    }

    /// Every account's week as a thread, Claude's then Codex's.
    func strands(now: Date = Date()) -> [ThreadStrand] { rings(now: now).flatMap { $0 } }

    /// The same threads, one group per family: the orbit's rings, Claude outside.
    func rings(now: Date = Date()) -> [[ThreadStrand]] {
        PoolFamily.allCases.map { f in
            var v = familyView(f, now: now)
            if !paired && !Fixtures.enabled { v.members = [] }
            return strandsOf([v], now: now)
        }
    }

    /// A family's `/pool` answer as last fetched (or the fixture's).
    func poolAnswer(_ family: PoolFamily, now: Date = Date()) -> Pool? {
        Fixtures.enabled ? Fixtures.pool(family, now: now) : pool(family).pool()
    }

    func clear() {
        d.removePersistentDomain(forName: Store.appGroup)
        token = nil
    }
}

/// One provider's `/usage` cache. Before per-provider caches, Claude's lived
/// under bare keys, so Claude keeps them; the others are suffixed.
struct ProviderCache {
    fileprivate let d: UserDefaults
    let provider: UsageProvider

    private func key(_ k: String) -> String { provider == .claude ? k : "\(k).\(provider.rawValue)" }

    var snapshotJson: String? { d.string(forKey: key("snapshot")) }
    var etag: String? { d.string(forKey: key("etag")) }
    var fetchedAt: Date? { d.object(forKey: key("fetched_at")) as? Date }
    var failures: Int { d.integer(forKey: key("failures")) }
    var lastError: SyncError? { d.string(forKey: key("last_error")).flatMap { SyncError(rawValue: $0) } }
    /// The last comeback time seen while out of quota; the widgets say GO! just after it.
    var backAt: Date? { d.object(forKey: key("back_at")) as? Date }

    func snapshot() -> Snapshot? {
        guard let s = snapshotJson.flatMap({ try? parseSnapshot($0) }), s.provider == provider else { return nil }
        return s
    }

    func recordFresh(body: String?, etag: String?, at: Date) {
        if let body {
            d.set(body, forKey: key("snapshot"))
            d.set(etag, forKey: key("etag"))
        }
        d.set(at, forKey: key("fetched_at"))
        d.set(0, forKey: key("failures"))
        d.removeObject(forKey: key("last_error"))
    }

    func recordFailure(_ error: SyncError) {
        if error == .offline { d.set(failures + 1, forKey: key("failures")) }
        d.set(error.rawValue, forKey: key("last_error"))
    }

    func record(backAt: Date?) {
        if let backAt { d.set(backAt, forKey: key("back_at")) }
    }
}

/// One family's `/pool` answer. A missing pool means the bridge answered 404:
/// an older server, so everything falls back to the per-provider look.
struct PoolCache {
    fileprivate let d: UserDefaults
    let family: PoolFamily

    private func key(_ k: String) -> String { "\(k).\(family.rawValue)" }

    var json: String? { d.string(forKey: key("pool")) }
    var etag: String? { d.string(forKey: key("pool_etag")) }
    var fetchedAt: Date? { d.object(forKey: key("pool_fetched_at")) as? Date }
    var offline: Bool { d.bool(forKey: key("pool_offline")) }

    func pool() -> Pool? {
        guard let p = json.flatMap({ try? parsePool($0) }), p.family == family else { return nil }
        return p
    }

    func recordFresh(body: String?, etag: String?, at: Date) {
        if let body {
            d.set(body, forKey: key("pool"))
            d.set(etag, forKey: key("pool_etag"))
        }
        d.set(at, forKey: key("pool_fetched_at"))
        d.set(false, forKey: key("pool_offline"))
    }

    func recordMissing() {
        d.removeObject(forKey: key("pool"))
        d.removeObject(forKey: key("pool_etag"))
        d.set(false, forKey: key("pool_offline"))
    }

    func recordFailure() { d.set(true, forKey: key("pool_offline")) }
}

/// The device token. After first unlock, so the widgets can fetch while the phone is locked.
private enum Keychain {
    private static let query: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: "aiusage",
        kSecAttrAccount as String: "token",
        kSecAttrAccessGroup as String: Store.appGroup,
    ]

    static func read() -> String? {
        var q = query
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func write(_ value: String?) {
        SecItemDelete(query as CFDictionary)
        guard let value else { return }
        var q = query
        q[kSecValueData as String] = Data(value.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(q as CFDictionary, nil)
    }
}
