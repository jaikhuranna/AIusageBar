import XCTest
@testable import AIusageCore

/// Port of Android's `PoolTest`.
final class PoolTests: XCTestCase {
    private let now = parseInstant("2026-10-30T12:00:00Z")!
    private var fetched: Date { now - 60 }
    private func h(_ hours: Double) -> Date { now + hours * 3600 }

    private func member(_ session: Int?, _ week: Int?, sessionReset: Date? = nil, weekReset: Date? = nil, provider: String = "claude") -> PoolMember {
        PoolMember(
            provider: provider, account: "a@x.com",
            fiveHour: session.map { LimitWindow(utilization: 100 - $0, remaining: $0, resetsAt: sessionReset ?? h(2), windowMinutes: 300) },
            sevenDay: week.map { LimitWindow(utilization: 100 - $0, remaining: $0, resetsAt: weekReset ?? h(50), windowMinutes: 10080) },
            sourceError: nil
        )
    }

    private func pool(_ members: PoolMember..., accounts: Int? = nil, sourceError: String? = nil) -> Pool {
        let n = accounts ?? members.count
        return Pool(family: .claude, accounts: n, capacity: n * 100, remaining: 0, fiveHourRemaining: 0, effectiveRemaining: 0,
                    members: members, generatedAt: now, sourceError: sourceError)
    }

    private func ring(_ p: Pool?, offline: Bool = false, backAts: [Date?] = []) -> Look? {
        poolRing(p, fetchedAt: fetched, offline: offline, backAts: backAts, now: now)
    }

    // MARK: parsing

    func testParsesTheContractExample() throws {
        let json = """
            {"family":"claude","accounts":2,"capacity_pct":200,"remaining_pct":131.0,
             "five_hour_remaining_pct":160.0,"effective_remaining_pct":118.0,"burn_pct_per_hour":3.7,
             "next_reset":{"at":"2026-10-31T10:00:00Z","gain_pct":100.0},
             "first_empty_at":"2026-10-31T03:49:00Z","pool_empty_at":null,
             "members":[
               {"provider":"claude","account":"a@x.com","five_hour":{"window_minutes":300,"utilization":30,"remaining":70,"resets_at":"2026-10-30T14:00:00Z"},
                "seven_day":{"window_minutes":10080,"utilization":40,"remaining":60,"resets_at":"2026-11-01T14:00:00Z"},"source_error":""},
               {"provider":"claude2","account":"b@x.com","five_hour":null,"seven_day":null,"source_error":"no token"}],
             "history":[{"t":1793390000,"remaining_pct":140.0}],
             "projection":[{"t":1793400000,"remaining_pct":131.0,"reset":false}],
             "generated_at":"2026-10-30T12:00:00Z","source_error":""}
            """
        let p = try parsePool(json)
        XCTAssertEqual(p.family, .claude)
        XCTAssertEqual(p.accounts, 2)
        XCTAssertEqual(p.capacity, 200)
        XCTAssertEqual(p.remaining, 131)
        XCTAssertEqual(p.effectiveRemaining, 118)
        XCTAssertEqual(p.members[0].sevenDay?.remaining, 60)
        XCTAssertEqual(p.members[1].sourceError, "no token")
        XCTAssertNil(p.members[1].fiveHour)
        XCTAssertNil(p.sourceError)
        XCTAssertEqual(p.generatedAt, now)
        XCTAssertEqual(p.burnPctPerHour, 3.7)
        XCTAssertNil(try parsePool(#"{"family":"codex","burn_pct_per_hour":null}"#).burnPctPerHour)
    }

    func testParsesTheFiveHourHistory() throws {
        let p = try parsePool("""
            {"family":"claude","accounts":2,"members":[],"history":[
            {"t":1793390600,"remaining_pct":140,"five_hour_remaining_pct":150,"effective_remaining_pct":120,
             "five_hour_remaining":{"claude":60,"claude2":90},"five_hour_resets_at":{"claude":1793400000}},
            {"t":1793390000,"remaining_pct":140,"five_hour_remaining_pct":170,"effective_remaining_pct":125},
            {"remaining_pct":1}]}
            """)
        XCTAssertEqual(p.history.map { Int($0.t.timeIntervalSince1970) }, [1793390000, 1793390600])
        XCTAssertEqual(p.history[0].pooled, 170)
        XCTAssertTrue(p.history[0].left.isEmpty)
        XCTAssertEqual(p.history[1].left, ["claude": 60, "claude2": 90])
        XCTAssertEqual(p.history[1].resetsAt, ["claude": Date(timeIntervalSince1970: 1793400000)])
    }

    func testParsesNullsAndAnEmptyFamily() throws {
        let p = try parsePool(#"{"family":"codex","accounts":0,"capacity_pct":0,"burn_pct_per_hour":null,"next_reset":null,"members":[],"history":[],"projection":[],"source_error":"no accounts"}"#)
        XCTAssertEqual(p.family, .codex)
        XCTAssertEqual(p.accounts, 0)
        XCTAssertTrue(p.members.isEmpty)
        XCTAssertEqual(p.sourceError, "no accounts")
    }

    func testParsesTheServersRealCodexSample() throws {
        let p = try parsePool("""
            {"family":"codex","accounts":1,"capacity_pct":100,"remaining_pct":0,"five_hour_remaining_pct":100,
            "effective_remaining_pct":0,"burn_pct_per_hour":null,"next_reset":{"at":"2026-11-04T07:53:23Z","gain_pct":100},
            "first_empty_at":null,"pool_empty_at":"2026-10-30T12:00:00Z",
            "members":[{"provider":"codex","account":"","five_hour":{"window_minutes":300,"utilization":0,"remaining":100,"resets_at":"2026-10-30T17:00:00Z","resets_in_sec":1},
              "seven_day":{"window_minutes":10080,"utilization":100,"remaining":0,"resets_at":"2026-11-04T07:53:23Z","resets_in_sec":1},"source_error":""}],
            "history":[],"projection":[],"generated_at":"2026-10-30T12:00:00Z","source_error":""}
            """)
        XCTAssertNil(p.members[0].account)
        let l = ring(p)!
        XCTAssertEqual(l.mode, .weekOut)
        XCTAssertEqual(l.provider, .codex)
        XCTAssertEqual(l.countdownTo, parseInstant("2026-11-04T07:53:23Z"))
    }

    // MARK: the pooled ring

    func testNoPoolFallsBackToTheProviderRing() {
        XCTAssertNil(ring(nil))
    }

    func testZeroAccountsOrNoDataWaits() {
        XCTAssertEqual(ring(pool(accounts: 0, sourceError: "No Claude account is signed in to the hub."))!.mode, .waiting)
        XCTAssertEqual(ring(pool(member(nil, nil), sourceError: "hub unreachable"))!.mode, .waiting)
    }

    func testOneAccountIsItsOwnRing() {
        let l = ring(pool(member(70, 62)))!
        XCTAssertEqual(l.mode, .normal)
        XCTAssertEqual(l.sessionLeft, 62)
        XCTAssertEqual(l.weekLeft, 62)
        let roomy = ring(pool(member(40, 90)))!
        XCTAssertEqual(roomy.sessionLeft, 40)
        XCTAssertEqual(roomy.weekLeft, 90)
    }

    func testTwoAccountsPoolAsAShareOfBoth() {
        let l = ring(pool(member(70, 60), member(48, 71)))!
        XCTAssertEqual(l.sessionLeft, 54)
        XCTAssertEqual(l.weekLeft, 66)
    }

    func testAnyNumberOfAccounts() {
        let l = ring(pool(member(100, 90), member(10, 80), member(55, 55), member(100, 100)))!
        XCTAssertEqual(l.sessionLeft, 64)
        XCTAssertEqual(l.weekLeft, 81)
    }

    func testAnAccountWithoutDataIsLeftOut() {
        XCTAssertEqual(ring(pool(member(70, 60), member(nil, nil)))!.sessionLeft, 60)
    }

    func testASlimRemainderNeverRoundsToAnEmptyOrFullRing() {
        XCTAssertEqual(ring(pool(member(1, 1), member(0, 50), member(0, 50), member(0, 50)))!.sessionLeft, 1)
        XCTAssertEqual(ring(pool(member(100, 100), member(100, 100), member(100, 100), member(99, 99)))!.sessionLeft, 99)
    }

    func testNothingSpendableCountsDownToTheFirstBack() {
        let l = ring(pool(member(40, 0, weekReset: h(50)), member(0, 30, sessionReset: h(2))))!
        XCTAssertEqual(l.mode, .sessionOut)
        XCTAssertEqual(l.countdownTo, h(2))
        XCTAssertTrue(l.ticking)
    }

    func testEveryWeekOutIsWeekOut() {
        let l = ring(pool(member(40, 0, weekReset: h(50)), member(80, 0, weekReset: h(20))))!
        XCTAssertEqual(l.mode, .weekOut)
        XCTAssertEqual(l.countdownTo, h(20))
    }

    func testAComebackIsGo() {
        let back = now - 10 * 60
        XCTAssertEqual(ring(pool(member(100, 80), member(60, 70)), backAts: [nil, back])!.mode, .go)
        XCTAssertEqual(ring(pool(member(30, 80), member(20, 70)), backAts: [back])!.mode, .normal)
        XCTAssertEqual(ring(pool(member(100, 80)), backAts: [now - 61 * 60])!.mode, .normal)
    }

    func testAResetThatPassedOfflineCountsAsFull() {
        XCTAssertEqual(ring(pool(member(0, 50, sessionReset: now - 60)))!.sessionLeft, 50)
    }

    func testOfflineOrOldIsStale() {
        XCTAssertTrue(ring(pool(member(70, 60)), offline: true)!.stale)
        XCTAssertFalse(ring(pool(member(70, 60)))!.stale)
        XCTAssertTrue(poolRing(pool(member(70, 60)), fetchedAt: now - 7200, offline: false, backAts: [], now: now)!.stale)
    }

    func testFamiliesCoverEveryProvider() {
        XCTAssertEqual(PoolFamily.of(.claude2), .claude)
        XCTAssertEqual(PoolFamily.of(.codex), .codex)
        UsageProvider.allCases.forEach { _ = PoolFamily.of($0) }
    }

    func testSecondClaudeAccountKeepsItsEmailAndHasNoCost() throws {
        let s = try parseSnapshot(#"{"provider":"claude2","account":"b@example.com","five_hour":{"utilization":25,"remaining":75},"seven_day":null,"cost_available":false}"#)
        XCTAssertEqual(s.provider, .claude2)
        XCTAssertEqual(s.account, "b@example.com")
        XCTAssertFalse(s.costAvailable)
        XCTAssertEqual(Look.of(paired: true, snap: s, fetchedAt: now, lastError: nil, backAt: nil, now: now).account, "b@example.com")
        XCTAssertFalse(try parseSnapshot(#"{"provider":"claude2"}"#).costAvailable)
        XCTAssertNil(try parseSnapshot(#"{"provider":"claude"}"#).account)
    }
}
