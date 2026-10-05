import Foundation
import Security

/// Everything the app remembers, shared with the widget extension through the
/// App Group. The token is in the Keychain, this device only, so it never
/// leaves the phone in a backup.
///
/// One cache, many readers: the app and every widget render from here, and
/// only `Sync` fetches.
final class Store: @unchecked Sendable {
    static let appGroup = "group.com.jaikhurana.aiusagewidget"
    static let shared = Store()

    private let d = UserDefaults(suiteName: Store.appGroup) ?? .standard

    var provider: UsageProvider {
        UsageProvider(rawValue: d.string(forKey: "provider") ?? "claude") ?? .claude
    }

    private func cacheKey(_ key: String, for provider: UsageProvider) -> String {
        provider == .claude ? key : "\(key).\(provider.rawValue)"
    }
    private func cacheKey(_ key: String) -> String { cacheKey(key, for: provider) }

    // Requests write to a captured provider even if another process switches selection.
    func selectProvider(_ provider: UsageProvider) {
        d.set(provider.rawValue, forKey: "provider")
        for key in ["snapshot", "etag", "fetched_at", "failures", "last_error", "back_at", "next_fetch_at"] {
            d.removeObject(forKey: cacheKey(key, for: provider))
        }
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

    var snapshotJson: String? {
        get { d.string(forKey: cacheKey("snapshot")) }
        set { d.set(newValue, forKey: cacheKey("snapshot")) }
    }
    var etag: String? {
        get { d.string(forKey: cacheKey("etag")) }
        set { d.set(newValue, forKey: cacheKey("etag")) }
    }
    var fetchedAt: Date? {
        get { d.object(forKey: cacheKey("fetched_at")) as? Date }
        set { d.set(newValue, forKey: cacheKey("fetched_at")) }
    }
    var failures: Int {
        get { d.integer(forKey: cacheKey("failures")) }
        set { d.set(newValue, forKey: cacheKey("failures")) }
    }
    var lastError: SyncError? {
        get { d.string(forKey: cacheKey("last_error")).flatMap { SyncError(rawValue: $0) } }
        set { d.set(newValue?.rawValue, forKey: cacheKey("last_error")) }
    }
    /// The last comeback time seen while out of quota; the widgets say GO! just after it.
    var backAt: Date? {
        get { d.object(forKey: cacheKey("back_at")) as? Date }
        set { d.set(newValue, forKey: cacheKey("back_at")) }
    }
    /// When the widgets should next go to the network (`Plan.nextCheck`).
    var nextFetchAt: Date? {
        get { d.object(forKey: cacheKey("next_fetch_at")) as? Date }
        set { d.set(newValue, forKey: cacheKey("next_fetch_at")) }
    }

    func recordFresh(body: String?, etag: String?, at: Date, for provider: UsageProvider) {
        if let body {
            d.set(body, forKey: cacheKey("snapshot", for: provider))
            d.set(etag, forKey: cacheKey("etag", for: provider))
        }
        d.set(at, forKey: cacheKey("fetched_at", for: provider))
        d.set(0, forKey: cacheKey("failures", for: provider))
        d.removeObject(forKey: cacheKey("last_error", for: provider))
    }

    func recordFailure(_ error: SyncError, for provider: UsageProvider) {
        let key = cacheKey("failures", for: provider)
        if error == .offline { d.set(d.integer(forKey: key) + 1, forKey: key) }
        d.set(error.rawValue, forKey: cacheKey("last_error", for: provider))
    }

    func schedule(backAt: Date?, nextFetchAt: Date, for provider: UsageProvider) {
        if let backAt { d.set(backAt, forKey: cacheKey("back_at", for: provider)) }
        d.set(nextFetchAt, forKey: cacheKey("next_fetch_at", for: provider))
    }

    var paired: Bool { baseUrl != nil && token != nil }

    func snapshot() -> Snapshot? {
        guard let snapshot = snapshotJson.flatMap({ try? parseSnapshot($0) }), snapshot.provider == provider else { return nil }
        return snapshot
    }

    func look(now: Date = Date()) -> Look {
        var look = Look.of(paired: paired, snap: snapshot(), fetchedAt: fetchedAt, lastError: lastError, backAt: backAt, now: now)
        look.provider = provider
        return look
    }

    func clear() {
        d.removePersistentDomain(forName: Store.appGroup)
        token = nil
    }
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
