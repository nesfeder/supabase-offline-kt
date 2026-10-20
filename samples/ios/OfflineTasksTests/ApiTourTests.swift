// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline
import SupabaseOfflineTesting
import Testing

@testable import OfflineTasks

/// The features that show the rest of the API, each on the in-memory client against the demo
/// server: reads by strategy and filter, the kinds of write, the queue as rows, the server's
/// functions, and Live updates.
@Suite("The rest of the API, feature by feature", .serialized)
struct ApiTourTests {

    // MARK: - Writes

    // --8<-- [start:offline-test]
    @Test("Offline, a new task waits in the queue on this device, and is sent once the network is back")
    func offlineWriteWaits() async throws {
        // Given
        let test = TestApp()
        try await test.start()
        try await test.setOffline(true)

        // When
        let outcome = try await test.repository.addTask(title: "Buy milk", notes: nil)

        // Then
        if case .saved = outcome { Issue.record("It was sent while offline") }
        #expect(try await test.repository.pendingMutationCountNow() == 1)
        #expect(await test.writeRequests().isEmpty)

        // When
        try await test.setOffline(false)

        // Then
        try await eventually("it reaches the server") { await test.server.rows(of: Tables.tasks).count == 1 }
        try await eventually("nothing waits") { test.app.syncStatus.pendingMutationCount == 0 }
        try await test.stop()
    }
    // --8<-- [end:offline-test]

    @Test("New task says the time the server stored it; with Needs a connection, offline, nothing is kept")
    func newTaskOutcomes() async throws {
        // Given
        let test = TestApp()
        try await test.start()

        // When: online
        let saved = await test.app.tasks.addTask(title: "Buy milk", notes: "", needsConnection: true)

        // Then: saved, with the server's own time
        #expect(saved == nil)
        try await eventually("the notice says so") { test.app.notice?.hasPrefix("Saved — the server stored it at") == true }

        // When: offline, with Needs a connection
        try await test.setOffline(true)
        let refused = await test.app.tasks.addTask(title: "Call the bank", notes: "", needsConnection: true)

        // Then: refused with Simulate offline's sentence, and neither kept nor queued
        #expect(refused == SampleFailure.sentence(for: networkDisabledError()))
        #expect(test.app.tasks.tasks?.map(\.title) == ["Buy milk"])
        #expect(test.app.syncStatus.pendingMutationCount == 0)

        // When: offline, without it
        let queued = await test.app.tasks.addTask(title: "Water the plants", notes: "", needsConnection: false)

        // Then: kept on this device
        #expect(queued == nil)
        #expect(test.app.notice == "Saved on this device — it syncs when the server can be reached")
        try await test.stop()
    }

    @Test("New task's saved time is the updated_at the server set, not the device's created_at")
    func newTaskShowsTheServersTime() async throws {
        // Given
        let test = TestApp()
        try await test.start()

        // When
        let result = try await test.repository.addTask(title: "Buy milk", notes: nil, needsConnection: true)

        // Then
        let row = try #require(await test.server.rows(of: Tables.tasks).first)
        guard case .string(let updatedAt)? = row["updated_at"], case .saved(let storedAt) = result else {
            Issue.record("Expected a stored row with its updated_at, got \(result)")
            return
        }
        let serverTime = try Date(updatedAt, strategy: Date.ISO8601FormatStyle(includingFractionalSeconds: true))
        #expect(abs(storedAt.timeIntervalSince(serverTime)) < 0.001)
        try await test.stop()
    }

    @Test("Add five sample tasks is one insert of five rows")
    func sampleTasks() async throws {
        // Given
        let test = TestApp()
        try await test.start()
        let before = await test.writeRequests().count

        // When
        test.app.sync.run(.sampleTasks, newestTaskID: nil)

        // Then
        try await eventually("they are listed") { test.app.tasks.tasks?.count == 5 }
        try await eventually("Try it says so") { test.app.sync.tryItResults[.sampleTasks] != nil }
        #expect(test.app.sync.tryItResults[.sampleTasks] == "Added five tasks in one insert")
        #expect(await test.server.rows(of: Tables.tasks).count == 5)
        #expect(await test.writeRequests().count == before + 1)
        #expect(test.app.tasks.tasks?.first?.title == SampleTasks.titles.last)
        try await test.stop()
    }

    @Test("Importing the starter list twice changes nothing, even after one was edited")
    func starterListTwice() async throws {
        // Given: the list imported once, and one of its tasks edited since
        let test = TestApp()
        try await test.start()
        test.app.sync.run(.starterList, newestTaskID: nil)
        try await eventually("it is imported") { await test.server.rows(of: Tables.tasks).count == StarterList.titles.count }
        let edited = try await test.task(titled: StarterList.titles[0])
        try await test.repository.updateTaskText(id: edited.id, title: "Read the tour twice", notes: nil)
        try await eventually("the edit reaches the server") {
            await test.server.rows(of: Tables.tasks).contains { $0["title"] == "Read the tour twice" }
        }

        // When
        test.app.sync.run(.starterList, newestTaskID: nil)
        try await eventually("Try it says so") { test.app.sync.tryItResults[.starterList] != nil }

        // Then
        let rows = await test.server.rows(of: Tables.tasks)
        #expect(rows.count == StarterList.titles.count)
        #expect(rows.contains { $0["title"] == "Read the tour twice" })
        try await test.stop()
    }

    @Test("Duplicate task makes one copy; doing it again brings that copy back in line")
    func duplicateTwice() async throws {
        // Given
        let test = TestApp()
        try await test.start()
        try await test.addTask("Fix the fence", notes: "Two posts")
        let task = try await test.task(titled: "Fix the fence")
        let detail = TaskDetailModel(taskID: task.id, repository: test.repository, failures: test.app.failureSink)
        let watching = Task { await detail.observeTask() }
        try await eventually("the detail has the task") { detail.lookup.task != nil }

        // When
        detail.duplicate()
        try await eventually("the copy is listed") { test.app.tasks.tasks?.count == 2 }
        detail.duplicate()
        try await eventually("the second copy is sent") { await test.writeRequests().filter { $0.method == "POST" }.count == 3 }

        // Then: one copy, with the task's notes
        let rows = await test.server.rows(of: Tables.tasks)
        #expect(rows.count == 2)
        #expect(rows.contains { $0["title"] == "Copy of Fix the fence" && $0["notes"] == "Two posts" })
        watching.cancel()
        try await test.stop()
    }

    @Test("Offline, the grouped delete is refused with the library's own words, and nothing is queued")
    func groupedDeleteOffline() async throws {
        // Given
        let test = TestApp()
        try await test.start()
        try await test.setOffline(true)

        // When
        test.app.sync.run(.groupedDelete, newestTaskID: nil)
        try await eventually("Try it says so") { test.app.sync.tryItResults[.groupedDelete] != nil }

        // Then
        let said = try #require(test.app.sync.tryItResults[.groupedDelete])
        #expect(said.hasPrefix("That change can’t wait in the queue, and the server can’t be reached, so nothing was changed. ("))
        #expect(test.app.syncStatus.pendingMutationCount == 0)
        try await test.stop()
    }

    @Test("Online, the grouped delete removes last week's done tasks, their photos first, and nothing else")
    func groupedDeleteOnline() async throws {
        // Given: done and open tasks from last week, and a done task from today
        let session = DemoSession()
        let userID = try #require(session.userID)
        let lastWeek = TaskQuery.week(before: .now).start + 3_600
        let server = DemoServer(tasks: [
            TaskRow(id: DeviceID.make(), userID: userID, title: "Old and done", notes: nil, done: true, createdAt: lastWeek),
            TaskRow(id: DeviceID.make(), userID: userID, title: "Old and open", notes: nil, done: false, createdAt: lastWeek),
            TaskRow(id: DeviceID.make(), userID: userID, title: "New and done", notes: nil, done: true, createdAt: .now),
        ])
        let test = TestApp(server: server, session: session)
        try await test.start()
        let old = try await test.task(titled: "Old and done")
        try await test.addPhoto(to: old.id)

        // When
        test.app.sync.run(.groupedDelete, newestTaskID: nil)
        try await eventually("Try it says so") { test.app.sync.tryItResults[.groupedDelete] != nil }

        // Then
        #expect(test.app.sync.tryItResults[.groupedDelete] == "Sent at once and applied: last week’s done tasks are gone.")
        let titles = await test.server.rows(of: Tables.tasks).compactMap { row -> String? in
            guard case .string(let title)? = row["title"] else { return nil }
            return title
        }
        #expect(titles.sorted() == ["New and done", "Old and open"])
        #expect(await test.server.objectPaths().isEmpty)
        try await eventually("the list drops it") { test.app.tasks.tasks?.contains { $0.title == "Old and done" } == false }
        try await test.stop()
    }

    // MARK: - The queue

    @Test("A waiting change is listed as a row, and Discard drops it unsent")
    func discardAWaitingChange() async throws {
        // Given
        let test = TestApp()
        try await test.start()
        try await test.setOffline(true)
        try await test.addTask("Buy milk")
        try await eventually("it is listed as waiting") { test.app.sync.waiting.count == 1 }
        let waiting = try #require(test.app.sync.waiting.first)
        #expect(Wording.waitingChange(waiting, now: .now) == "Added “Buy milk” · just now")

        // When
        test.app.sync.discard(waiting)

        // Then
        try await eventually("it is gone") { test.app.sync.waiting.isEmpty }
        try await eventually("the list drops it") { test.app.tasks.tasks?.isEmpty == true }
        try await test.setOffline(false)
        #expect(await test.writeRequests().isEmpty)
        try await test.stop()
    }

    @Test("Sync now reports what it sent, and the badge counts what waits and what was refused")
    func syncNowReport() async throws {
        // Given: one change waiting and one refused
        let test = TestApp()
        try await test.start()
        try await test.setOffline(true)
        test.app.sync.run(.invalidTitle, newestTaskID: nil)
        try await test.addTask("Buy milk")
        try await eventually("both wait") { test.app.syncStatus.pendingMutationCount == 2 }
        #expect(test.app.syncBadge == 2)

        // When
        try await test.repository.setNetworkEnabled(true)
        test.app.sync.syncNow()
        try await eventually("the queue is empty") { test.app.syncStatus.pendingMutationCount == 0 }
        try await eventually("the refusal is counted") { test.app.syncStatus.rejectedMutationCount == 1 }

        // Then: whichever pass sent them, the badge now counts the refusal
        #expect(test.app.syncBadge == 1)
        try await eventually("Sync now has its report") { test.app.sync.lastReport != nil }
        let report = try #require(test.app.sync.lastReport)
        #expect(Wording.syncReport(report).hasPrefix("Sent \(report.syncedCount) · refused \(report.rejected.count)"))
        try await test.stop()
    }

    // MARK: - Reads

    @Test("The chips and the search are filters the server applies, each its own list")
    func serverSideFilters() async throws {
        // Given
        let session = DemoSession()
        let userID = try #require(session.userID)
        let server = DemoServer(tasks: [
            TaskRow(id: DeviceID.make(), userID: userID, title: "Buy milk", notes: nil, done: false, createdAt: .now - 60),
            TaskRow(id: DeviceID.make(), userID: userID, title: "Buy bread", notes: nil, done: true, createdAt: .now - 120),
            TaskRow(id: DeviceID.make(), userID: userID, title: "Old chore", notes: nil, done: false, createdAt: .now - 30 * 86_400),
        ])
        let test = TestApp(server: server, session: session)
        try await test.start()

        // When / Then
        try await test.show(TaskQuery(filter: .open))
        try await eventually("Open shows the open tasks") { test.app.tasks.tasks?.map(\.title) == ["Buy milk", "Old chore"] }
        try await test.show(TaskQuery(filter: .done))
        try await eventually("Done shows the done task") { test.app.tasks.tasks?.map(\.title) == ["Buy bread"] }
        try await test.show(TaskQuery(filter: .recent))
        try await eventually("Recent shows this week's") { test.app.tasks.tasks?.map(\.title) == ["Buy milk", "Buy bread"] }
        try await test.show(TaskQuery(filter: .all, search: "BUY"))
        try await eventually("the search shows the titles holding it") {
            test.app.tasks.tasks?.map(\.title) == ["Buy milk", "Buy bread"]
        }
        let requests = await test.server.requests.map(\.path)
        #expect(requests.filter { $0 == "/rest/v1/tasks" }.count >= 5)
        try await test.stop()
    }

    @Test("A live read that fails offline is said, and read again when the network comes back")
    func failedLiveReadReadsAgain() async throws {
        // Given: a done task on the server, and Prefer fresh data on
        let session = DemoSession()
        let userID = try #require(session.userID)
        let server = DemoServer(tasks: [
            TaskRow(id: DeviceID.make(), userID: userID, title: "Buy bread", notes: nil, done: true, createdAt: .now)
        ])
        let test = TestApp(server: server, session: session)
        try await test.start()
        test.app.prefersFreshData = true

        // When: offline, a chip never read online, which the server must answer first
        try await test.setOffline(true)
        try await test.show(TaskQuery(filter: .done))

        // Then: the failure is said in the app's words, and the list has nothing
        try await eventually("the app says why") { test.app.failure != nil }
        #expect(test.app.failure?.message == SampleFailure.sentence(for: networkDisabledError()))
        #expect(test.app.tasks.visibleRows == nil)

        // When: the network comes back, with no other change
        test.app.dismissFailure()
        try await test.setOffline(false)

        // Then: the same read starts again and shows the server's rows
        try await eventually("the list is read again") { test.app.tasks.tasks?.map(\.title) == ["Buy bread"] }
        try await test.stop()
    }

    @Test("A live read the server fails is said, and read again on pull to refresh")
    func failedLiveReadReadsAgainOnPull() async throws {
        // Given: a done task on the server, Prefer fresh data on, and a server failing the Done list
        let session = DemoSession()
        let userID = try #require(session.userID)
        let server = DemoServer(tasks: [
            TaskRow(id: DeviceID.make(), userID: userID, title: "Buy bread", notes: nil, done: true, createdAt: .now)
        ])
        let test = TestApp(server: server, session: session)
        try await test.start()
        test.app.prefersFreshData = true
        await server.intercept { request, _ in
            let isDoneList = request.method == "GET" && request.url.query()?.contains("done=is.true") == true
            return isDoneList ? .json(#"{"code":"XX000","message":"Internal error","details":null,"hint":null}"#, status: 500) : nil
        }

        // When
        try await test.show(TaskQuery(filter: .done))

        // Then: the failure is said, and the list has nothing
        try await eventually("the app says why") { test.app.failure != nil }
        #expect(test.app.tasks.visibleRows == nil)

        // When: the server recovers, and the user pulls to refresh
        test.app.dismissFailure()
        await server.intercept(nil)
        await test.app.tasks.refreshList()

        // Then: the live read starts again and shows the server's rows
        try await eventually("the list is read again") { test.app.tasks.tasks?.map(\.title) == ["Buy bread"] }
        try await test.stop()
    }

    @Test("A pull to refresh made while a failed read is still starting to wait is not missed", .timeLimit(.minutes(1)))
    func readAgainRequestBeforeTheWait() async {
        // Given: a read that failed, having seen no request yet
        let requests = ReadAgainRequests()
        let seen = requests.sent

        // When: the user pulls to refresh before the read waits
        requests.send()

        // Then: the wait returns at once
        await requests.next(after: seen)
    }

    @Test("Offline, pull to refresh answers from the device and says how old the list is")
    func pullToRefreshOffline() async throws {
        // Given: the list read online, then three minutes on the client's clock, offline
        let clock = ManualClock()
        let test = TestApp(clock: clock)
        try await test.start()
        try await test.addTask("Buy milk")
        try await eventually("it reaches the server") { await test.server.rows(of: Tables.tasks).count == 1 }
        await test.app.tasks.refreshList()
        let online = try #require(test.app.tasks.provenance)
        #expect(!online.isFromCache)
        clock.advance(by: .seconds(180))
        try await test.setOffline(true)

        // When
        await test.app.tasks.refreshList()

        // Then
        let offline = try #require(test.app.tasks.provenance)
        #expect(offline.isFromCache)
        #expect(Wording.caption(offline, pendingCount: 0, now: clock.now) == "From this device, 3 min ago")
        #expect(test.app.failure == nil)
        try await test.stop()
    }

    @Test("Offline, a saved list older than a day is not an answer: pull to refresh says why")
    func pullToRefreshTooOld() async throws {
        // Given: the list read online, then a day and a minute later on the client's clock, offline
        let clock = ManualClock(now: Date(timeIntervalSince1970: 1_760_000_000))
        let test = TestApp(clock: clock)
        try await test.start()
        await test.app.tasks.refreshList()
        clock.set(to: clock.now + 86_460)
        try await test.setOffline(true)

        // When
        await test.app.tasks.refreshList()

        // Then
        try await eventually("the app says why") { test.app.failure != nil }
        #expect(test.app.failure?.message == SampleFailure.sentence(for: networkDisabledError()))
        try await test.stop()
    }

    @Test("The open count at launch is read from the device when it read the list in the last hour")
    func openCountAtLaunch() async throws {
        // Given: the open tasks read once, online
        let test = TestApp()
        try await test.start()
        try await test.addTask("Buy milk")
        _ = try await test.repository.openTaskCountAtLaunch()
        try await test.setOffline(true)

        // When: offline, read again
        let count = try await test.repository.openTaskCountAtLaunch()

        // Then
        #expect(count == 1)
        try await test.stop()
    }

    // MARK: - The server

    @Test("Mark all done and the server's counts go through its database functions; offline, refused")
    func databaseFunctions() async throws {
        // Given
        let test = TestApp()
        try await test.start()
        try await test.addTask("Buy milk")
        try await test.addTask("Call the bank")

        // When
        await test.app.tasks.markAllDone()
        test.app.sync.askServer()

        // Then
        try await eventually("the list shows them done") { test.app.tasks.tasks?.allSatisfy(\.isDone) == true }
        try await eventually("the server says so") { test.app.sync.serverSummary != nil }
        #expect(test.app.sync.serverSummary == "On the server: 2 of 2 done · 2 this week")
        #expect(await test.server.requests.contains { $0.path == "/rest/v1/rpc/complete_all_tasks" })

        // When: offline
        try await test.setOffline(true)
        await test.app.tasks.markAllDone()

        // Then
        try await eventually("the app says why") { test.app.failure != nil }
        #expect(test.app.failure?.message == SampleFailure.sentence(for: networkDisabledError()))
        try await test.stop()
    }

    @Test("Live updates: a task changed on another device shows without this app asking")
    func liveUpdates() async throws {
        // Given: a task the server holds, on the client's clock, and Live updates on
        let clock = ManualClock(now: Date(timeIntervalSince1970: 1_760_000_000))
        let test = TestApp(clock: clock)
        try await test.start()
        try await test.addTask("Buy milk")
        let task = try await test.savedTask(titled: "Buy milk")
        clock.advance(by: .seconds(1))
        try await test.startLiveUpdates()
        // Each join reads the lists on screen again, since Realtime does not replay what it missed.
        // The change below waits for that answer to be shown: a change applied while the read is
        // still out makes its answer stale, and the list is read once more.
        try await eventually("the task list is read again, and the answer shown") {
            guard let provenance = test.app.tasks.provenance else { return false }
            return !provenance.isFromCache && provenance.fetchedAt == clock.now
        }
        try await eventually("the Sync tab says so") { test.app.realtimeStatus == .subscribed }
        let readsBefore = await test.server.requests.filter { $0.method == "GET" }.count

        // When
        await test.server.changeElsewhere(task: task.id, ["title": "Buy oat milk", "done": true])

        // Then
        try await eventually("the change shows") { test.app.tasks.tasks?.first?.title == "Buy oat milk" }
        #expect(test.app.tasks.tasks?.first?.isDone == true)
        #expect(await test.server.requests.filter { $0.method == "GET" }.count == readsBefore)
        try await test.stop()
    }

    @Test("Live updates stops when the network drops, and catches up on what it missed when it is back")
    func liveUpdatesCatchUpAfterADrop() async throws {
        // Given: Live updates following a task, on a device whose connectivity the test sets
        let (signal, network) = AsyncStream<Bool>.makeStream()
        network.yield(true)
        let test = TestApp(networkAvailability: { signal })
        try await test.start()
        try await eventually("the device is online") { test.app.syncStatus.network == .online }
        try await test.addTask("Buy milk")
        let task = try await test.savedTask(titled: "Buy milk")
        try await test.startLiveUpdates()
        try await eventually("the channel is joined") { test.app.realtimeStatus == .subscribed }

        // When: the network drops, and the task changes on another device meanwhile
        network.yield(false)
        try await eventually("the client leaves the channel") { test.app.realtimeStatus == .paused }
        try await eventually("the channel is left") { test.server.realtime.subscribedTables.isEmpty }
        await test.server.changeElsewhere(task: task.id, ["title": "Buy oat milk"])
        #expect(test.server.realtime.droppedReportCount == 1)
        let taskReadsBefore = await test.taskListReads()

        // Then: back online, the client joins again and reads the list again, so the change shows
        network.yield(true)
        try await eventually("the client joins again") { test.app.realtimeStatus == .subscribed }
        try await eventually("the task list is read again") { await test.taskListReads() > taskReadsBefore }
        try await eventually("the change made during the drop shows") { test.app.tasks.tasks?.first?.title == "Buy oat milk" }
        network.finish()
        try await test.stop()
    }
}
