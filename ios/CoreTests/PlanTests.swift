import XCTest
@testable import AIusageCore

final class PlanTests: XCTestCase {
    private let now = parseInstant("2026-09-23T12:00:00Z")!
    private func at(_ minutes: Double) -> Date { now + minutes * 60 }
    private func win(_ remaining: Int, _ resetInMinutes: Double?) -> LimitWindow {
        LimitWindow(utilization: 100 - remaining, remaining: remaining, resetsAt: resetInMinutes.map(at))
    }
    private func snap(_ five: LimitWindow?, _ week: LimitWindow?) -> Snapshot {
        Snapshot(fiveHour: five, sevenDay: week, host: "host")
    }

    func testComebackIsTheLaterResetAmongExhaustedWindows() {
        XCTAssertEqual(Plan.comeback(snap(win(0, 60), win(0, 3000)), now), at(3000))
    }

    func testWeeklyOutMeansTheSessionResetDoesNotHelp() {
        XCTAssertEqual(Plan.comeback(snap(win(40, 60), win(0, 3000)), now), at(3000))
    }

    func testNoComebackWhileThereIsHeadroom() {
        XCTAssertNil(Plan.comeback(snap(win(5, 60), win(50, 3000)), now))
    }

    func testAResetThatPassedOfflineReadsAsFullButUnconfirmed() {
        let e = win(0, -10).effective(now)
        XCTAssertEqual(e.remaining, 100)
        XCTAssertTrue(e.unconfirmedReset)
        XCTAssertNil(Plan.comeback(snap(win(0, -10), nil), now))
    }

    func testPlentyLeftChecksEveryHalfHour() {
        XCTAssertEqual(Plan.nextCheck(snap(win(80, 240), nil), now, failures: 0), 30 * 60)
    }

    func testAResetSoonerThanTheIntervalIsCheckedJustAfter() {
        XCTAssertEqual(Plan.nextCheck(snap(win(80, 10), nil), now, failures: 0), 11 * 60)
    }

    func testCadenceTightensAsHeadroomShrinks() {
        XCTAssertEqual(Plan.nextCheck(snap(win(50, 290), nil), now, failures: 0), 15 * 60)
        XCTAssertEqual(Plan.nextCheck(snap(win(10, 290), nil), now, failures: 0), 15 * 60)
    }

    func testExhaustedStopsPollingUntilTheComeback() {
        XCTAssertEqual(Plan.nextCheck(snap(win(0, 120), nil), now, failures: 0), 121 * 60)
    }

    func testFailuresBackOffToAnHour() {
        XCTAssertEqual(Plan.nextCheck(nil, now, failures: 1), 15 * 60)
        XCTAssertEqual(Plan.nextCheck(nil, now, failures: 2), 30 * 60)
        XCTAssertEqual(Plan.nextCheck(nil, now, failures: 9), 60 * 60)
    }

    func testAddressesGetTheRightScheme() {
        XCTAssertEqual(try normalizeAddress("192.168.0.19:8765").get(), "http://192.168.0.19:8765")
        XCTAssertEqual(try normalizeAddress("pop-os.local:8765/").get(), "http://pop-os.local:8765")
        XCTAssertEqual(try normalizeAddress("usage.example.com").get(), "https://usage.example.com")
        XCTAssertEqual(try normalizeAddress("https://usage.example.com/").get(), "https://usage.example.com")
        XCTAssertThrowsError(try normalizeAddress("http://usage.example.com").get())
        XCTAssertThrowsError(try normalizeAddress("ftp://x").get())
        XCTAssertThrowsError(try normalizeAddress("  ").get())
    }

    func testPrivateHosts() {
        for h in ["10.0.0.1", "172.20.1.1", "192.168.1.5", "127.0.0.1", "box.local"] { XCTAssertTrue(isPrivateHost(h), h) }
        for h in ["172.32.0.1", "8.8.8.8", "usage.example.com", "999.1.1.1"] { XCTAssertFalse(isPrivateHost(h), h) }
    }

    func testParsesTheBridgeWireFormat() throws {
        let s = try parseSnapshot(
            """
            {"schema":1,"five_hour":{"utilization":53,"remaining":47,"resets_at":"2026-09-21T18:20:00Z","resets_in_sec":1},
             "seven_day":null,"cost_today":2.5,"cost_session":1,"cost_week":9,"generated_at":"x","host":"pop-os",
             "cache_fetched_at":"2026-09-21T13:43:12Z"}
            """
        )
        XCTAssertEqual(s.fiveHour?.remaining, 47)
        XCTAssertEqual(s.fiveHour?.resetsAt, parseInstant("2026-09-21T18:20:00Z"))
        XCTAssertNil(s.sevenDay)
        XCTAssertEqual(s.host, "pop-os")
        XCTAssertEqual(s.cacheFetchedAt, parseInstant("2026-09-21T13:43:12Z"))
    }

    func testMissingRemainingIsDerived() throws {
        let s = try parseSnapshot(#"{"five_hour":{"utilization":120}}"#)
        XCTAssertEqual(s.fiveHour?.remaining, 0)
    }
}
