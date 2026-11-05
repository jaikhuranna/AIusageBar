import Foundation

/// One thread of the threads face: an account's week, bright for `left`
/// (0..1, nil unknown), in account `index`'s colour of `family`.
struct ThreadStrand: Equatable {
    var family: PoolFamily
    var index: Int
    var left: Double?
    var stale: Bool
}

/// Every account's week as a thread, family by family. A family with no
/// accounts still has one faint thread, so the twist keeps its shape.
func strandsOf(_ views: [FamilyView], now: Date) -> [ThreadStrand] {
    views.flatMap { v -> [ThreadStrand] in
        let stale = v.offline || now.timeIntervalSince(v.fetchedAt ?? .distantPast) > Look.staleAfter
        if v.known.isEmpty { return [ThreadStrand(family: v.family, index: 0, left: nil, stale: false)] }
        return v.known.enumerated().map { i, m in
            ThreadStrand(family: v.family, index: i, left: (m.week ?? m.session).map { Double($0.remaining) / 100 }, stale: stale)
        }
    }
}
