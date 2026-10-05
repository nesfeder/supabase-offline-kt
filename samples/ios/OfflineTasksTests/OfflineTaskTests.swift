// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline
import SupabaseOfflineTesting
import Testing

@testable import OfflineTasks

/// The task screens' models on the in-memory client: offline writes, refused writes and Start over.
@Suite("Tasks on the offline client", .serialized)
struct OfflineTaskTests {

    @Test("Offline, a task is added, edited, completed and deleted at once, and reaches the server once online")
    func offlineAddEditCompleteDelete() async throws {
        // Given
        let test = TestApp()
        try await test.start()
        try await test.setOffline(true)

        // When
        test.app.tasks.addTask(title: "Buy milk", notes: "Two liters")
        test.app.tasks.addTask(title: "Call the bank", notes: "")
        let milk = try await test.task(titled: "Buy milk")
        let bank = try await test.task(titled: "Call the bank")
        let detail = TaskDetailModel(taskID: milk.id, repository: test.repository, failures: test.app.failureSink)
        let watching = Task { await detail.observeTask() }
        try await eventually("the detail has the task") { detail.lookup.task != nil }
        detail.editTitle("Buy oat milk")
        detail.editNotes("")
        detail.saveNow()
        watching.cancel()
        let oatMilk = try await test.task(titled: "Buy oat milk")
        test.app.tasks.setDone(oatMilk, isDone: true)
        test.app.tasks.delete(bank)
        try await eventually("the edits show") {
            test.app.tasks.tasks?.map(\.title) == ["Buy oat milk"] && test.app.tasks.tasks?.first?.isDone == true
        }

        // Then: shown at once, marked, counted, and nothing sent
        let shown = try #require(test.app.tasks.tasks?.first)
        #expect(shown.notes == nil)
        #expect(shown.hasPendingMutations)
        #expect(test.app.syncStatus.pendingMutationCount > 0)
        #expect(test.app.headline == .offline(pendingCount: test.app.syncStatus.pendingMutationCount))
        #expect(await test.writeRequests().isEmpty)

        // When
        try await test.setOffline(false)

        // Then: the queue is sent, in the order the changes were made
        try await eventually("the queue is empty") { test.app.syncStatus.pendingMutationCount == 0 }
        let rows = await test.server.rows(of: Tables.tasks)
        #expect(rows.count == 1)
        #expect(rows.first?["id"] == .string(milk.id))
        #expect(rows.first?["title"] == .string("Buy oat milk"))
        #expect(rows.first?["notes"] == .null)
        #expect(rows.first?["done"] == .bool(true))
        #expect(rows.first?["user_id"] == .string(try #require(test.session.userID)))
        let writes = await test.writeRequests().map { "\($0.method) \($0.path)" }
        let firstInsert = try #require(writes.firstIndex(of: "POST /rest/v1/tasks"))
        let firstUpdate = try #require(writes.firstIndex(of: "PATCH /rest/v1/tasks"))
        #expect(firstInsert < firstUpdate)
        try await eventually("the mark clears") { test.app.tasks.tasks?.first?.hasPendingMutations == false }
        try await test.stop()
    }

    @Test("Ids are made on the device in lower case, as Postgres writes them")
    func idsAreLowerCase() async throws {
        // Given
        let test = TestApp()
        try await test.start()

        // When
        test.app.tasks.addTask(title: "Water the plants", notes: "")
        let task = try await test.task(titled: "Water the plants")

        // Then
        #expect(task.id == task.id.lowercased())
        #expect(UUID(uuidString: task.id) != nil)
        try await eventually("the server has it") { await test.server.rows(of: Tables.tasks).count == 1 }
        #expect(await test.server.rows(of: Tables.tasks).first?["id"] == .string(task.id))
        try await test.stop()
    }

    @Test("A blank title queued offline is refused when it syncs, listed with the server's answer, and dismissed")
    func refusedWriteIsListedAndDismissed() async throws {
        // Given
        let test = TestApp()
        try await test.start()
        try await test.setOffline(true)
        test.app.sync.queueInvalidTitle()
        try await eventually("it is queued") { test.app.syncStatus.pendingMutationCount == 1 }

        // When
        try await test.setOffline(false)

        // Then
        try await eventually("it is refused") { test.app.sync.rejected.count == 1 }
        let refusal = try #require(test.app.sync.rejected.first)
        #expect(refusal.serverError?.status == 400)
        #expect(refusal.serverError?.code == "23514")
        #expect(refusal.reason == "The server refused it: a title can’t be blank or longer than 200 characters.")
        #expect(Wording.title(refusal.taskTitle) == "(no title)")
        try await eventually("the app says so") { test.app.failure != nil }
        #expect(test.app.failure?.message == "Couldn’t save “(no title)” — the server refused it")
        #expect(test.app.failure?.opensRefusedChanges == true)
        try await eventually("the list drops it") { test.app.tasks.tasks?.isEmpty == true }
        #expect(await test.server.rows(of: Tables.tasks).isEmpty)

        // When
        test.app.sync.dismiss(refusal)

        // Then
        try await eventually("it is dismissed") {
            test.app.sync.rejected.isEmpty && test.app.syncStatus.rejectedMutationCount == 0
        }
        try await test.stop()
    }

    @Test("A blank title sent online is refused at once and said in an alert, not listed")
    func refusedAtOnceIsAnAlert() async throws {
        // Given
        let test = TestApp()
        try await test.start()

        // When
        test.app.sync.queueInvalidTitle()

        // Then
        try await eventually("the app says so") { test.app.failure != nil }
        #expect(
            test.app.failure?.message
                == "Couldn’t save “(no title)”. The server refused it: a title can’t be blank or longer than 200 characters."
        )
        #expect(test.app.sync.rejected.isEmpty)
        #expect(test.app.tasks.tasks?.isEmpty == true)
        #expect(test.app.syncStatus.pendingMutationCount == 0)
        try await test.stop()
    }

    @Test("Start over sends nothing of the first user with the new user's token, and shows nothing of theirs")
    func startOverLeavesNothingOfTheFirstUser() async throws {
        // Given: the first user has a task on the server and a write the server keeps failing
        let test = TestApp()
        try await test.start()
        let firstUser = try #require(test.session.userID)
        test.app.tasks.addTask(title: "Synced before", notes: "")
        try await eventually("it reaches the server") { await test.server.rows(of: Tables.tasks).count == 1 }
        await test.server.intercept { request, userID in
            let isStuck = request.method == "POST" && String(decoding: request.body, as: UTF8.self).contains("Stuck in the queue")
            return isStuck && userID == firstUser ? .status(503) : nil
        }
        test.app.tasks.addTask(title: "Stuck in the queue", notes: "")
        try await eventually("the server failed it once") {
            await test.server.requests.contains { $0.body.contains("Stuck in the queue") }
        }
        #expect(test.app.syncStatus.pendingMutationCount == 1)
        let requestsBefore = await test.server.requests.count
        let queueAtSignOut = Recorded<[PendingMutation]>()
        let client = test.repository.client
        test.session.beforeSignOut = {
            queueAtSignOut.value = try? await client.pendingMutations().first()
        }

        // When
        test.app.sync.startOver()
        try await eventually("a new user is signed in") { test.session.userID != firstUser && !test.app.sync.isStartingOver }
        let secondUser = try #require(test.session.userID)
        test.startReads()
        try await eventually("the new user's empty list shows") { test.app.tasks.tasks == [] }

        // Then: nothing of the first user's waited in the queue once the deletes were done, and
        // the network the app turned off for the switch is back on
        #expect(queueAtSignOut.value?.isEmpty == true)
        try await eventually("the network is back on") { test.app.syncStatus.network != .disabled }
        // And the server lost the first user's rows, and the queued write was never sent again
        let requests = await test.server.requests
        let after = requests[requestsBefore...]
        #expect(!after.contains { $0.body.contains("Stuck in the queue") })
        let firstOfSecondUser = try #require(requests.firstIndex { $0.userID == secondUser })
        #expect(!requests[firstOfSecondUser...].contains { $0.userID == firstUser })
        #expect(await test.server.rows(of: Tables.tasks).isEmpty)
        // And the device shows nothing of the first user
        #expect(test.app.syncStatus.pendingMutationCount == 0)
        #expect(test.app.syncStatus.rejectedMutationCount == 0)
        #expect(test.app.sync.rejected.isEmpty)
        #expect(try await test.repository.client.pendingMutations().first().isEmpty)
        try await test.stop()
    }

    @Test("With Simulate offline on, Start over refuses and changes nothing")
    func startOverWhileSimulatingOffline() async throws {
        // Given: a task on the server and Simulate offline on
        let test = TestApp()
        try await test.start()
        let user = try #require(test.session.userID)
        test.app.tasks.addTask(title: "Walk the dog", notes: "")
        try await eventually("it reaches the server") { await test.server.rows(of: Tables.tasks).count == 1 }
        try await test.setOffline(true)

        // When
        test.app.sync.startOver()

        // Then: refused for want of the network, with the user, the task and the switch as they were
        try await eventually("the app says why") { test.app.failure != nil }
        #expect(test.app.failure?.message == "That needs the network, and you’re offline.")
        try await eventually("Start over is done") { !test.app.sync.isStartingOver }
        #expect(test.session.userID == user)
        #expect(test.app.syncStatus.network == .disabled)
        #expect(test.app.tasks.tasks?.map(\.title) == ["Walk the dog"])
        #expect(await test.server.rows(of: Tables.tasks).count == 1)
        try await test.stop()
    }

    @Test("Start over with a list open: offline, the second user's list holds nothing of the first user's")
    func startOverWithAListOpen() async throws {
        // Given: the first user's task on the server and in the cache, a live read of the list open
        // for the whole of Start over, as an open task screen holds one, and a sign-out that takes
        // a moment, as Auth's does
        let test = TestApp(signOutDelay: .milliseconds(300))
        try await test.start()
        let firstUser = try #require(test.session.userID)
        test.app.tasks.addTask(title: "The first user's task", notes: "")
        try await eventually("it reaches the server") { await test.server.rows(of: Tables.tasks).count == 1 }
        let openList = LiveRecording(test.repository.taskSnapshots())
        try await eventually("the open list shows it") { openList.last?.result?.count == 1 }
        // A list read with the first user's token is answered with their rows, as one that reached
        // the server before the deletes would be; the second user starts with no network.
        let firstUsersList = try jsonText(await test.server.rows(of: Tables.tasks))
        await test.server.intercept { request, userID in
            guard let userID else { return nil }
            guard userID == firstUser else { return .noConnection }
            let isListRead = request.method == "GET" && request.url.path().hasSuffix("/rest/v1/\(Tables.tasks)")
            return isListRead ? .json(firstUsersList) : nil
        }

        // When
        test.app.sync.startOver()
        try await eventually("a new user is signed in") { test.session.userID != firstUser && !test.app.sync.isStartingOver }
        let secondUser = try #require(test.session.userID)
        try await test.setOffline(true)

        // Then: nothing of the first user's is on the device, shown, or sent with the new token
        let cached = try await cachedTaskRows(test.repository)
        #expect(cached.isEmpty)
        try await eventually("the open list shows nothing of the first user's") {
            openList.last.map { $0.result?.contains { $0.userID == firstUser } != true } ?? false
        }
        #expect(await test.server.rows(of: Tables.tasks).isEmpty)
        let requests = await test.server.requests
        if let firstOfSecondUser = requests.firstIndex(where: { $0.userID == secondUser }) {
            #expect(!requests[firstOfSecondUser...].contains { $0.userID == firstUser })
        }
        openList.stop()
        try await test.stop()
    }

    @Test("Deleting a task online deletes its files, then its photo rows, then the task")
    func deleteOrder() async throws {
        // Given
        let test = TestApp()
        try await test.start()
        test.app.tasks.addTask(title: "Fix the fence", notes: "")
        let task = try await test.task(titled: "Fix the fence")
        try await test.repository.attachPhoto(taskID: task.id, photo: try await PhotoEncoder.encode(jpegFixture()))
        #expect(await test.server.objectPaths().count == 1)
        let requestsBefore = await test.server.requests.count

        // When
        test.app.tasks.delete(task)

        // Then
        try await eventually("the task is gone from the server") { await test.server.rows(of: Tables.tasks).isEmpty }
        let writes = await test.server.requests[requestsBefore...]
            .filter { $0.method != "GET" && $0.method != "HEAD" }
            .map { "\($0.method) \($0.path)" }
        #expect(writes == ["DELETE /storage/v1/object/task-photos", "DELETE /rest/v1/task_photos", "DELETE /rest/v1/tasks"])
        #expect(await test.server.objectPaths().isEmpty)
        #expect(await test.server.rows(of: Tables.taskPhotos).isEmpty)
        try await test.stop()
    }
}
