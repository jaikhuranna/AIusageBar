import Foundation

/// A provider family: every account whose limits pool together. Claude is the
/// hub's 1..N accounts; Codex is a pool of one. `GET /pool?family=` answers for
/// one family. Port of Android's `Pool.kt`.
enum PoolFamily: String, CaseIterable {
    case claude, codex

    var title: String { self == .claude ? "Claude" : "Codex" }
    var providers: [UsageProvider] { self == .claude ? [.claude, .claude2] : [.codex] }
    /// The pool is drawn in the family's first provider's colour.
    var accentProvider: UsageProvider { providers[0] }

    static func of(_ provider: UsageProvider) -> PoolFamily { allCases.first { $0.providers.contains(provider) }! }
}

struct PoolMember: Equatable {
    var provider: String
    var account: String?
    var fiveHour: LimitWindow?
    var sevenDay: LimitWindow?
    var sourceError: String?

    /// What this account can spend right now: the tighter of its two windows. Nil with no data.
    func effective(_ now: Date) -> Int? {
        if fiveHour == nil && sevenDay == nil { return nil }
        return min(fiveHour?.effective(now).remaining ?? 100, sevenDay?.effective(now).remaining ?? 100)
    }

    /// When an exhausted account can spend again: the weekly reset if that's out, else the 5h one.
    func comeback(_ now: Date) -> Date? {
        if let week = sevenDay?.effective(now), week.remaining <= 0 { return week.resetsAt }
        if let session = fiveHour?.effective(now), session.remaining <= 0 { return session.resetsAt }
        return nil
    }
}

/// GET /pool. The wire contract is the Endpoints table in AIusageBar's README.
/// Of the history only the five-hour numbers are parsed (the card's session
/// graph); the projection and burn figures aren't drawn, so they aren't parsed.
struct Pool: Equatable {
    var family: PoolFamily
    var accounts: Int
    var capacity: Int
    /// Σ weekly left.
    var remaining: Double
    var fiveHourRemaining: Double
    /// Σ per account of the tighter window: what can be spent right now.
    var effectiveRemaining: Double
    var members: [PoolMember]
    var generatedAt: Date?
    var sourceError: String?
    /// Oldest first.
    var history: [SessionSample] = []
    /// How fast Σ weekly left dropped over the last 3 hours, in account-percent
    /// per hour. Nil while there's too little history.
    var burnPctPerHour: Double? = nil
}

struct PoolParseError: Error {}

func parsePool(_ json: String) throws -> Pool {
    guard let o = try JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any] else { throw PoolParseError() }
    func num(_ v: Any?) -> Double? { (v as? NSNumber).map(\.doubleValue) }
    func str(_ v: Any?) -> String? { (v as? String).flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 } }
    let family = PoolFamily(rawValue: o["family"] as? String ?? "") ?? .claude
    let accounts = max(num(o["accounts"]).map { Int($0) } ?? 0, 0)
    let members = (o["members"] as? [[String: Any]] ?? []).map { m in
        PoolMember(
            provider: m["provider"] as? String ?? "",
            account: str(m["account"]),
            fiveHour: (m["five_hour"] as? [String: Any]).map(parseWindowObject),
            sevenDay: (m["seven_day"] as? [String: Any]).map(parseWindowObject),
            sourceError: str(m["source_error"])
        )
    }
    return Pool(
        family: family,
        accounts: accounts,
        capacity: max(num(o["capacity_pct"]).map { Int($0) } ?? accounts * 100, 0),
        remaining: num(o["remaining_pct"]) ?? 0,
        fiveHourRemaining: num(o["five_hour_remaining_pct"]) ?? 0,
        effectiveRemaining: num(o["effective_remaining_pct"]) ?? 0,
        members: members,
        generatedAt: parseInstant(o["generated_at"] as? String),
        sourceError: str(o["source_error"]),
        history: parseHistory(o["history"] as? [[String: Any]] ?? []),
        burnPctPerHour: num(o["burn_pct_per_hour"])
    )
}

/// One window object as `/usage` and `/pool` write it.
func parseWindowObject(_ o: [String: Any]) -> LimitWindow {
    let util = (o["utilization"] as? NSNumber).map { Int($0.doubleValue) } ?? 0
    return LimitWindow(
        utilization: util,
        remaining: (o["remaining"] as? NSNumber).map { Int($0.doubleValue) } ?? max(100 - util, 0),
        resetsAt: parseInstant(o["resets_at"] as? String),
        windowMinutes: (o["window_minutes"] as? NSNumber).map(\.intValue).flatMap { $0 > 0 ? $0 : nil }
    )
}

/// `history[]`'s five-hour numbers. A sample without a time is skipped; older bridges send no per-account maps.
private func parseHistory(_ a: [[String: Any]]) -> [SessionSample] {
    a.compactMap { h -> SessionSample? in
        guard let t = (h["t"] as? NSNumber)?.int64Value, t > 0 else { return nil }
        let left = (h["five_hour_remaining"] as? [String: Any] ?? [:]).compactMapValues { ($0 as? NSNumber)?.doubleValue }
        let resets = (h["five_hour_resets_at"] as? [String: Any] ?? [:]).compactMapValues { v -> Date? in
            guard let s = (v as? NSNumber)?.int64Value, s > 0 else { return nil }
            return Date(timeIntervalSince1970: TimeInterval(s))
        }
        return SessionSample(t: Date(timeIntervalSince1970: TimeInterval(t)),
                             pooled: (h["five_hour_remaining_pct"] as? NSNumber)?.doubleValue ?? 0,
                             left: left, resetsAt: resets)
    }.sorted { $0.t < $1.t }
}

/// The pooled ring for a whole family, in the original ring's terms: the outer
/// ring is what every account together can spend right now (each account's
/// tighter window, summed), the pie the pooled week, both as a share of the
/// family's capacity. With one account that is the account's own ring. Pure,
/// like `Look.of`, whose modes it reuses:
///
/// - every account's week out → weekOut, counting down to the first weekly reset;
/// - nothing spendable anywhere → sessionOut, counting down to the first comeback;
/// - a family account came back within the hour (`backAts`) and the pool is
///   at least half full → go.
///
/// Nil when there is no pool to draw (an older bridge, or none fetched yet):
/// the widget then draws the selected provider's own ring, as before `/pool`.
func poolRing(_ pool: Pool?, fetchedAt: Date?, offline: Bool, backAts: [Date?], now: Date) -> Look? {
    guard let pool else { return nil }
    let provider = pool.family.accentProvider
    let waiting = Look(mode: .waiting, now: now, session: nil, week: nil, fetchedAt: fetchedAt, offline: offline, provider: provider)
    if pool.accounts == 0 { return waiting }
    let known = pool.members.filter { $0.fiveHour != nil || $0.sevenDay != nil }
    if known.isEmpty && !pool.members.isEmpty { return waiting }
    let capacity = Double(max(known.isEmpty ? pool.capacity : known.count * 100, 1))

    func weekOf(_ m: PoolMember) -> Effective { (m.sevenDay ?? m.fiveHour)!.effective(now) }
    let spendable = known.isEmpty ? pool.effectiveRemaining : Double(known.reduce(0) { $0 + ($1.effective(now) ?? 0) })
    let week = known.isEmpty ? pool.remaining : Double(known.reduce(0) { $0 + weekOf($1).remaining })
    func share(_ v: Double) -> Int { min(max(Int((v * 100 / capacity).rounded()), 0), 100) }
    // Something left must never round to an empty ring, nor a nearly empty one to a full one.
    func visible(_ v: Double) -> Int {
        let s = share(v)
        if v > 0 && s == 0 { return 1 }
        if v < capacity && s == 100 { return 99 }
        return s
    }

    let weekOut = !known.isEmpty && known.allSatisfy { weekOf($0).remaining <= 0 }
    let firstWeekBack = known.compactMap { $0.sevenDay?.effective(now).resetsAt }.min()
    let firstBack = known.compactMap { $0.comeback(now) }.min()
    let sessionLength = known.lazy.compactMap { $0.fiveHour?.windowMinutes }.first.map { Double($0) * 60 } ?? Look.session
    let weekLength = known.lazy.compactMap { $0.sevenDay?.windowMinutes }.first.map { Double($0) * 60 } ?? Look.week

    let session = Effective(remaining: visible(spendable), resetsAt: spendable <= 0 ? firstBack : nil, unconfirmedReset: false)
    let weekly = Effective(remaining: visible(week), resetsAt: firstWeekBack, unconfirmedReset: false)
    let cameBack = backAts.contains { b in b.map { now >= $0 && now < $0 + Look.goFor } ?? false }
    let mode: Mode
    if weekOut { mode = .weekOut }
    else if spendable <= 0 { mode = .sessionOut }
    else if cameBack && session.remaining >= Look.goWhileLeft { mode = .go }
    else { mode = .normal }
    return Look(mode: mode, now: now, session: session, week: weekly, fetchedAt: fetchedAt, offline: offline,
                provider: provider, sessionLength: sessionLength, weekLength: weekLength)
}
