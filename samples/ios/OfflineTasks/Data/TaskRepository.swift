// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline

/// Everything the screens read and write, for the signed-in user: every call the app makes to the
/// offline client is in this file and its three extensions, `TaskRepository+Photos.swift`
/// (Storage), `TaskRepository+PendingPhotos.swift` (photos waiting to upload) and
/// `TaskRepository+Server.swift` (database functions and server-pushed rows).
///
/// Reads are live: each is a sequence of snapshots served from the device first and refreshed from
/// the server when it can be reached. Writes change the device's copy at once and wait in the
/// mutation queue until the server has them, so they work offline. Storage writes, database
/// functions and counts are the exceptions: they need the network.
///
/// What throws: a write sent at once that the server refuses throws
/// ``SampleFailure/writeRefused(taskTitle:serverError:)``; a write that waited in the queue never
/// throws, and its refusal goes to the rejected list. Everything else arrives as the library threw
/// it, an ``OfflineError``, which ``SampleFailure/sentence(for:)`` puts in words.
///
/// Photos are the one write the app queues itself: a picked photo waits in ``pendingPhotos`` until
/// ``PendingPhotoUploader`` uploads it (`TaskRepository+PendingPhotos.swift`).
final class TaskRepository {

    /// The app's one offline client.
    let client: SupabaseOfflineClient

    /// Who is signed in.
    let session: any UserSession

    /// How a cached photo's bytes are read.
    let photoBytes: PhotoBytesSource

    /// The photos picked on this device that wait to upload: the app's own queue.
    let pendingPhotos: PendingPhotoQueue

    /// How many objects one Storage delete names at most.
    static let storageDeleteBatch = 100

    /// How many photo rows one read of their paths asks for: a project answers at most its row
    /// limit (1,000 unless changed) to one request.
    static let pathPageSize = 1_000

    /// How old a saved list may be and still answer a pull to refresh the server cannot.
    static let refreshMaxAge = Duration.seconds(24 * 60 * 60)

    /// How old the saved list may be and still give the open count at launch, without asking.
    static let badgeMaxAge = Duration.seconds(60 * 60)

    /// A repository over `client` for `session`'s user, reading cached photos as `photoBytes` says,
    /// with the photos waiting to upload in `pendingPhotos`.
    init(
        client: SupabaseOfflineClient,
        session: any UserSession,
        photoBytes: PhotoBytesSource = .cachedFile,
        pendingPhotos: PendingPhotoQueue = PendingPhotoQueue()
    ) {
        self.client = client
        self.session = session
        self.photoBytes = photoBytes
        self.pendingPhotos = pendingPhotos
    }

    // MARK: - Live reads

    // --8<-- [start:first-observed-read]
    /// Every task of the user, newest first, as the device holds it and as the server refreshes
    /// it: the list the other screens share. The task screen picks its task out of it, because
    /// each query is cached on its own, and offline a query never fetched has nothing to show.
    func taskSnapshots(readStrategy: SequenceReadStrategy = .cacheFirstThenNetwork) -> QuerySnapshotSequence<[TaskRow]> {
        client.from(Tables.tasks)
            .selectAsSequence(as: [TaskRow].self, readStrategy: readStrategy)
            .order("created_at", ascending: false)
    }
    // --8<-- [end:first-observed-read]

    // --8<-- [start:reactive-strategies]
    /// How the task list reads, as Settings → Prefer fresh data says: the device's rows at once,
    /// refreshed from the server alongside (the default), or the server's answer first, the
    /// device's rows only when the server cannot be reached.
    static func readStrategy(prefersFreshData: Bool) -> SequenceReadStrategy {
        prefersFreshData ? .networkFirstThenCache : .cacheFirstThenNetwork
    }
    // --8<-- [end:reactive-strategies]

    // --8<-- [start:task-query]
    /// The tasks `query` picks, newest first: its chip and its search are filters the server
    /// applies, and each combination is cached on its own, so offline the device shows the ones
    /// it has read before.
    func taskSnapshots(_ query: TaskQuery, readStrategy: SequenceReadStrategy) -> QuerySnapshotSequence<[TaskRow]> {
        let tasks = client.from(Tables.tasks).selectAsSequence(as: [TaskRow].self, readStrategy: readStrategy)
        return query.applied(to: tasks).order("created_at", ascending: false)
    }
    // --8<-- [end:task-query]

    /// Every photo row the user has, newest first; each task's screen picks out its own, as with
    /// ``taskSnapshots(readStrategy:)``.
    func photoSnapshots(readStrategy: SequenceReadStrategy = .cacheFirstThenNetwork) -> QuerySnapshotSequence<[TaskPhotoRow]> {
        client.from(Tables.taskPhotos)
            .selectAsSequence(as: [TaskPhotoRow].self, readStrategy: readStrategy)
            .order("created_at", ascending: false)
    }

    // --8<-- [start:photo-rows-in]
    /// The photo rows of `taskIDs`, the tasks on screen, in one query: the list shows how many
    /// photos each one has.
    func photoSnapshots(ofTasks taskIDs: [String]) -> QuerySnapshotSequence<[TaskPhotoRow]> {
        client.from(Tables.taskPhotos)
            .selectAsSequence(as: [TaskPhotoRow].self)
            .in("task_id", values: taskIDs)
            .order("created_at", ascending: true)
    }
    // --8<-- [end:photo-rows-in]

    // --8<-- [start:photos-page]
    /// The user's newest `count` photos, for the Photos grid, which asks for a page more each
    /// time it reaches the end.
    func photoPage(count: Int) -> QuerySnapshotSequence<[TaskPhotoRow]> {
        client.from(Tables.taskPhotos)
            .selectAsSequence(as: [TaskPhotoRow].self)
            .order("created_at", ascending: false)
            .limit(count)
    }
    // --8<-- [end:photos-page]

    // --8<-- [start:pending]
    /// The writes waiting in the mutation queue, of `table` or of every table, in the order they
    /// will be sent, as the queue changes.
    func pendingMutations(table: String? = nil) -> PendingMutationSequence {
        client.pendingMutations(table: table)
    }

    /// How many writes wait for `table`, or for every table, as the queue changes.
    func pendingMutationCounts(table: String? = nil) -> PendingMutationCountSequence {
        client.pendingMutationCountSequence(table: table)
    }
    // --8<-- [end:pending]

    // --8<-- [start:discard]
    /// Removes a waiting write without sending it: it is lost, and the rows it changed show again
    /// as the server last sent them.
    func discardPendingMutation(id: Int64) async throws {
        try await client.discardPendingMutation(id: id)
    }
    // --8<-- [end:discard]

    // --8<-- [start:rejected-list]
    /// The writes the server refused on replay, oldest first, until each is dismissed or retried.
    func rejectedMutations() -> RejectedMutationSequence {
        client.rejectedMutations()
    }

    /// The refused writes as they are now, read once: the ones the app found at launch.
    func rejectedMutationsNow() async throws -> [RejectedMutation] {
        try await client.rejectedMutations().first()
    }
    // --8<-- [end:rejected-list]

    // --8<-- [start:sync-status]
    /// The network, the queue and the last sync, as one value: ``SyncStatusSequence/value`` now,
    /// or iterate it for each change.
    var syncStatus: SyncStatusSequence {
        client.syncStatus
    }
    // --8<-- [end:sync-status]

    // --8<-- [start:pending-count]
    /// How many writes wait, read once: the Sync tab's badge at launch, before the first status.
    func pendingMutationCountNow() async throws -> Int {
        try await client.pendingMutationCount()
    }
    // --8<-- [end:pending-count]

    // MARK: - One-shot reads

    // --8<-- [start:first-read]
    /// Asks the server for `query`'s tasks again, as pull to refresh does: the same query as the
    /// list's live read, so its answer replaces that query's cached rows and every reader of it
    /// sees them. When the server cannot be reached, the saved rows answer, unless they are older
    /// than ``refreshMaxAge``; either way the answer says where it came from.
    func refreshTasks(_ query: TaskQuery) async throws -> ListProvenance {
        let request = client.from(Tables.tasks).select(readStrategy: .networkFirst(maxAge: Self.refreshMaxAge))
        let response = try await query.applied(to: request)
            .order("created_at", ascending: false)
            .execute([TaskRow].self)
        return ListProvenance(response)
    }
    // --8<-- [end:first-read]

    // --8<-- [start:badge-read]
    /// How many tasks are open, for the Tasks tab's badge at launch: from the device when it read
    /// them less than an hour ago, from the server otherwise.
    func openTaskCountAtLaunch() async throws -> Int {
        try await client.from(Tables.tasks)
            .select("id", readStrategy: .cacheFirst(maxAge: Self.badgeMaxAge))
            .eq("done", value: false)
            .execute([RowKey].self)
            .value
            .count
    }
    // --8<-- [end:badge-read]

    // --8<-- [start:cache-only]
    /// The photo rows of task `taskID` the device holds, from the query ``photoSnapshots(readStrategy:)``
    /// reads, without asking the server; `nil` when the device holds nothing for that query.
    func cachedPhotos(ofTask taskID: String) async throws -> [TaskPhotoRow]? {
        do {
            return try await client.from(Tables.taskPhotos)
                .select(readStrategy: .cacheOnly)
                .order("created_at", ascending: false)
                .execute([TaskPhotoRow].self)
                .value
                .filter { $0.taskID == taskID }
        } catch OfflineError.cacheMiss {
            return nil
        }
    }
    // --8<-- [end:cache-only]

    // --8<-- [start:operator-filter]
    /// How many tasks the server holds with notes, through the generic filter that names its
    /// operator as text; it asks the server, so it needs the network.
    func tasksWithNotesOnServer() async throws -> Int {
        try await client.from(Tables.tasks)
            .select("id", readStrategy: .networkOnly)
            .filter("notes", operator: "not.is", value: "null")
            .execute([RowKey].self)
            .value
            .count
    }
    // --8<-- [end:operator-filter]

    // --8<-- [start:realtime-switch]
    /// Where the client's Realtime channel stands: Live updates' status.
    var realtimeStatus: RealtimeStatusSequence {
        client.realtimeStatus
    }

    /// Turns the client's Realtime channel off, which leaves it at once, or on, which joins it
    /// again as soon as the network allows and reads both tables again. Live updates' switch.
    func setLiveUpdates(_ isOn: Bool) async throws {
        if isOn {
            try await client.enableRealtime()
        } else {
            try await client.disableRealtime()
        }
    }
    // --8<-- [end:realtime-switch]

    // MARK: - Task writes

    // --8<-- [start:first-write]
    /// Adds task `id` (a new one unless given) for the signed-in user, with the device's clock as
    /// its creation time, and says what happened: the server stored it, at the `updated_at` it set
    /// and gives back, or it waits in the queue on this device.
    func addTask(id: String = DeviceID.make(), title: String, notes: String?, needsConnection: Bool = false) async throws -> NewTaskResult {
        let row = NewTaskRow(
            id: id,
            userID: try signedInUserID(),
            title: title,
            notes: notes,
            done: false,
            createdAt: .now
        )
        let outcome = try await write(taskTitle: title) {
            try await client.from(Tables.tasks)
                .insert(row, mutationStrategy: Self.mutationStrategy(needsConnection: needsConnection))
                .select()
                .single()
                .execute(StoredTaskRow.self)
        }
        switch outcome {
        case .applied(let result):
            // A write sent only straight to the server is never put into the device's lists:
            // the app puts in the row the server stored, as a record Realtime reports is applied.
            if needsConnection { try await client.applyServerRecord(table: Tables.tasks, record: result.value) }
            return .saved(storedAt: result.value.updatedAt)
        case .queued(let pendingMutation):
            return .savedOnDevice(pendingMutationID: pendingMutation.id)
        }
    }
    // --8<-- [end:first-write]

    // --8<-- [start:mutation-strategies]
    /// How New task sends its row: through the queue, so it is kept while offline (the default),
    /// or straight to the server only, failing offline, when Needs a connection is on.
    static func mutationStrategy(needsConnection: Bool) -> MutationStrategy {
        needsConnection ? .onlineOnly : .queueIfOffline
    }
    // --8<-- [end:mutation-strategies]

    // --8<-- [start:typed-update]
    /// Sets a task's title and notes, as one typed patch; `nil` notes clears them on the server
    /// too.
    @discardableResult
    func updateTaskText(id: String, title: String, notes: String?) async throws -> MutationOutcome<Void> {
        try await write(taskTitle: title) {
            try await client.from(Tables.tasks)
                .update(TaskTextPatch(title: title, notes: notes))
                .eq("id", value: id)
                .execute()
        }
    }
    // --8<-- [end:typed-update]

    // --8<-- [start:body-update]
    /// Marks a task done, or open again, with the update's body written as JSON values.
    @discardableResult
    func setTaskDone(id: String, isDone: Bool) async throws -> MutationOutcome<Void> {
        try await write(taskTitle: nil) {
            try await client.from(Tables.tasks).update(["done": .bool(isDone)]).eq("id", value: id).execute()
        }
    }
    // --8<-- [end:body-update]

    // --8<-- [start:duplicate-task]
    /// Makes the task's copy. The copy's id is made from the task's, so doing it again writes over
    /// that copy rather than adding another: an upsert, merged by the table's key.
    func duplicateTask(_ task: TaskItem) async throws -> MutationOutcome<Void> {
        let copy = NewTaskRow(
            id: DeviceID.derived(from: "copy of \(task.id)"),
            userID: task.userID,
            title: Wording.copyTitle(of: task.title),
            notes: task.notes,
            done: false,
            createdAt: .now
        )
        return try await write(taskTitle: copy.title) {
            try await client.from(Tables.tasks).upsert(copy).execute()
        }
    }
    // --8<-- [end:duplicate-task]

    // --8<-- [start:insert-list]
    /// Adds five sample tasks in one insert of a list of rows: one write in the queue, sent as one
    /// request.
    func addSampleTasks() async throws -> MutationOutcome<Void> {
        let userID = try signedInUserID()
        let now = Date.now
        let rows = SampleTasks.titles.enumerated().map { index, title in
            NewTaskRow(
                id: DeviceID.make(),
                userID: userID,
                title: title,
                notes: nil,
                done: false,
                // A second apart, so they list in the order written.
                createdAt: now - Double(SampleTasks.titles.count - index)
            )
        }
        return try await write(taskTitle: nil) {
            try await client.from(Tables.tasks).insert(rows).execute()
        }
    }
    // --8<-- [end:insert-list]

    // --8<-- [start:starter-list]
    /// Adds the starter tasks, once: their ids are made from the user's, so importing again finds
    /// them on the server and changes nothing, however they were edited since. The rows are JSON
    /// values; `onConflict` names the key and `ignoreDuplicates` keeps the server's rows.
    func importStarterList() async throws -> MutationOutcome<Void> {
        let userID = try signedInUserID()
        let createdAt = Date.now.formatted(.iso8601)
        let rows: [OfflineJSON] = StarterList.titles.enumerated().map { index, title in
            // Every column the list reads: a queued row shows with only what it wrote.
            [
                "id": .string(DeviceID.derived(from: "starter \(index) of \(userID)")),
                "user_id": .string(userID),
                "title": .string(title),
                "notes": .null,
                "done": false,
                "created_at": .string(createdAt),
            ]
        }
        return try await write(taskTitle: nil) {
            try await client.from(Tables.tasks)
                .upsert(.array(rows), onConflict: "id", ignoreDuplicates: true)
                .execute()
        }
    }
    // --8<-- [end:starter-list]

    // --8<-- [start:json-insert]
    /// Adds a task whose title the server refuses, written as raw JSON, to show what a refused
    /// write looks like: online it is refused at once; offline when it syncs.
    @discardableResult
    func queueInvalidTitle() async throws -> MutationOutcome<Void> {
        let row: OfflineJSON = [
            "id": .string(DeviceID.make()),
            "user_id": .string(try signedInUserID()),
            "title": .string(TaskRules.invalidTitle),
            "notes": .null,
            "done": false,
            "created_at": .string(Date.now.formatted(.iso8601)),
        ]
        return try await write(taskTitle: TaskRules.invalidTitle) {
            try await client.from(Tables.tasks).insert(row).execute()
        }
    }
    // --8<-- [end:json-insert]

    /// Deletes a task: its photo files (only online: Storage writes are never queued, so offline
    /// they stay in the bucket), then its photos waiting to upload, then its photo rows, then the
    /// task.
    ///
    /// - Throws: ``SampleFailure/storageRefused(status:message:)`` when Storage refuses to delete
    ///   the files, before anything else is deleted: the task and its waiting photos stay.
    func deleteTask(id: String) async throws {
        let photos = try await cachedPhotos(ofTask: id)
        if canReachNetwork {
            if let photos {
                try await deleteFiles(photos.map(\.objectPath))
            } else {
                try await deleteFiles(photoPathsOnServer { $0.eq("task_id", value: id) })
            }
        }
        // Then its photos still on this device: one whose upload has not returned yet gets no row.
        try pendingPhotos.removePhotos(ofTask: id)
        // The server's cascade would delete the photo rows, but the cache never sees a cascade.
        if photos == nil || photos?.isEmpty == false {
            try await write(taskTitle: nil) {
                try await client.from(Tables.taskPhotos).delete().eq("task_id", value: id).execute()
            }
        }
        // --8<-- [start:delete-task]
        try await write(taskTitle: nil) {
            try await client.from(Tables.tasks).delete().eq("id", value: id).execute()
        }
        // --8<-- [end:delete-task]
    }

    /// Deletes the done tasks made last week. Online, their photos' files, then their photos waiting
    /// to upload, then their photo rows go first, as Delete task does; offline, the tasks' grouped
    /// delete is refused before anything is deleted.
    func deleteLastWeeksDoneTasks(now: Date = .now) async throws {
        let lastWeek = TaskQuery.week(before: now)
        if canReachNetwork {
            let taskIDs = try await client.from(Tables.tasks)
                .select("id", readStrategy: .networkOnly)
                .is("done", value: true)
                .gte("created_at", value: lastWeek.start)
                .lt("created_at", value: lastWeek.end)
                .execute([RowKey].self)
                .value
                .map(\.id)
            guard !taskIDs.isEmpty else { return }
            try await deleteFiles(photoPathsOnServer { $0.in("task_id", values: taskIDs) })
            for taskID in taskIDs { try pendingPhotos.removePhotos(ofTask: taskID) }
            try await write(taskTitle: nil) {
                try await client.from(Tables.taskPhotos).delete().in("task_id", values: taskIDs).execute()
            }
        }
        try await deleteDoneTasks(createdWithin: lastWeek)
    }

    // --8<-- [start:same-column-write]
    /// Deletes the done tasks created within `window`: two conditions on `created_at`, which the
    /// client sends together in one group. The queue cannot record a group, so the delete is sent
    /// at once rather than queued, and offline it fails with `mutationNotQueueable`.
    func deleteDoneTasks(createdWithin window: DateInterval) async throws {
        try await client.from(Tables.tasks)
            .delete()
            .is("done", value: true)
            .gte("created_at", value: window.start)
            .lt("created_at", value: window.end)
            .execute()
    }
    // --8<-- [end:same-column-write]

    // MARK: - Sync

    // --8<-- [start:sync-now]
    /// Sends the queued writes now, in the order they were made, and says what happened; returns
    /// at once offline.
    @discardableResult
    func syncPendingMutations() async throws -> SyncReport {
        try await client.syncPendingMutations()
    }
    // --8<-- [end:sync-now]

    // --8<-- [start:rejected-actions]
    /// Forgets a refused write.
    func dismissRejectedMutation(id: Int64) async throws {
        try await client.dismissRejectedMutation(id: id)
    }

    /// Queues a refused write again, at the back of the queue; a second refusal comes back on the
    /// rejected list, never as an error.
    func retryRejectedMutation(id: Int64) async throws {
        try await client.retryRejectedMutation(id: id)
    }
    // --8<-- [end:rejected-actions]

    // --8<-- [start:resolve-conflict]
    /// The writes parked because their task changed on the server meanwhile, oldest first: the
    /// client's resolver holds a title both sides changed, until the user keeps one side.
    func conflictedMutations() -> ConflictedMutationSequence {
        client.conflictedMutations()
    }

    /// Keep mine queues the write again, preconditioned on the server's row as the conflict saw
    /// it, and asks for a sync; Keep theirs drops it, and the server's row stands on the device
    /// too. Either way it leaves the list.
    func resolveConflictedMutation(id: Int64, keepMine: Bool) async throws {
        try await client.resolveConflictedMutation(id: id, resolution: keepMine ? .keepLocal : .keepServer)
    }
    // --8<-- [end:resolve-conflict]

    // --8<-- [start:network-switch]
    /// Switches the app's own network access off (Simulate offline) or back on.
    func setNetworkEnabled(_ isEnabled: Bool) async throws {
        if isEnabled {
            try await client.enableNetwork()
        } else {
            try await client.disableNetwork()
        }
    }
    // --8<-- [end:network-switch]

    // --8<-- [start:pending-photo-clear]
    /// Clear local cache: deletes everything this device holds, the library's cached rows and
    /// files, queued and refused writes, and then the photos waiting to upload. Open lists then
    /// read their rows again from the server, once the network allows. Start over clears the same.
    func clearLocalData() async throws {
        try await client.clearLocalData()
        // The pending photos are the app's own files, outside the library's data directory, so
        // its clearLocalData() leaves them: the app deletes them itself.
        try pendingPhotos.removeAll()
    }
    // --8<-- [end:pending-photo-clear]

    // --8<-- [start:start-over]
    /// Starts again as a new anonymous user with no tasks, in this order:
    ///
    /// 1. Photo uploads are held, the one in progress waited for, and this user's queued writes
    ///    are discarded (a write already being sent is waited for), so none lands after the deletes.
    /// 2. Their photo files and rows are deleted from the server, with their token: an anonymous
    ///    user cannot sign back in to reach them.
    /// 3. The client's network is turned off, until the new user is signed in.
    /// 4. The app signs out, so the client's token closure returns `nil`.
    /// 5. Local data is cleared: the library's, then the photos waiting to upload.
    /// 6. The app signs in as a new user, and the network is turned back on: open live reads then
    ///    fetch again, with the new user's token.
    ///
    /// - Throws: ``SampleFailure/needsNetwork`` while offline, Simulate offline included, when
    ///   nothing is discarded, deleted or cleared.
    func startOver() async throws {
        guard canReachNetwork else { throw SampleFailure.needsNetwork }
        await pendingPhotos.holdUploads()
        defer { pendingPhotos.releaseUploads() }
        for mutation in try await client.pendingMutations().first() {
            try await client.discardPendingMutation(id: mutation.id)
        }
        if let userID = session.userID { try await deleteEverything(of: userID) }
        try await client.disableNetwork()
        await session.signOut()
        try await client.clearLocalData()
        try pendingPhotos.removeAll()
        do {
            try await session.signIn()
        } catch {
            try await client.enableNetwork()
            throw error
        }
        try await client.enableNetwork()
    }
    // --8<-- [end:start-over]

    // MARK: - Helpers

    /// Whether the library may reach the network: not switched off, and not known to be offline.
    var canReachNetwork: Bool {
        let network = client.syncStatus.value.network
        return network != .offline && network != .disabled
    }

    /// The signed-in user's id; the app offers no write that needs one before there is a session.
    func signedInUserID() throws -> String {
        guard let userID = session.userID else { throw SampleFailure.needsNetwork }
        return userID
    }

    /// Runs `write` and names a refusal of it in the app's terms.
    @discardableResult
    func write<Value>(
        taskTitle: String?,
        _ write: () async throws -> MutationOutcome<Value>
    ) async throws -> MutationOutcome<Value> {
        do {
            return try await write()
        } catch {
            throw SampleFailure.write(error, taskTitle: taskTitle)
        }
    }

    /// The object paths of the photo rows `filter` picks, asked of the server a page at a time
    /// until a page comes back empty.
    func photoPathsOnServer(
        where filter: (OfflineSelectRequestBuilder) -> OfflineSelectRequestBuilder
    ) async throws -> [String] {
        var paths: [String] = []
        while true {
            // --8<-- [start:network-only]
            let page = try await filter(client.from(Tables.taskPhotos).select("object_path", readStrategy: .networkOnly))
                .order("id", ascending: true)
                .range(from: paths.count, to: paths.count + Self.pathPageSize - 1)
                .execute([ObjectPathRow].self)
                .value
            // --8<-- [end:network-only]
            if page.isEmpty { return paths }
            paths += page.map(\.objectPath)
        }
    }

    // --8<-- [start:delete-my-data]
    /// Deletes `userID`'s files, photo rows and tasks on the server, before that user is left.
    /// The deletes are sent now or not at all: queued, they would outlive the user they are for.
    private func deleteEverything(of userID: String) async throws {
        try await deleteFiles(photoPathsOnServer { $0.eq("user_id", value: userID) })
        // The files of its photos waiting to upload too: an upload whose answer was lost may have stored one.
        try await deleteFiles(pendingPhotos.photos.filter { $0.userID == userID }.map(\.objectPath))
        try await write(taskTitle: nil) {
            try await client.from(Tables.taskPhotos).delete(mutationStrategy: .onlineOnly).eq("user_id", value: userID).execute()
        }
        try await write(taskTitle: nil) {
            try await client.from(Tables.tasks).delete(mutationStrategy: .onlineOnly).eq("user_id", value: userID).execute()
        }
    }
    // --8<-- [end:delete-my-data]
}

/// What New task reports once its write returns.
nonisolated enum NewTaskResult: Equatable, Sendable {

    /// The server stored it, at `storedAt`: the `updated_at` the server set and gave back.
    case saved(storedAt: Date)

    /// It waits in the mutation queue under `pendingMutationID`, and is sent when the network allows.
    case savedOnDevice(pendingMutationID: Int64)
}

/// Where a list's rows came from, for the caption under the Tasks title.
nonisolated struct ListProvenance: Equatable, Sendable {

    /// Whether the rows are the device's copy rather than the server's answer to this read.
    let isFromCache: Bool

    /// When the rows were fetched from the server; `nil` when they never were.
    let fetchedAt: Date?

    /// Whether a queued write changed the rows shown.
    let hasPendingMutations: Bool
}

nonisolated extension ListProvenance {

    // --8<-- [start:provenance]
    /// The provenance of a one-shot read's `response`: whether it is the device's copy, when it
    /// was fetched, and whether a queued write changed it.
    init<Value>(_ response: OfflineResponse<Value>) {
        self.init(
            isFromCache: response.isFromCache,
            fetchedAt: response.fetchedAt,
            hasPendingMutations: response.hasPendingMutations
        )
    }
    // --8<-- [end:provenance]

    /// The provenance of a live read's `snapshot`.
    init<Value>(_ snapshot: QuerySnapshot<Value>) {
        self.init(
            isFromCache: snapshot.isFromCache,
            fetchedAt: snapshot.fetchedAt,
            hasPendingMutations: snapshot.hasPendingMutations
        )
    }
}

/// Where a photo's file stands on this device.
nonisolated enum PhotoFileState: Equatable, Sendable {

    /// Kept on the device: it shows offline too.
    case onDevice(CachedFile)

    /// Not on the device, and the server cannot be asked now; it loads when online.
    case notOnDevice

    /// Neither the device nor the bucket has it.
    case missing

    /// It could not be loaded.
    case unavailable
}

/// How the app reads the bytes of a photo the device keeps.
nonisolated enum PhotoBytesSource: Sendable {

    /// From the cached file on disk, at ``CachedFile/url``: the app's way.
    case cachedFile

    /// Through the bucket's download, which answers from the device's copy when the network fails.
    /// For demo mode only: the in-memory client keeps its copies in memory, at paths that are not
    /// on disk.
    case bucketDownload
}

/// A photo re-encoded on the device as one JPEG under the bucket's 5 MB limit.
nonisolated struct EncodedPhoto: Sendable {

    /// The JPEG's bytes.
    let jpeg: Data

    /// Its width in pixels.
    let width: Int

    /// Its height in pixels.
    let height: Int
}

extension PendingMutation {

    /// The `id` values in this write's row keys: the rows it adds, changes or deletes.
    nonisolated var rowIDs: [String] {
        rowKeys.compactMap { key in
            guard case .string(let id) = key["id"] else { return nil }
            return id
        }
    }
}
