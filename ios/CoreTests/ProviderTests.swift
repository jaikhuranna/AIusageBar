import XCTest
@testable import AIusageCore

final class ProviderTests: XCTestCase {
    private let now = parseInstant("2026-10-03T12:00:00Z")!

    func testCodexWireAndLegacyDefaults() throws {
        let s = try parseSnapshot(#"{"provider":"codex","five_hour":{"utilization":25,"remaining":75,"window_minutes":15},"seven_day":null,"cost_available":false,"source_error":"Unavailable"}"#)
        XCTAssertEqual(s.provider, .codex)
        XCTAssertFalse(s.costAvailable)
        XCTAssertEqual(s.fiveHour?.windowMinutes, 15)
        XCTAssertNil(s.sevenDay)
        XCTAssertEqual(s.sourceError, "Unavailable")
        let old = try parseSnapshot(#"{"five_hour":null,"seven_day":null}"#)
        XCTAssertEqual(old.provider, .claude)
        XCTAssertTrue(old.costAvailable)
    }

    func testCodexUsesReportedDurationAndSourceAge() {
        let s = Snapshot(fiveHour: LimitWindow(utilization: 100, remaining: 0, resetsAt: now + 5 * 60, windowMinutes: 15),
                         sevenDay: nil, cacheFetchedAt: now - 7200, provider: .codex,
                         costAvailable: false, sourceError: "Unavailable")
        let l = Look.of(paired: true, snap: s, fetchedAt: now, lastError: nil, backAt: nil, now: now)
        XCTAssertEqual(l.provider, .codex)
        XCTAssertEqual(l.sessionLabel, "15M")
        XCTAssertEqual(l.sessionTimerFraction(), 1.0 / 3, accuracy: 0.001)
        XCTAssertEqual(l.fetchedAt, now - 7200)
        XCTAssertTrue(l.stale)
        XCTAssertEqual(l.weekLeft, 0)
        XCTAssertEqual(Plan.nextCheck(s, now, failures: 0), 15 * 60)
    }

    func testAProviderWithNoQuotasWaits() {
        let s = Snapshot(fiveHour: nil, sevenDay: nil, provider: .codex, sourceError: "Sign in on the desktop")
        let l = Look.of(paired: true, snap: s, fetchedAt: now, lastError: nil, backAt: nil, now: now)
        XCTAssertEqual(l.mode, .waiting)
        XCTAssertEqual(Plan.nextCheck(s, now, failures: 0), 15 * 60)
    }

    func testDailySecondaryLabelsAndAccessibility() throws {
        let s = try parseSnapshot(#"{"provider":"codex","five_hour":{"utilization":20,"window_minutes":60},"seven_day":{"utilization":40,"window_minutes":1440}}"#)
        let l = Look.of(paired: true, snap: s, fetchedAt: now, lastError: nil, backAt: nil, now: now)
        XCTAssertEqual(l.sessionLabel, "1H")
        XCTAssertEqual(l.weekLabel, "1D")
        XCTAssertTrue(l.summary.contains("80% of the 1H allowance"))
        XCTAssertTrue(l.summary.contains("60% of the 1D allowance"))
        XCTAssertFalse(l.summary.contains("week"))
    }
}
