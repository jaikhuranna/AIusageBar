import Foundation

enum UsageProvider: String, CaseIterable {
    case claude, codex
    var title: String { self == .codex ? "Codex" : "Claude" }
}

/// One plan-limit window as the bridge reports it.
struct LimitWindow: Equatable {
    var utilization: Int
    var remaining: Int
    var resetsAt: Date?
    var windowMinutes: Int? = nil
}

/// GET /usage. The wire contract is the Endpoints table in AIusageBar's README.
struct Snapshot: Equatable {
    var fiveHour: LimitWindow?
    var sevenDay: LimitWindow?
    var costToday: Double = 0
    var costSession: Double = 0
    var costWeek: Double = 0
    var host: String = ""
    /// When Claude Code last fetched these numbers: their true age. Older bridges omit it.
    var cacheFetchedAt: Date? = nil
    var provider: UsageProvider = .claude
    var costAvailable: Bool = true
    var sourceError: String? = nil
}

private struct Wire: Decodable {
    struct Window: Decodable {
        let utilization: Double?
        let remaining: Double?
        let resets_at: String?
        let window_minutes: Int?
    }

    let five_hour: Window?
    let seven_day: Window?
    let cost_today: Double?
    let cost_session: Double?
    let cost_week: Double?
    let host: String?
    let cache_fetched_at: String?
    let provider: String?
    let cost_available: Bool?
    let source_error: String?
}

func parseSnapshot(_ json: String) throws -> Snapshot {
    let o = try JSONDecoder().decode(Wire.self, from: Data(json.utf8))
    return Snapshot(
        fiveHour: o.five_hour.map(parseWindow),
        sevenDay: o.seven_day.map(parseWindow),
        costToday: o.cost_today ?? 0,
        costSession: o.cost_session ?? 0,
        costWeek: o.cost_week ?? 0,
        host: o.host ?? "",
        cacheFetchedAt: parseInstant(o.cache_fetched_at),
        provider: UsageProvider(rawValue: o.provider ?? "claude") ?? .claude,
        costAvailable: o.cost_available ?? (o.provider != "codex"),
        sourceError: o.source_error
    )
}

private func parseWindow(_ o: Wire.Window) -> LimitWindow {
    let util = Int(o.utilization ?? 0)
    return LimitWindow(
        utilization: util,
        remaining: o.remaining.map { Int($0) } ?? max(100 - util, 0),
        resetsAt: parseInstant(o.resets_at),
        windowMinutes: o.window_minutes.flatMap { $0 > 0 ? $0 : nil }
    )
}

/// RFC 3339 in UTC, whole seconds: how the bridge writes every time.
func parseInstant(_ s: String?) -> Date? {
    guard let s, !s.isEmpty else { return nil }
    return ISO8601DateFormatter().date(from: s)
}
