import Foundation

/// The two limits every account has. Port of Android's `Family.kt`.
enum Limit: CaseIterable {
    case session, week
    var title: String { self == .session ? "Session" : "Weekly" }
}

/// One account of a family, numbered from 1 in the bridge's order. `session`
/// and `week` are already effective, so a reset that passed offline is full.
struct Member: Equatable {
    var number: Int
    var provider: String
    var account: String?
    var session: Effective?
    var week: Effective?
    var sessionLength: TimeInterval = Look.session
    var weekLength: TimeInterval = Look.week
    var error: String? = nil

    /// The email when the bridge knows it, else the provider's own name ("Codex").
    var name: String { account ?? UsageProvider(rawValue: provider)?.title ?? provider }

    func window(_ limit: Limit) -> Effective? { limit == .session ? session : week }
    func length(_ limit: Limit) -> TimeInterval { limit == .session ? sessionLength : weekLength }

    static func of(_ number: Int, provider: String, account: String?, fiveHour: LimitWindow?, sevenDay: LimitWindow?, error: String?, now: Date) -> Member {
        Member(
            number: number, provider: provider, account: account,
            session: fiveHour?.effective(now), week: sevenDay?.effective(now),
            sessionLength: fiveHour?.windowMinutes.map { Double($0) * 60 } ?? Look.session,
            weekLength: sevenDay?.windowMinutes.map { Double($0) * 60 } ?? Look.week,
            error: error
        )
    }
}

/// A family as the app and the card show it: its accounts, how old the numbers
/// are, and the bridge's reason when there are none. Built from `/pool` when the
/// bridge has it (any number of accounts), else from the per-provider snapshots
/// (an older bridge answers 404 to `/pool`).
struct FamilyView: Equatable {
    var family: PoolFamily
    var members: [Member]
    /// Nil when nothing has been fetched yet.
    var fetchedAt: Date?
    var offline: Bool
    var error: String?
    /// `/pool`'s recorded five-hour samples, oldest first; empty from the per-provider fallback.
    var history: [SessionSample] = []

    var known: [Member] { members.filter { $0.session != nil || $0.week != nil } }

    static func of(_ pool: Pool, fetchedAt: Date?, offline: Bool, now: Date) -> FamilyView {
        FamilyView(
            family: pool.family,
            members: pool.members.enumerated().map { i, m in
                Member.of(i + 1, provider: m.provider, account: m.account, fiveHour: m.fiveHour, sevenDay: m.sevenDay, error: m.sourceError, now: now)
            },
            fetchedAt: fetchedAt, offline: offline, error: pool.sourceError, history: pool.history
        )
    }

    /// The fallback: one member per provider of the family that has numbers.
    static func of(_ family: PoolFamily, snapshots: [Snapshot?], fetchedAt: Date?, offline: Bool, now: Date) -> FamilyView {
        let withData = snapshots.compactMap { $0 }.filter { $0.fiveHour != nil || $0.sevenDay != nil }
        return FamilyView(
            family: family,
            members: withData.enumerated().map { i, s in
                Member.of(i + 1, provider: s.provider.rawValue, account: s.account, fiveHour: s.fiveHour, sevenDay: s.sevenDay, error: s.sourceError, now: now)
            },
            fetchedAt: fetchedAt, offline: offline,
            error: withData.isEmpty ? snapshots.lazy.compactMap { $0?.sourceError }.first : nil
        )
    }
}

/// What comes back at the family's next reset of a limit, as a share of the whole family.
struct Comeback: Equatable {
    var gain: Int
    var at: Date
    /// "+28% in 3h 30m".
    func text(_ now: Date) -> String { "+\(gain)% in \(untilText(from: now, to: at))" }
}

/// Spending against the clock: ahead burns faster than the window allows.
enum PaceMark: Equatable {
    case ahead, on, under
    var text: String {
        switch self {
        case .ahead: return "Ahead of pace"
        case .on: return "On pace"
        case .under: return "Under pace"
        }
    }
}

/// One account's slice of a `Meter`: what's left, and how much of its window is still to run. Nil is unknown.
struct Segment: Equatable {
    var number: Int
    var left: Double?
    var timeLeft: Double?
}

/// One limit across a family, the way the app's cards draw it: the share of
/// the whole family left (two accounts at 45% and 82% are 64%), what the next
/// reset brings back, the pace, and one segment per account.
struct Meter: Equatable {
    var left: Int?
    var comeback: Comeback?
    var pace: PaceMark?
    var segments: [Segment]

    var out: Bool { (left ?? 1) <= 0 }

    /// How far off the clock, in points of the family, still counts as on pace.
    static let paceSlack = 5.0

    static func of(_ members: [Member], _ limit: Limit, now: Date) -> Meter {
        let known = members.filter { $0.window(limit) != nil }
        let segments = members.map { m in
            let e = m.window(limit)
            return Segment(number: m.number, left: e.map { Double($0.remaining) / 100 },
                           timeLeft: e?.resetsAt.map { timeLeft(now, $0, m.length(limit)) })
        }
        if known.isEmpty { return Meter(left: nil, comeback: nil, pace: nil, segments: segments) }
        let left = share(Double(known.reduce(0) { $0 + $1.window(limit)!.remaining }), known.count)
        return Meter(left: left, comeback: comeback(known, limit, now: now), pace: left <= 0 ? nil : pace(known, limit, now: now), segments: segments)
    }

    /// The soonest reset that gives anything back, and how much: every account
    /// resetting within a minute of it counts, as a share of `members`.
    static func comeback(_ members: [Member], _ limit: Limit, now: Date) -> Comeback? {
        let due = members.compactMap { m -> Effective? in
            guard let e = m.window(limit), e.remaining < 100, let r = e.resetsAt, r > now else { return nil }
            return e
        }
        guard let first = due.compactMap(\.resetsAt).min() else { return nil }
        let together = due.filter { abs($0.resetsAt!.timeIntervalSince(first)) <= 60 }
        let gain = Double(together.reduce(0) { $0 + (100 - $1.remaining) })
        return Comeback(gain: max(1, share(gain, members.count)), at: first)
    }

    /// Left against time left, summed over the accounts whose window is running:
    /// each window "should" have as much left as it has time left. Nil until the
    /// windows are ten minutes in, when a rate means something.
    static func pace(_ members: [Member], _ limit: Limit, now: Date) -> PaceMark? {
        let running = members.compactMap { m -> Double? in
            guard let e = m.window(limit), let reset = e.resetsAt else { return nil }
            let length = m.length(limit)
            let elapsed = length - reset.timeIntervalSince(now)
            return elapsed < 10 * 60 ? nil : Double(e.remaining) - timeLeft(now, reset, length) * 100
        }
        if running.isEmpty { return nil }
        let ahead = running.reduce(0, +) / Double(running.count)
        if ahead < -paceSlack { return .ahead }
        if ahead > paceSlack { return .under }
        return .on
    }

    /// `sum` percent across `count` accounts, as a percent of all of them.
    private static func share(_ sum: Double, _ count: Int) -> Int { Int((sum / Double(max(count, 1))).rounded()) }

    private static func timeLeft(_ now: Date, _ reset: Date, _ length: TimeInterval) -> Double {
        min(max(reset.timeIntervalSince(now).rounded(.towardZero) / max(length, 1), 0), 1)
    }
}

/// Where each of `count` equal segments sits across `width`, `gap` apart:
/// every account is the same 100%, so every segment is the same width.
func segmentSpans(_ width: Double, _ count: Int, gap: Double) -> [ClosedRange<Double>] {
    guard count > 0 else { return [] }
    let g = count > 1 ? min(gap, width / Double(count - 1) / 2) : 0
    let w = (width - g * Double(count - 1)) / Double(count)
    return (0..<count).map { i in
        let start = Double(i) * (w + g)
        return start...(start + w)
    }
}
