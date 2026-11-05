import XCTest
@testable import AIusageCore

/// Port of Android's `SessionChartTest`.
final class SessionChartTests: XCTestCase {
    private let now = parseInstant("2026-11-01T12:00:00Z")!
    private func m(_ minutes: Double) -> Date { now + minutes * 60 }

    private func member(_ number: Int, _ provider: String, _ left: Int, _ reset: Date?) -> Member {
        Member(number: number, provider: provider, account: nil,
               session: Effective(remaining: left, resetsAt: reset, unconfirmedReset: false),
               week: Effective(remaining: 50, resetsAt: m(3000), unconfirmedReset: false))
    }

    private func sample(_ minutes: Double, _ accounts: (String, Double, Date?)...) -> SessionSample {
        SessionSample(
            t: m(minutes), pooled: accounts.reduce(0) { $0 + $1.1 },
            left: Dictionary(uniqueKeysWithValues: accounts.map { ($0.0, $0.1) }),
            resetsAt: Dictionary(uniqueKeysWithValues: accounts.compactMap { a in a.2.map { (a.0, $0) } })
        )
    }

    private func assertPoints(_ expected: [(Date, Double)], _ actual: [ChartPoint], file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(expected.map(\.0), actual.map(\.t), file: file, line: line)
        for (e, a) in zip(expected, actual) { XCTAssertEqual(e.1, a.left, accuracy: 1e-4, file: file, line: line) }
    }

    private func assertMarks(_ expected: [ResetMark], _ actual: [ResetMark], file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(expected.count, actual.count, file: file, line: line)
        for (e, a) in zip(expected, actual) {
            XCTAssertEqual(e.at, a.at, file: file, line: line)
            XCTAssertEqual(e.color, a.color, file: file, line: line)
            XCTAssertEqual(e.upcoming, a.upcoming, file: file, line: line)
            XCTAssertEqual(e.gain, a.gain, accuracy: 1e-4, file: file, line: line)
        }
    }

    // MARK: the line and its resets

    func testARecordedResetStepsTheLineUpByWhatItGaveBack() {
        let reset = m(-120)
        let history = [sample(-200, ("claude", 40, reset)), sample(-130, ("claude", 10, reset)), sample(-100, ("claude", 95, m(180)))]
        let chart = SessionChart.of([member(1, "claude", 80, m(180))], history: history, now: now)
        assertMarks([ResetMark(at: reset, color: 0, upcoming: false, gain: 0.9), ResetMark(at: m(180), color: 0, upcoming: true, gain: 0.2)], chart.resets)
        assertPoints([(m(-200), 0.4), (m(-130), 0.1), (reset, 0.1), (reset, 1), (m(-100), 0.95), (now, 0.8)], chart.line)
        XCTAssertEqual(chart.current!, 0.8, accuracy: 1e-4)
    }

    func testAResetSinceTheLastSampleIsMarkedAtItsRecordedTime() {
        let reset = m(-10)
        let chart = SessionChart.of([member(1, "codex", 100, nil)], history: [sample(-30, ("codex", 5, reset))], now: now)
        assertMarks([ResetMark(at: reset, color: 0, upcoming: false, gain: 0.95)], chart.resets)
        assertPoints([(now, 1), (chart.to, 1)], chart.projection)
    }

    func testWithoutRecordedResetsAJumpIsAReset() {
        let history = [SessionSample(t: m(-300), pooled: 30), SessionSample(t: m(-240), pooled: 20),
                       SessionSample(t: m(-180), pooled: 90), SessionSample(t: m(-120), pooled: 95)]
        let chart = SessionChart.of([member(1, "codex", 85, m(100))], history: history, now: now)
        assertMarks([ResetMark(at: m(-180), color: 0, upcoming: false, gain: 0.7), ResetMark(at: m(100), color: 0, upcoming: true, gain: 0.15)], chart.resets)
    }

    func testResetsOutsideTheWindowAreDroppedAndRepeatsMerged() {
        let old = m(-13 * 60)
        let history = [
            sample(-14 * 60, ("claude", 10, old)),
            sample(-12 * 60 - 30, ("claude", 100, m(-60))),
            sample(-70, ("claude", 50, m(-60))),
            sample(-50, ("claude", 100, m(-59))),
            sample(-40, ("claude", 90, m(240))),
        ]
        let chart = SessionChart.of([member(1, "claude", 90, m(240))], history: history, now: now)
        XCTAssertEqual(chart.resets.map(\.at), [m(-60), m(240)])
    }

    func testSeveralAccountsAreOneLineWithEachResetInItsAccountsColour() {
        let history = [
            sample(-90, ("claude", 20, m(-60)), ("claude2", 70, m(30))),
            sample(-30, ("claude", 100, m(240)), ("claude2", 60, m(30))),
        ]
        let chart = SessionChart.of([member(1, "claude", 90, m(240)), member(2, "claude2", 55, m(30))], history: history, now: now)
        assertPoints([(m(-90), 0.45), (m(-60), 0.45), (m(-60), 0.85), (m(-30), 0.8), (now, 0.725)], chart.line)
        assertMarks([ResetMark(at: m(-60), color: 0, upcoming: false, gain: 0.4), ResetMark(at: m(30), color: 1, upcoming: true, gain: 0.225),
                     ResetMark(at: m(240), color: 0, upcoming: true, gain: 0.05)], chart.resets)
        assertPoints([(now, 0.725), (m(30), 0.725), (m(30), 0.95), (m(240), 0.95), (m(240), 1), (chart.to, 1)], chart.projection)
    }

    func testOlderSamplesOfSeveralAccountsUseThePooledSum() {
        let history = [SessionSample(t: m(-300), pooled: 120), SessionSample(t: m(-240), pooled: 100), sample(-60, ("claude", 50, nil), ("claude2", 40, nil))]
        let chart = SessionChart.of([member(1, "claude", 45, nil), member(2, "claude2", 40, nil)], history: history, now: now)
        assertPoints([(m(-300), 0.6), (m(-240), 0.5), (m(-60), 0.45), (now, 0.425)], chart.line)
    }

    func testAJumpInThePooledSumOfSeveralCantSayWhose() {
        let history = [SessionSample(t: m(-300), pooled: 40), SessionSample(t: m(-240), pooled: 140)]
        let chart = SessionChart.of([member(1, "claude", 70, nil), member(2, "claude2", 70, nil)], history: history, now: now)
        assertMarks([ResetMark(at: m(-240), color: SessionChart.unknown, upcoming: false, gain: 0.5)], chart.resets)
    }

    func testALongSilenceBreaksTheLine() {
        let history = [sample(-600, ("codex", 50, nil)), sample(-300, ("codex", 50, nil))]
        let pts = SessionChart.of([member(1, "codex", 50, nil)], history: history, now: now).line
        XCTAssertEqual(pts.map(\.penUp), [false, true, true])
    }

    func testHistoryCrossingTheLeftEdgeStartsAtTheInterpolatedValue() {
        let history = [SessionSample(t: m(-750), pooled: 80), SessionSample(t: m(-690), pooled: 60)]
        let chart = SessionChart.of([member(1, "codex", 50, nil)], history: history, now: now)
        XCTAssertEqual(chart.line.first!.t, chart.from)
        XCTAssertEqual(chart.line.first!.left, 0.7, accuracy: 1e-4)
        XCTAssertTrue(chart.line.allSatisfy { $0.t >= chart.from })
    }

    func testARecordingGapAcrossTheLeftEdgeIsNotFilledIn() {
        let history = [SessionSample(t: m(-900), pooled: 80), SessionSample(t: m(-600), pooled: 60)]
        let chart = SessionChart.of([member(1, "codex", 50, nil)], history: history, now: now)
        XCTAssertEqual(chart.line.first!.t, m(-600))
        XCTAssertTrue(chart.line.first!.penUp)
    }

    func testNoNumbersNoLine() {
        let chart = SessionChart.of([], history: [], now: now)
        XCTAssertTrue(chart.line.isEmpty && chart.projection.isEmpty)
        XCTAssertNil(chart.current)
    }

    // MARK: scaling

    func testTheRangeIsTwelveHoursBackToJustPastTheLastReset() {
        let chart = SessionChart.of([member(1, "claude", 50, m(120)), member(2, "claude2", 50, m(270))], history: [], now: now)
        XCTAssertEqual(chart.from, now - 12 * 3600)
        XCTAssertTrue(chart.to > m(270) && chart.to < m(270 + 40))
        let idle = SessionChart.of([member(1, "codex", 100, nil)], history: [], now: now)
        XCTAssertTrue(idle.to > m(44))
    }

    func testXAndYScale() {
        let chart = SessionChart(from: m(-600), now: now, to: m(400), line: [], projection: [], resets: [])
        XCTAssertEqual(chart.x(m(-600), 500), 0, accuracy: 1e-3)
        XCTAssertEqual(chart.x(now, 500), 300, accuracy: 1e-3)
        XCTAssertEqual(chart.x(m(400), 500), 500, accuracy: 1e-3)
        XCTAssertEqual(chart.y(1, top: 10, height: 60), 10, accuracy: 1e-3)
        XCTAssertEqual(chart.y(0, top: 10, height: 60), 70, accuracy: 1e-3)
        XCTAssertEqual(chart.y(0.75, top: 10, height: 60), 25, accuracy: 1e-3)
        XCTAssertEqual(chart.y(-0.2, top: 10, height: 60), 70, accuracy: 1e-3)
    }

    // MARK: labels

    func testLabelsKeepPriorityDropOverlapsAndStayInside() {
        let lefts = placeLabels([(100, 20), (112, 20), (140, 20), (2, 20), (299, 20)], width: 300, gap: 4)
        XCTAssertEqual(lefts, [90, nil, 130, 0, 280])
    }

    func testACrowdedLabelMovesJustClearWhenThatsCloseEnough() {
        XCTAssertEqual(placeLabels([(100, 20), (116, 20)], width: 300, gap: 4, nudge: 10), [90, 114])
        XCTAssertEqual(placeLabels([(100, 20), (101, 20)], width: 300, gap: 4, nudge: 10), [90, nil])
        XCTAssertEqual(placeLabels([(290, 30), (295, 20)], width: 300, gap: 4, nudge: 40), [270, 246])
    }
}
