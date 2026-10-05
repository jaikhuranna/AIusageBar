#!/usr/bin/env python3
"""Run the repository's unchanged iOS Sync.swift with a delayed mock bridge.

Usage: python3 ios/Tests/check_sync.py [swift_path]
This writes no repository files and needs Foundation, not Apple UI frameworks.
"""
import pathlib
import subprocess
import sys

repo = pathlib.Path(__file__).resolve().parents[2]
swift = sys.argv[1] if len(sys.argv) > 1 else 'swift'

mocks = r'''
import Foundation

enum UsageProvider: String { case claude, codex }
enum SyncError: String { case unpaired, offline }
struct Snapshot { var provider: UsageProvider = .claude }
func parseSnapshot(_ s: String) throws -> Snapshot { Snapshot() }

enum Plan {
    static func nextCheck(_ s: Snapshot?, _ now: Date, failures: Int) -> TimeInterval { 1800 }
    static func comeback(_ s: Snapshot, _ now: Date) -> Date? { nil }
}

final class Store: @unchecked Sendable {
    static let shared = Store()
    var baseUrl: String? = "https://example.invalid"
    var lanUrl: String? = nil
    var token: String? = "dummy"
    var provider: UsageProvider = .claude
    var lastError: SyncError? = nil
    var fetchedAt: Date? = nil
    var nextFetchAt: Date? = nil
    var snapshotJson: String? = nil
    var etag: String? = nil
    var failures = 0
    func selectProvider(_ p: UsageProvider) { provider = p }
    func snapshot() -> Snapshot? { snapshotJson == nil ? nil : Snapshot() }
    func schedule(backAt: Date?, nextFetchAt: Date, for provider: UsageProvider) {
        self.nextFetchAt = nextFetchAt
    }
    func recordFresh(body: String?, etag: String?, at: Date, for provider: UsageProvider) {
        if let body { snapshotJson = body }
        fetchedAt = at
    }
    func recordFailure(_ error: SyncError, for provider: UsageProvider) { lastError = error }
}

actor Probe {
    static let shared = Probe()
    private(set) var starts = 0
    func started() { starts += 1 }
}

enum Bridge {
    enum Usage { case failed(String), fresh(String, String?), notModified, unauthorized }
    static func usage(base: String, token: String, etag: String?, provider: UsageProvider) async -> Usage {
        await Probe.shared.started()
        try? await Task.sleep(for: .milliseconds(600))
        return .fresh("{}", "tag")
    }
}
'''

checks = r'''
let first = Task { await Sync.shared.run(.force) }
// Wait for the first network request to start; do not depend on scheduler ordering.
while await Probe.shared.starts == 0 {
    try? await Task.sleep(for: .milliseconds(5))
}
let start = Date()
await Sync.shared.run(.due)
let elapsed = Date().timeIntervalSince(start)
let hasSnapshot = Store.shared.snapshotJson != nil
let coalescedCount = await Probe.shared.starts
print("concurrent run: waited \(elapsed)s; snapshot present = \(hasSnapshot); bridge requests = \(coalescedCount)")
precondition(hasSnapshot, "Concurrent run returned before the shared fetch populated the cache")
precondition(coalescedCount == 1, "Concurrent runs did not coalesce into one bridge request")
await first.value

// A subsequent due call observes the schedule set by the completed request.
await Sync.shared.run(.due)
let afterDue = await Probe.shared.starts
precondition(afterDue == 1, "A future nextFetchAt did not suppress an unnecessary due fetch")

// Ensure the in-flight task was cleared and a later forced refresh can run.
await Sync.shared.run(.force)
let afterForce = await Probe.shared.starts
precondition(afterForce == 2, "Completed shared task prevented a later forced refresh")
print("PASS: concurrent calls await one fetch; due cadence and later forced refresh remain functional")
'''

source = mocks + '\n' + (repo / 'ios/Shared/Sync.swift').read_text() + '\n' + checks
result = subprocess.run([swift, '-'], input=source, capture_output=True, text=True)
print(result.stdout, end='')
print(result.stderr, end='', file=sys.stderr)
sys.exit(result.returncode)
