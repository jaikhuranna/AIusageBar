import Foundation

/// Made-up pools that follow the `/pool` contract, for judging the app and the
/// card without live accounts, like Android's debug `Fixtures`. Three Claude
/// accounts (one out for the session) and three Codex ones (the first out for the week). Only when the
/// app is launched with `-fixture`, e.g.
///
///     xcrun simctl launch booted com.jaikhurana.aiusagewidget -fixture
enum Fixtures {
    static let enabled: Bool = {
        #if DEBUG
        // Debug builds hand the switch to the widget extension through the App
        // Group, so the widgets can be judged with the same made-up accounts.
        let d = UserDefaults(suiteName: Store.appGroup)
        if Bundle.main.bundlePath.hasSuffix(".app") {
            let on = ProcessInfo.processInfo.arguments.contains("-fixture")
            d?.set(on, forKey: "debug_fixture")
            return on
        }
        return d?.bool(forKey: "debug_fixture") ?? false
        #else
        return ProcessInfo.processInfo.arguments.contains("-fixture")
        #endif
    }()

    /// A provider's `/usage` as the fixture's pool member would answer it.
    static func snapshot(_ provider: UsageProvider, now: Date) -> Snapshot? {
        guard let m = pool(PoolFamily.of(provider), now: now)?.members.first(where: { $0.provider == provider.rawValue }) else { return nil }
        return Snapshot(fiveHour: m.fiveHour, sevenDay: m.sevenDay, host: "fixture", cacheFetchedAt: now - 120,
                        provider: provider, costAvailable: provider == .claude, account: m.account)
    }

    /// `ended` is how much each earlier five-hour window had left when it reset, newest first.
    private struct Account {
        let email: String?
        let session: Int
        let week: Int
        let sessionIn: Double
        let weekIn: Double
        var ended: [Int] = []
    }

    static func pool(_ family: PoolFamily, now: Date) -> Pool? {
        switch family {
        case .claude:
            return build(family, now, [
                Account(email: "jai.khurana.longname+work@example-company.com", session: 45, week: 60, sessionIn: 210, weekIn: 51 * 60, ended: [12, 40]),
                Account(email: "second@example.com", session: 82, week: 71, sessionIn: 290, weekIn: 129 * 60, ended: [30, 0, 65]),
                Account(email: "third@example.com", session: 0, week: 38, sessionIn: 41, weekIn: 20 * 60, ended: [0, 22]),
            ])
        case .codex:
            // Three subscriptions, so the orbit's inner ring has a twist to show.
            return build(family, now, [
                Account(email: nil, session: 100, week: 0, sessionIn: 300, weekIn: 76 * 60, ended: [0, 18]),
                Account(email: "codex-team@example.com", session: 64, week: 52, sessionIn: 175, weekIn: 98 * 60, ended: [40, 70]),
                Account(email: "codex-side@example.com", session: 90, week: 81, sessionIn: 250, weekIn: 140 * 60, ended: [85]),
            ])
        }
    }

    private static func build(_ family: PoolFamily, _ now: Date, _ accounts: [Account]) -> Pool {
        // Whole minutes, as the bridge rounds reset times.
        let base = Date(timeIntervalSince1970: (now.timeIntervalSince1970 / 60).rounded(.down) * 60)
        func at(_ min: Double) -> Date { base + min * 60 }
        let members = accounts.enumerated().map { i, a in
            PoolMember(
                provider: i == 0 ? family.rawValue : "\(family.rawValue)\(i + 1)",
                account: a.email,
                fiveHour: LimitWindow(utilization: 100 - a.session, remaining: a.session, resetsAt: at(a.sessionIn), windowMinutes: 300),
                sevenDay: LimitWindow(utilization: 100 - a.week, remaining: a.week, resetsAt: at(a.weekIn), windowMinutes: 10080),
                sourceError: nil
            )
        }
        return Pool(
            family: family, accounts: accounts.count, capacity: accounts.count * 100,
            remaining: Double(accounts.reduce(0) { $0 + $1.week }),
            fiveHourRemaining: Double(accounts.reduce(0) { $0 + $1.session }),
            effectiveRemaining: Double(accounts.reduce(0) { $0 + min($1.week, $1.session) }),
            members: members, generatedAt: now, sourceError: nil,
            history: history(members, accounts, now),
            burnPctPerHour: family == .claude ? 4.8 : 0
        )
    }

    /// Twelve hours of samples every 10 min: each account's windows back to
    /// back, 5h apart, ending at its next reset; each drains from full to what
    /// `ended` says (the current one to `session`), then resets.
    private static func history(_ members: [PoolMember], _ accounts: [Account], _ now: Date) -> [SessionSample] {
        let window: TimeInterval = 5 * 3600
        return stride(from: 72, through: 1, by: -1).map { k in
            let t = now - Double(10 * k) * 60
            var left: [String: Double] = [:]
            var resets: [String: Date] = [:]
            for (m, a) in zip(members, accounts) {
                var reset = m.fiveHour!.resetsAt!
                var n = 0
                while reset - window > t { reset -= window; n += 1 }
                let end = n == 0 ? a.session : (n - 1 < a.ended.count ? a.ended[n - 1] : 100)
                let start = reset - window
                let until = n == 0 ? now : reset
                let f = min(max(t.timeIntervalSince(start) / until.timeIntervalSince(start), 0), 1)
                // Spend early, then hold: a limit hit stays at 0 until the reset.
                left[m.provider] = 100 - Double(100 - end) * min(1, f * 1.6)
                resets[m.provider] = reset
            }
            return SessionSample(t: t, pooled: left.values.reduce(0, +), left: left, resetsAt: resets)
        }
    }
}
