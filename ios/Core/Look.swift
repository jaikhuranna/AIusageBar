import Foundation

/// What every widget is showing. Priority runs top to bottom.
enum Mode {
    /// No token yet: tap to pair.
    case unpaired
    /// Paired, but no snapshot has arrived.
    case waiting
    /// Weekly limit hit: grey, struck through, until the weekly reset.
    case weekOut
    /// 5h limit hit: the yellow dotted timer counts down to the session reset.
    case sessionOut
    /// A limit just came back.
    case go
    case normal
}

/// Why the last fetch didn't land.
enum SyncError: String {
    case offline
    /// The bridge answered 401: the token was revoked. Pair again.
    case unpaired
}

/// Everything a renderer needs, decided once. Pure, so the rules are unit-tested
/// and every widget agrees on them.
struct Look: Equatable {
    var mode: Mode
    var now: Date
    var session: Effective?
    var week: Effective?
    /// The last good fetch; nil if there never was one.
    var fetchedAt: Date?
    var offline: Bool
    var provider: UsageProvider = .claude
    var sessionLength: TimeInterval = Look.session
    var weekLength: TimeInterval = Look.week

    static let session: TimeInterval = 5 * 3600
    static let week: TimeInterval = 7 * 86400
    /// The background cadence tops out at 30 min, so an hour means two missed checks.
    static let staleAfter: TimeInterval = 3600
    /// How long "GO!" stays up after a comeback.
    static let goFor: TimeInterval = 3600
    /// ...or until the session is half used again, when the numbers matter more.
    static let goWhileLeft = 50

    /// Old enough that the numbers shouldn't be trusted at full brightness. An
    /// exhausted limit isn't polled until its reset (nothing can change), so it
    /// only goes stale by being offline.
    var stale: Bool {
        switch mode {
        case .unpaired: return false
        case .weekOut, .sessionOut: return offline
        default: return offline || now.timeIntervalSince(fetchedAt ?? .distantPast) > Look.staleAfter
        }
    }

    var sessionLabel: String { durationLabel(sessionLength) }
    var weekLabel: String { durationLabel(weekLength) }
    private func durationLabel(_ length: TimeInterval) -> String {
        let minutes = Int(length / 60)
        if minutes % 1440 == 0 { return "\(minutes / 1440)D" }
        if minutes % 60 == 0 { return "\(minutes / 60)H" }
        return "\(minutes)M"
    }

    var sessionLeft: Int { session?.remaining ?? 0 }
    var weekLeft: Int { week?.remaining ?? 0 }

    /// What the countdown in sessionOut / weekOut is counting to.
    var countdownTo: Date? {
        switch mode {
        case .sessionOut: return session?.resetsAt
        case .weekOut: return week?.resetsAt
        default: return nil
        }
    }

    /// True while a countdown is on screen, so the widgets redraw every minute.
    var ticking: Bool { countdownTo != nil }

    /// How much of the 5h timer is still to run, 1 → 0. Display only: the reset
    /// time itself always comes from `resets_at`.
    func sessionTimerFraction() -> Double {
        guard let at = session?.resetsAt else { return 0 }
        return min(max(at.timeIntervalSince(now) / sessionLength, 0), 1)
    }

    static func of(
        paired: Bool,
        snap: Snapshot?,
        fetchedAt: Date?,
        lastError: SyncError?,
        backAt: Date?,
        now: Date
    ) -> Look {
        let session = snap?.fiveHour?.effective(now)
        let week = snap?.sevenDay?.effective(now)
        let mode: Mode
        if !paired || lastError == .unpaired {
            mode = .unpaired
        } else if snap == nil || (session == nil && week == nil) {
            mode = .waiting
        } else if let week, week.remaining <= 0 {
            mode = .weekOut
        } else if let session, session.remaining <= 0 {
            mode = .sessionOut
        } else if let back = backAt, now >= back, now < back + goFor, (session?.remaining ?? 100) >= goWhileLeft {
            mode = .go
        } else {
            mode = .normal
        }
        return Look(mode: mode, now: now, session: session, week: week, fetchedAt: snap?.cacheFetchedAt ?? fetchedAt, offline: lastError == .offline || snap?.sourceError != nil,
                    provider: snap?.provider ?? .claude,
                    sessionLength: snap?.fiveHour?.windowMinutes.map { Double($0) * 60 } ?? Look.session,
                    weekLength: snap?.sevenDay?.windowMinutes.map { Double($0) * 60 } ?? Look.week)
    }

    /// For VoiceOver.
    var summary: String {
        let back = countdownTo.map { ", back in \(untilText(from: now, to: $0))" } ?? ""
        switch mode {
        case .unpaired: return "\(provider.title) usage: not paired. Tap to set up."
        case .waiting: return "\(provider.title) usage: waiting for the first reading."
        case .normal:
            let sessionText = session == nil ? "primary allowance not reported" : "\(sessionLeft)% of the \(sessionLabel) allowance left"
            let weekText = week == nil ? "secondary allowance not reported" : "\(weekLeft)% of the \(weekLabel) allowance left"
            return "\(provider.title): \(sessionText), \(weekText). Tap for details."
        case .sessionOut: return "\(provider.title) \(sessionLabel) limit reached" + back
        case .weekOut: return "\(provider.title) \(weekLabel) limit reached" + back
        case .go: return "\(provider.title) is back."
        }
    }

    /// One of each state, with plausible numbers: the widget gallery and placeholders.
    static func demos(_ now: Date) -> [Look] {
        func e(_ rem: Int, _ inMin: Double?) -> Effective {
            Effective(remaining: rem, resetsAt: inMin.map { now + $0 * 60 }, unconfirmedReset: false)
        }
        let fetched = now - 4 * 60
        return [
            Look(mode: .normal, now: now, session: e(62, 131), week: e(71, 3 * 1440 + 200), fetchedAt: fetched, offline: false),
            Look(mode: .normal, now: now, session: e(18, 170), week: e(44, 4000), fetchedAt: fetched, offline: false),
            Look(mode: .sessionOut, now: now, session: e(0, 101), week: e(38, 3000), fetchedAt: fetched, offline: false),
            Look(mode: .weekOut, now: now, session: e(40, 60), week: e(0, 2 * 1440 + 300), fetchedAt: fetched, offline: false),
            Look(mode: .go, now: now, session: e(100, nil), week: e(38, 3000), fetchedAt: fetched, offline: false),
            Look(mode: .normal, now: now, session: e(62, 131), week: e(71, 4000), fetchedAt: now - 5 * 3600, offline: true),
            Look(mode: .unpaired, now: now, session: nil, week: nil, fetchedAt: nil, offline: false),
        ]
    }
}

/// Will this window last until it resets, at the rate it has been used so far?
/// An estimate for display; the window's start is inferred from its length.
enum Pace: Equatable {
    case unused
    case lasts
    case runsOut(Date)
}

func pace(_ e: Effective?, length: TimeInterval, now: Date) -> Pace? {
    guard let e, let reset = e.resetsAt, e.remaining > 0 else { return nil }
    let left = reset.timeIntervalSince(now).rounded(.towardZero)
    let elapsed = length - left
    let used = 100 - e.remaining
    if used <= 0 { return .unused }
    // Too early in the window for a rate to mean anything.
    if elapsed < 10 * 60 { return nil }
    let toEmpty = Double(e.remaining) * elapsed / Double(used)
    return toEmpty >= left ? .lasts : .runsOut(now + toEmpty.rounded(.towardZero))
}
