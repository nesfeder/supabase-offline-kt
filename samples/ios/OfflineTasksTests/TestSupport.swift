// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline
import SupabaseOfflineTesting
import Testing
import UIKit

@testable import OfflineTasks

/// The app for one test: its models on the testing product's in-memory client, talking to a demo
/// server of its own, with the app's background work running.
final class TestApp {

    /// The stand-in for the samples' project.
    let server: DemoServer

    /// The signed-in user.
    let session: DemoSession

    /// The app's state, as the screens see it.
    let app: AppModel

    /// The app's background work, stopped by ``stop()``.
    private var run: Task<Void, Never>?

    /// The live reads the tabs share, stopped by ``stop()``.
    private var reads: [Task<Void, Never>] = []

    /// The live updates, stopped by ``stop()``.
    private var liveUpdates: Task<Void, Never>?

    /// A fresh app on `server`, signed in as `session`'s user or a new one whose sign-out takes
    /// `signOutDelay`, on `clock`'s time when one is given, its connectivity `networkAvailability`.
    init(
        server: DemoServer = DemoServer(),
        session: DemoSession? = nil,
        signOutDelay: Duration = .zero,
        clock: ManualClock? = nil,
        networkAvailability: @escaping @Sendable () -> AsyncStream<Bool> = { AsyncStream { $0.yield(true) } }
    ) {
        self.server = server
        let session = session ?? DemoSession(signOutDelay: signOutDelay)
        self.session = session
        app = AppModel.demo(server: server, session: session, clock: clock, networkAvailability: networkAvailability)
    }

    /// Every read and write.
    var repository: TaskRepository {
        app.repository
    }

    /// Starts what the root view starts: the app's run and the live reads the tabs share.
    func start() async throws {
        let app = app
        run = Task { await app.run() }
        startReads()
        try await eventually("the task list is read") { app.tasks.rows != nil }
    }

    /// Starts the live reads again, for the user now signed in, as the root view does when the
    /// user changes.
    func startReads() {
        reads.forEach { $0.cancel() }
        let app = app
        reads = [
            Task { await app.tasks.observeTasks() },
            Task { await app.tasks.observeVisibleTasks(readStrategy: app.listReadStrategy) },
            Task { await app.tasks.observePendingTasks() },
            Task { await app.sync.observe() },
        ]
    }

    /// Shows the list `query` asks for, as choosing a chip or searching does; the test waits for
    /// the rows it expects.
    func show(_ query: TaskQuery) async throws {
        app.tasks.query = query
        startReads()
    }

    /// Starts Live updates, as the root view does, and waits until the server reports to it.
    func startLiveUpdates() async throws {
        let app = app
        liveUpdates = Task { await app.followLiveUpdates() }
        try await eventually("the feed is open") { await server.openFeedCount() > 0 }
    }

    /// Stops Live updates, as the root view does when what it follows changes, and waits until the
    /// server has closed the feed.
    func stopLiveUpdates() async throws {
        liveUpdates?.cancel()
        try await eventually("the feed is closed") { await server.openFeedCount() == 0 }
    }

    /// Adds a task through New task, and fails the test when it is not saved.
    func addTask(_ title: String, notes: String = "", needsConnection: Bool = false) async throws {
        let failure = await app.tasks.addTask(title: title, notes: notes, needsConnection: needsConnection)
        try #require(failure == nil, "New task failed: \(failure ?? "")")
    }

    /// Stops everything and closes the client.
    func stop() async throws {
        reads.forEach { $0.cancel() }
        liveUpdates?.cancel()
        run?.cancel()
        try await repository.client.close()
    }

    /// Switches Simulate offline, and waits for the client to report it.
    func setOffline(_ isOffline: Bool) async throws {
        app.setSimulateOffline(isOffline)
        try await eventually(isOffline ? "the network is off" : "the network is on") {
            (app.syncStatus.network == .disabled) == isOffline
        }
    }

    /// The task titled `title` in the list, once it is there.
    func task(titled title: String) async throws -> TaskItem {
        try await eventually("“\(title)” is listed") { app.tasks.tasks?.contains { $0.title == title } == true }
        return try #require(app.tasks.tasks?.first { $0.title == title })
    }

    /// The requests the server has had that change something: every method but `GET` and `HEAD`.
    func writeRequests() async -> [DemoServer.RecordedRequest] {
        await server.requests.filter { $0.method != "GET" && $0.method != "HEAD" }
    }
}

/// Waits until `condition` holds, checking every 20 ms, and fails the test after `limit`.
func eventually(
    _ description: String,
    within limit: Duration = .seconds(10),
    _ condition: () async -> Bool
) async throws {
    let deadline = ContinuousClock.now + limit
    while ContinuousClock.now < deadline {
        if await condition() { return }
        try await Task.sleep(for: .milliseconds(20))
    }
    throw TimedOut(description: description)
}

/// A wait that ran out before its condition held.
struct TimedOut: Error, CustomStringConvertible {

    /// What the test waited for.
    let description: String
}

/// A small JPEG, as a photo picked from the library would arrive.
func jpegFixture() throws -> Data {
    let format = UIGraphicsImageRendererFormat()
    format.scale = 1
    let renderer = UIGraphicsImageRenderer(size: CGSize(width: 24, height: 16), format: format)
    let data = renderer.jpegData(withCompressionQuality: 0.9) { context in
        UIColor.systemTeal.setFill()
        context.fill(CGRect(x: 0, y: 0, width: 24, height: 16))
    }
    try #require(!data.isEmpty)
    return data
}

/// The task list as the device holds it, read without the network: empty when nothing is cached.
func cachedTaskRows(_ repository: TaskRepository) async throws -> [TaskRow] {
    do {
        return try await repository.client.from(Tables.tasks)
            .select(readStrategy: .cacheOnly)
            .order("created_at", ascending: false)
            .execute([TaskRow].self)
            .value
    } catch OfflineError.cacheMiss {
        return []
    }
}

/// A live sequence a test iterates in a task of its own, keeping each element, until it ends or the
/// test stops it.
final class LiveRecording<Element: Sendable> {

    /// The elements so far, in order.
    private(set) var values: [Element] = []

    /// The iteration.
    private var iteration: Task<Void, Never>?

    /// Starts iterating `sequence`.
    init<Sequence: AsyncSequence>(_ sequence: Sequence) where Sequence.Element == Element {
        iteration = Task { [weak self] in
            do {
                for try await element in sequence { self?.values.append(element) }
            } catch {
                // The test reads what arrived; an ended read shows as no newer element.
            }
        }
    }

    /// The newest element.
    var last: Element? {
        values.last
    }

    /// Leaves the loop.
    func stop() {
        iteration?.cancel()
    }
}

/// `rows` as the JSON array PostgREST would send.
func jsonText(_ rows: [[String: OfflineJSON]]) throws -> String {
    String(decoding: try JSONEncoder().encode(OfflineJSON.array(rows.map(OfflineJSON.object))), as: UTF8.self)
}

/// A value a test's closure records for the test to read afterward.
final class Recorded<Value> {

    /// What was recorded; `nil` until something was.
    var value: Value?
}
