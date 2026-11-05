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

enum UsageProvider: String, CaseIterable { case claude, claude2, codex }
enum PoolFamily: String, CaseIterable { case claude, codex }
enum SyncError: String { case unpaired, offline }
struct Snapshot { var provider: UsageProvider }
struct Pool { var family: PoolFamily }
func parseSnapshot(_ s: String) throws -> Snapshot { Snapshot(provider: UsageProvider(rawValue: s)!) }
func parsePool(_ s: String) throws -> Pool { Pool(family: PoolFamily(rawValue: s)!) }

enum Plan {
    static func nextCheck(_ s: Snapshot?, _ now: Date, failures: Int) -> TimeInterval { 1800 }
    static func comeback(_ s: Snapshot, _ now: Date) -> Date? { nil }
}

/// In-memory caches, one per provider and family, like the real Store's.
/// Locked, because Sync writes every cache from parallel tasks (UserDefaults is thread-safe).
final class Box: @unchecked Sendable {
    private let lock = NSLock()
    private var values: [String: Any] = [:]
    func get<T>(_ k: String) -> T? { lock.withLock { values[k] as? T } }
    func put(_ k: String, _ v: Any?) { lock.withLock { values[k] = v } }
}

struct ProviderCache {
    let box: Box
    let provider: UsageProvider
    var snapshotJson: String? { box.get("json." + provider.rawValue) }
    var etag: String? { nil }
    var fetchedAt: Date? { box.get("fetched." + provider.rawValue) }
    var failures: Int { 0 }
    var lastError: SyncError? { box.get("error." + provider.rawValue) }
    func snapshot() -> Snapshot? { snapshotJson.map { _ in Snapshot(provider: provider) } }
    func recordFresh(body: String?, etag: String?, at: Date) {
        if let body { box.put("json." + provider.rawValue, body) }
        box.put("fetched." + provider.rawValue, at)
        box.put("error." + provider.rawValue, nil)
    }
    func recordFailure(_ e: SyncError) { box.put("error." + provider.rawValue, e) }
    func record(backAt: Date?) {}
}

struct PoolCache {
    let box: Box
    let family: PoolFamily
    var json: String? { box.get("pool." + family.rawValue) }
    var etag: String? { nil }
    func recordFresh(body: String?, etag: String?, at: Date) { if let body { box.put("pool." + family.rawValue, body) } }
    func recordMissing() { box.put("pool." + family.rawValue, nil) }
    func recordFailure() {}
}

final class Store: @unchecked Sendable {
    static let shared = Store()
    let box = Box()
    var baseUrl: String? = "https://example.invalid"
    var lanUrl: String? = nil
    var token: String? = "dummy"
    var nextFetchAt: Date? = nil
    func selectProvider(_ p: UsageProvider) {}
    func cache(_ p: UsageProvider) -> ProviderCache { ProviderCache(box: box, provider: p) }
    func pool(_ f: PoolFamily) -> PoolCache { PoolCache(box: box, family: f) }
}

actor Probe {
    static let shared = Probe()
    private(set) var starts = 0
    func started() { starts += 1 }
}

enum Bridge {
    enum Usage { case failed(String), fresh(String, String?), notModified, unauthorized, notFound }
    static func usage(base: String, token: String, etag: String?, provider: UsageProvider) async -> Usage {
        await Probe.shared.started()
        try? await Task.sleep(for: .milliseconds(600))
        return .fresh(provider.rawValue, "tag")
    }
    static func pool(base: String, token: String, etag: String?, family: PoolFamily) async -> Usage {
        await Probe.shared.started()
        try? await Task.sleep(for: .milliseconds(300))
        return .fresh(family.rawValue, "tag")
    }
}
'''

checks = r'''
let perRun = 5
let first = Task { await Sync.shared.run(.force) }
// Wait for the first network request to start; do not depend on scheduler ordering.
while await Probe.shared.starts == 0 {
    try? await Task.sleep(for: .milliseconds(5))
}
let start = Date()
await Sync.shared.run(.due)
let elapsed = Date().timeIntervalSince(start)
let cached = UsageProvider.allCases.allSatisfy { Store.shared.cache($0).snapshotJson != nil }
    && PoolFamily.allCases.allSatisfy { Store.shared.pool($0).json != nil }
let coalescedCount = await Probe.shared.starts
print("concurrent run: waited \(elapsed)s; every cache present = \(cached); bridge requests = \(coalescedCount)")
precondition(cached, "Concurrent run returned before the shared fetch populated every cache")
precondition(coalescedCount == perRun, "Concurrent runs did not coalesce into one set of bridge requests")
precondition(elapsed < 1.1, "Providers and pools were fetched one after another, not in parallel")
await first.value

// A subsequent due call observes the schedule set by the completed run.
await Sync.shared.run(.due)
let afterDue = await Probe.shared.starts
precondition(afterDue == perRun, "A future nextFetchAt did not suppress an unnecessary due fetch")

// Opening the app within a minute of a good fetch skips the network.
await Sync.shared.run(.manual)
let afterManual = await Probe.shared.starts
precondition(afterManual == perRun, "A manual run within the throttle went to the network")

// Ensure the in-flight task was cleared and a later forced refresh can run.
await Sync.shared.run(.force)
let afterForce = await Probe.shared.starts
precondition(afterForce == 2 * perRun, "Completed shared task prevented a later forced refresh")
print("PASS: concurrent calls await one parallel fetch of every provider and pool; due, manual and forced runs behave")
'''

source = mocks + '\n' + (repo / 'ios/Shared/Sync.swift').read_text() + '\n' + checks
result = subprocess.run([swift, '-'], input=source, capture_output=True, text=True)
print(result.stdout, end='')
print(result.stderr, end='', file=sys.stderr)
sys.exit(result.returncode)
