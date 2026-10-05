import XCTest
@testable import AIusageCore

final class LookTests: XCTestCase {
    private let now = parseInstant("2026-09-24T12:00:00Z")!
    private var fetched: Date { now - 60 }
    private func at(_ minutes: Double) -> Date { now + minutes * 60 }
    private func win(_ remaining: Int, _ resetInMinutes: Double?) -> LimitWindow {
        LimitWindow(utilization: 100 - remaining, remaining: remaining, resetsAt: resetInMinutes.map(at))
    }
    private func snap(_ five: LimitWindow?, _ week: LimitWindow?) -> Snapshot {
        Snapshot(fiveHour: five, sevenDay: week, host: "host")
    }
    private func look(_ s: Snapshot?, backAt: Date? = nil, error: SyncError? = nil, paired: Bool = true) -> Look {
        Look.of(paired: paired, snap: s, fetchedAt: fetched, lastError: error, backAt: backAt, now: now)
    }

    func testUnpairedWinsOverEverything() {
        XCTAssertEqual(look(snap(win(0, 60), nil), paired: false).mode, .unpaired)
        XCTAssertEqual(look(snap(win(50, 60), nil), error: .unpaired).mode, .unpaired)
    }

    func testPairedWithoutDataIsWaiting() {
        XCTAssertEqual(look(nil).mode, .waiting)
    }

    func testWeeklyOutBeatsSessionOut() {
        let l = look(snap(win(0, 60), win(0, 3000)))
        XCTAssertEqual(l.mode, .weekOut)
        XCTAssertEqual(l.countdownTo, at(3000))
    }

    func testSessionOutCountsDownToTheSessionReset() {
        let l = look(snap(win(0, 150), win(40, 3000)))
        XCTAssertEqual(l.mode, .sessionOut)
        XCTAssertEqual(l.countdownTo, at(150))
        XCTAssertEqual(l.sessionTimerFraction(), 0.5, accuracy: 0.001)
        XCTAssertTrue(l.ticking)
    }

    func testGoForAnHourAfterTheComeback() {
        let s = snap(win(100, nil), win(40, 3000))
        XCTAssertEqual(look(s, backAt: at(-10)).mode, .go)
        XCTAssertEqual(look(s, backAt: at(-61)).mode, .normal)
        XCTAssertEqual(look(s, backAt: at(10)).mode, .normal)
    }

    func testGoEndsOnceTheSessionIsHalfGone() {
        let back = at(-14)
        XCTAssertEqual(look(snap(win(50, 286), win(40, 3000)), backAt: back).mode, .go)
        XCTAssertEqual(look(snap(win(28, 286), win(40, 3000)), backAt: back).mode, .normal)
    }

    func testUntilTextRoundsUpLikeTheCountdown() {
        let left: TimeInterval = 270 * 60 + 40
        XCTAssertEqual(countdownText(from: now, to: now + left), "4:31")
        XCTAssertEqual(untilText(from: now, to: now + left), "4h 31m")
    }

    func testAResetThatPassedOfflineSaysGo() {
        // Still holding the exhausted snapshot, but its reset time has gone by.
        XCTAssertEqual(look(snap(win(0, -5), win(40, 3000)), backAt: at(-5)).mode, .go)
    }

    func testStaleAfterAnHourOrWhenOffline() {
        XCTAssertFalse(look(snap(win(50, 60), nil)).stale)
        XCTAssertTrue(look(snap(win(50, 60), nil), error: .offline).stale)
        let old = Look.of(paired: true, snap: snap(win(50, 60), nil), fetchedAt: at(-61), lastError: nil, backAt: nil, now: now)
        XCTAssertTrue(old.stale)
    }

    func testAnExhaustedLimitIsNotStaleWhileWaitingForItsReset() {
        let old = Look.of(paired: true, snap: snap(win(0, 240), nil), fetchedAt: at(-120), lastError: nil, backAt: nil, now: now)
        XCTAssertFalse(old.stale)
    }

    func testPaceSaysWhetherTheWindowLasts() {
        // 2.5h into a 5h window with 30% used: plenty.
        XCTAssertEqual(pace(Effective(remaining: 70, resetsAt: at(150), unconfirmedReset: false), length: Look.session, now: now), .lasts)
        // 1h in with 50% used: empty in another hour.
        XCTAssertEqual(pace(Effective(remaining: 50, resetsAt: at(240), unconfirmedReset: false), length: Look.session, now: now), .runsOut(at(60)))
        XCTAssertEqual(pace(Effective(remaining: 100, resetsAt: at(240), unconfirmedReset: false), length: Look.session, now: now), .unused)
    }

    func testCountdownFormats() {
        XCTAssertEqual(countdownText(from: now, to: at(161)), "2:41")
        XCTAssertEqual(countdownText(from: now, to: at(41)), "41M")
        XCTAssertEqual(countdownText(from: now, to: now + 20), "1M")
        XCTAssertEqual(countdownText(from: now, to: at(3 * 1440 + 100)), "3D")
        XCTAssertEqual(shortCountdown(from: now, to: at(161)), "2H")
        XCTAssertEqual(shortCountdown(from: now, to: nil), "--")
    }

    func testDotFontWidths() {
        XCTAssertEqual(DotFont.width("0"), 5)
        XCTAssertEqual(DotFont.width("1"), 3)
        XCTAssertEqual(DotFont.width("2:41"), 5 + 1 + 1 + 1 + 5 + 1 + 3) // ":" is 1 wide, "1" is 3
    }

    func testDemosCoverEveryMode() {
        let modes = Look.demos(now).map(\.mode)
        for m in [Mode.normal, .sessionOut, .weekOut, .go, .unpaired] { XCTAssertTrue(modes.contains(m)) }
    }
}
