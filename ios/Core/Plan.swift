import Foundation

/// What a window means right now. A reset that passed while the desktop was out
/// of reach reads as full but unconfirmed: after a reset a window really is full
/// until it's used, so the stale pre-reset number would be the wrong answer.
struct Effective: Equatable {
    var remaining: Int
    var resetsAt: Date?
    var unconfirmedReset: Bool
}

extension LimitWindow {
    func effective(_ now: Date) -> Effective {
        if let r = resetsAt, r <= now { return Effective(remaining: 100, resetsAt: nil, unconfirmedReset: true) }
        return Effective(remaining: remaining, resetsAt: resetsAt, unconfirmedReset: false)
    }
}

/// Pure scheduling and countdown rules, shared with the watch and the Android widgets.
enum Plan {
    /// The owner's baseline: every 30 min while there's headroom. A fetch is
    /// cheap now that the bridge refreshes its own cache on `GET /usage`.
    static let base: TimeInterval = 30 * 60
    private static let backoffCap: TimeInterval = 60 * 60
    private static let floor: TimeInterval = 15 * 60
    private static let oneMinute: TimeInterval = 60

    static func windows(_ s: Snapshot, _ now: Date) -> [(key: String, e: Effective)] {
        var out: [(key: String, e: Effective)] = []
        if let w = s.fiveHour { out.append(("five_hour", w.effective(now))) }
        if let w = s.sevenDay { out.append(("seven_day", w.effective(now))) }
        return out
    }

    /// The window with the least headroom, which is the one that bites first.
    static func worst(_ s: Snapshot, _ now: Date) -> (key: String, e: Effective)? {
        windows(s, now).min { $0.e.remaining < $1.e.remaining }
    }

    /// When Claude comes back: the later reset among exhausted windows. If the
    /// weekly window is out, the 5h reset gives nothing back. Nil when nothing
    /// is exhausted, or when an exhausted window has no known reset.
    static func comeback(_ s: Snapshot, _ now: Date) -> Date? {
        let exhausted = windows(s, now).map(\.e).filter { $0.remaining <= 0 }
        if exhausted.isEmpty || exhausted.contains(where: { $0.resetsAt == nil }) { return nil }
        return exhausted.compactMap(\.resetsAt).max()
    }

    /// How long until the next background check.
    static func nextCheck(_ s: Snapshot?, _ now: Date, failures: Int) -> TimeInterval {
        if failures > 0 {
            // 15, 30, then the 60 min cap.
            let minutes = 15 << min(failures - 1, 2)
            return min(TimeInterval(minutes) * 60, backoffCap)
        }
        guard let s else { return floor }
        if s.sourceError != nil || (s.fiveHour == nil && s.sevenDay == nil) { return floor }
        // Out of quota: nothing can change until the reset, so don't ask.
        if let back = comeback(s, now) { return back.timeIntervalSince(now) + oneMinute }

        let left = worst(s, now)?.e.remaining ?? 100
        let interval = left > 50 ? base : floor
        // Check just after each reset so a rollover shows up promptly.
        let anchors = [s.fiveHour?.resetsAt, s.sevenDay?.resetsAt]
            .compactMap { $0 }
            .map { $0 + oneMinute }
            .filter { $0 > now + oneMinute }
        let next = (anchors + [now + interval]).min()!
        return max(next.timeIntervalSince(now), oneMinute)
    }
}

struct AddressError: LocalizedError, Equatable {
    let message: String
    var errorDescription: String? { message }
}

/// Turns what the user typed into a bridge base URL. Private addresses get
/// http:// (pairing on the LAN), everything else https:// (a tunnel), and a
/// plain-http public address is refused: the token would cross the internet
/// unencrypted.
func normalizeAddress(_ input: String) -> Result<String, AddressError> {
    var s = input.trimmingCharacters(in: .whitespacesAndNewlines)
    while s.hasSuffix("/") { s.removeLast() }
    if s.isEmpty { return .failure(AddressError(message: "Enter an address")) }
    let scheme = s.range(of: "://")
    let rest = scheme.map { String(s[$0.upperBound...]) } ?? s
    let host = hostOf(rest)
    if host.isEmpty { return .failure(AddressError(message: "That doesn't look like an address")) }
    if s.hasPrefix("https://") { return .success(s) }
    if s.hasPrefix("http://") {
        return isPrivateHost(host) ? .success(s) : .failure(AddressError(message: "Use https:// outside your home network"))
    }
    if scheme != nil { return .failure(AddressError(message: "Use an http:// or https:// address")) }
    return .success(isPrivateHost(host) ? "http://\(s)" : "https://\(s)")
}

private func hostOf(_ rest: String) -> String {
    let authority = rest.prefix { $0 != "/" }
    if authority.hasPrefix("[") { return String(authority.dropFirst().prefix { $0 != "]" }) }
    return String(authority.prefix { $0 != ":" })
}

func isPrivateHost(_ host: String) -> Bool {
    let h = host.lowercased()
    if h == "localhost" || h.hasSuffix(".local") { return true }
    let parts = h.split(separator: ".", omittingEmptySubsequences: false).map { Int($0) }
    guard parts.count == 4, parts.allSatisfy({ $0.map { (0...255).contains($0) } ?? false }) else { return false }
    let a = parts[0]!, b = parts[1]!
    return a == 10 || a == 127 || (a == 172 && (16...31).contains(b)) || (a == 192 && b == 168) || (a == 169 && b == 254)
}
