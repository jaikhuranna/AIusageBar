import XCTest
@testable import AIusageCore

/// Port of Android's `FamilyTest`.
final class FamilyTests: XCTestCase {
    private let now = parseInstant("2026-10-30T12:00:00Z")!
    private func m(_ min: Double) -> Date { now + min * 60 }

    /// An account with `left`% of its session, resetting in `resetIn` minutes of a 5h window.
    private func session(_ number: Int, _ left: Int, _ resetIn: Double?) -> Member {
        Member(number: number, provider: "claude", account: "a\(number)@x.com",
               session: Effective(remaining: left, resetsAt: resetIn.map(m), unconfirmedReset: false), week: nil)
    }

    // MARK: the share left, and what comes back

    func testLeftIsAShareOfEveryAccount() {
        let meter = Meter.of([session(1, 45, 210), session(2, 82, 290)], .session, now: now)
        XCTAssertEqual(meter.left, 64)
        XCTAssertEqual(meter.segments.count, 2)
    }

    func testTheNextResetGivesBackItsAccountsShare() {
        let c = Meter.of([session(1, 45, 210), session(2, 82, 290)], .session, now: now).comeback!
        XCTAssertEqual(c.gain, 28)
        XCTAssertEqual(c.at, m(210))
        XCTAssertEqual(c.text(now), "+28% in 3h 30m")
    }

    func testAccountsResettingTogetherAddUp() {
        let c = Meter.comeback([session(1, 40, 60), session(2, 20, 60), session(3, 100, 30)], .session, now: now)!
        XCTAssertEqual(c.gain, 47)
        XCTAssertEqual(c.at, m(60))
    }

    func testAFullAccountGivesNothingBackSoTheNextOneCounts() {
        let c = Meter.comeback([session(1, 100, 10), session(2, 70, 120)], .session, now: now)!
        XCTAssertEqual(c.gain, 15)
        XCTAssertEqual(c.at, m(120))
    }

    func testASliverStillComesBackAsOnePercent() {
        XCTAssertEqual(Meter.comeback([session(1, 99, 10), session(2, 100, 10), session(3, 100, 10)], .session, now: now)!.gain, 1)
    }

    func testNothingUsedOrNoResetMeansNoComeback() {
        XCTAssertNil(Meter.comeback([session(1, 100, 30)], .session, now: now))
        XCTAssertNil(Meter.comeback([session(1, 50, nil)], .session, now: now))
    }

    func testAnOutFamilyComesBackInFull() {
        let meter = Meter.of([session(1, 0, 101)], .session, now: now)
        XCTAssertTrue(meter.out)
        XCTAssertNil(meter.pace, "no pace when there's nothing to spend")
        XCTAssertEqual(meter.comeback!.text(now), "+100% in 1h 41m")
    }

    func testUnreportedWindowsAreUnknown() {
        let meter = Meter.of([session(1, 50, 60)], .week, now: now)
        XCTAssertNil(meter.left)
        XCTAssertNil(meter.segments.first!.left)
    }

    // MARK: pace

    func testSpendingFasterThanTheClockIsAhead() {
        XCTAssertEqual(Meter.pace([session(1, 45, 210)], .session, now: now), .ahead)
    }

    func testMoreLeftThanTimeIsUnder() {
        XCTAssertEqual(Meter.pace([session(1, 90, 150)], .session, now: now), .under)
    }

    func testCloseToTheClockIsOnPace() {
        XCTAssertEqual(Meter.pace([session(1, 53, 150)], .session, now: now), .on)
    }

    func testPaceAveragesTheAccounts() {
        XCTAssertEqual(Meter.pace([session(1, 20, 150), session(2, 80, 150)], .session, now: now), .on)
    }

    func testNoPaceInTheFirstTenMinutesOrWithoutAReset() {
        XCTAssertNil(Meter.pace([session(1, 90, 295)], .session, now: now))
        XCTAssertNil(Meter.pace([session(1, 90, nil)], .session, now: now))
    }

    func testSegmentsCarryLeftAndTimeLeft() {
        let seg = Meter.of([session(1, 45, 210)], .session, now: now).segments.first!
        XCTAssertEqual(seg.number, 1)
        XCTAssertEqual(seg.left!, 0.45, accuracy: 0.001)
        XCTAssertEqual(seg.timeLeft!, 0.70, accuracy: 0.001)
    }

    // MARK: segment widths

    func testSegmentsAreEqualAndGapped() {
        let s = segmentSpans(208, 2, gap: 8)
        XCTAssertEqual(s[0].lowerBound, 0, accuracy: 0.001)
        XCTAssertEqual(s[0].upperBound, 100, accuracy: 0.001)
        XCTAssertEqual(s[1].lowerBound, 108, accuracy: 0.001)
        XCTAssertEqual(s[1].upperBound, 208, accuracy: 0.001)
    }

    func testOneSegmentFillsTheBar() {
        let s = segmentSpans(300, 1, gap: 8)[0]
        XCTAssertEqual(s.lowerBound, 0, accuracy: 0.001)
        XCTAssertEqual(s.upperBound, 300, accuracy: 0.001)
    }

    func testManySegmentsStillFitAndGapsShrinkBeforeSegmentsVanish() {
        let s = segmentSpans(100, 5, gap: 40)
        XCTAssertEqual(s.last!.upperBound, 100, accuracy: 0.001)
        s.forEach { XCTAssertGreaterThan($0.upperBound - $0.lowerBound, 0) }
        XCTAssertTrue(segmentSpans(100, 0, gap: 4).isEmpty)
    }

    // MARK: building a family

    func testAPoolNumbersItsMembersInOrder() {
        let pool = Pool(
            family: .claude, accounts: 2, capacity: 200, remaining: 0, fiveHourRemaining: 0, effectiveRemaining: 0,
            members: [
                PoolMember(provider: "claude", account: "a@x.com",
                           fiveHour: LimitWindow(utilization: 30, remaining: 70, resetsAt: m(60), windowMinutes: 300),
                           sevenDay: LimitWindow(utilization: 40, remaining: 60, resetsAt: m(3000), windowMinutes: 10080), sourceError: nil),
                PoolMember(provider: "claude2", account: nil,
                           fiveHour: LimitWindow(utilization: 50, remaining: 50, resetsAt: now - 5, windowMinutes: 300),
                           sevenDay: nil, sourceError: "hub read failed"),
            ],
            generatedAt: now, sourceError: nil
        )
        let v = FamilyView.of(pool, fetchedAt: nil, offline: false, now: now)
        XCTAssertEqual(v.members.map(\.number), [1, 2])
        XCTAssertEqual(v.members[0].name, "a@x.com")
        XCTAssertEqual(v.members[1].name, "Claude 2")
        XCTAssertEqual(v.members[1].error, "hub read failed")
        XCTAssertEqual(v.members[1].session!.remaining, 100, "a reset that passed offline is full")
    }

    func testWithoutAPoolTheSnapshotsMakeTheFamily() {
        func snap(_ p: UsageProvider, _ w: LimitWindow?, _ err: String? = nil) -> Snapshot {
            Snapshot(fiveHour: w, sevenDay: w, host: "h", provider: p, sourceError: err)
        }
        let v = FamilyView.of(.claude, snapshots: [snap(.claude, LimitWindow(utilization: 20, remaining: 80, resetsAt: m(60))), snap(.claude2, nil, "not signed in")],
                              fetchedAt: nil, offline: false, now: now)
        XCTAssertEqual(v.members.count, 1)
        XCTAssertNil(v.error)
        let none = FamilyView.of(.claude, snapshots: [nil, snap(.claude2, nil, "not signed in")], fetchedAt: nil, offline: false, now: now)
        XCTAssertTrue(none.members.isEmpty)
        XCTAssertEqual(none.error, "not signed in")
    }
}
