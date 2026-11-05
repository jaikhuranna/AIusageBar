import XCTest
@testable import AIusageCore

final class BraidTests: XCTestCase {
    /// Wherever two strands meet, one is clearly in front: no crossing is ambiguous,
    /// whatever the number of accounts (the old pair-by-pair slicing broke at four).
    func testEveryCrossingHasAStrandInFront() {
        for count in 2...6 {
            var t = 0.0
            while t < 4 * .pi {
                for i in 0..<count {
                    for j in (i + 1)..<count {
                        let dy = abs(Braid.offset(i, of: count, at: t) - Braid.offset(j, of: count, at: t))
                        if dy < 0.05 {
                            let dz = abs(Braid.depth(i, of: count, at: t) - Braid.depth(j, of: count, at: t))
                            XCTAssertGreaterThan(dz, 0.3, "\(count) strands, \(i)×\(j) at t=\(t)")
                        }
                    }
                }
                t += 0.01
            }
        }
    }

    /// Two strands take turns: the one in front at one crossing is behind at the next.
    func testCrossingsAlternateOverAndUnder() {
        var fronts: [Int] = []
        var last = Braid.offset(0, of: 2, at: 0) - Braid.offset(1, of: 2, at: 0)
        var t = 0.0
        while t < 6 * .pi {
            t += 0.001
            let d = Braid.offset(0, of: 2, at: t) - Braid.offset(1, of: 2, at: t)
            if (d < 0) != (last < 0) { fronts.append(Braid.order(2, at: t).last!) }
            last = d
        }
        XCTAssertGreaterThanOrEqual(fronts.count, 4)
        for k in 1..<fronts.count { XCTAssertNotEqual(fronts[k], fronts[k - 1]) }
    }

    func testOrderIsBackToFront() {
        let order = Braid.order(3, at: 0.4)
        let depths = order.map { Braid.depth($0, of: 3, at: 0.4) }
        XCTAssertEqual(depths, depths.sorted())
        XCTAssertEqual(Set(order), [0, 1, 2])
    }

    /// Two strands cross twice a turn, taking turns in front; four strands make
    /// six pairs, each crossing twice a turn, never two at the same instant.
    func testCrossingsAreFoundAndTakeTurns() {
        let two = Braid.crossings(2, from: 0, to: 4 * .pi)
        XCTAssertEqual(two.count, 4)
        for k in 1..<two.count { XCTAssertNotEqual(two[k].front, two[k - 1].front) }
        let four = Braid.crossings(4, from: 0, to: 2 * .pi)
        XCTAssertEqual(four.count, 12)
        for c in four {
            XCTAssertEqual(Braid.offset(c.front, of: 4, at: c.t), Braid.offset(c.back, of: 4, at: c.t), accuracy: 1e-4)
            XCTAssertGreaterThan(Braid.depth(c.front, of: 4, at: c.t), Braid.depth(c.back, of: 4, at: c.t))
        }
    }
}
