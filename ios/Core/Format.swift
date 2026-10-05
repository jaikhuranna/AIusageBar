import Foundation

/// Whole minutes from `from` to `to`, a started minute counting as one.
private func ceilMinutes(_ d: TimeInterval) -> Int {
    let s = Int(d)
    return s / 60 + (s % 60 > 0 ? 1 : 0)
}

/// "2h 41m", "3d 4h", "12m". Rounds up to the minute, like `countdownText`.
func untilText(from: Date, to: Date) -> String {
    let d = to.timeIntervalSince(from)
    if d <= 0 { return "now" }
    let total = ceilMinutes(d)
    let h = total / 60
    let m = total % 60
    if h >= 24 { return "\(h / 24)d \(h % 24)h" }
    if h > 0 { return "\(h)h \(m)m" }
    return "\(max(m, 1))m"
}

func agoText(_ fetchedAt: Date?, now: Date) -> String {
    guard let fetchedAt else { return "never" }
    let m = Int(now.timeIntervalSince(fetchedAt)) / 60
    if m < 1 { return "just now" }
    if m < 60 { return "\(m) min ago" }
    if m < 48 * 60 { return "\(m / 60)h ago" }
    return "\(m / 1440)d ago"
}

/// "3:40 PM" today-ish, "Fri 9:00 AM" further out. Follows the phone's 12/24h setting.
func clockText(_ t: Date, now: Date) -> String {
    let f = DateFormatter()
    f.setLocalizedDateFormatFromTemplate(t.timeIntervalSince(now) < 20 * 3600 ? "jmm" : "EEEjmm")
    return f.string(from: t)
}

/// "2:41" above an hour, "41M" under it, "3D" for days. Fits the dot-matrix numerals.
func countdownText(from: Date, to: Date) -> String {
    let d = to.timeIntervalSince(from)
    if d <= 0 { return "0M" }
    let m = ceilMinutes(d)
    if m >= 48 * 60 { return "\(m / 1440)D" }
    if m >= 60 {
        let mm = m % 60
        return "\(m / 60):\(mm < 10 ? "0" : "")\(mm)"
    }
    return "\(m)M"
}

/// "2H", "41M", "3D": the tightest form, for a corner label.
func shortCountdown(from: Date, to: Date?) -> String {
    guard let to else { return "--" }
    let m = Int(to.timeIntervalSince(from)) / 60
    if m < 1 { return "0M" }
    if m < 60 { return "\(m)M" }
    if m < 48 * 60 { return "\(m / 60)H" }
    return "\(m / 1440)D"
}
