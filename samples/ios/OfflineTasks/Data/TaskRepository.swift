// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline

/// Everything the screens read and write, for the signed-in user: every call the app makes to the
/// offline client is in this file.
///
/// Reads are live: each is a sequence of snapshots served from the device first and refreshed from
/// the server when it can be reached. Writes change the device's copy at once and wait in the
/// mutation queue until the server has them, so they work offline. Photo uploads and deletes are
/// the exception: Storage writes are never queued, so they need the network.
///
/// What throws: a write sent at once that the server refuses throws
/// ``SampleFailure/writeRefused(taskTitle:serverError:)``; a write that waited in the queue never
/// throws, and its refusal goes to the rejected list. A call that needs the server throws
/// ``SampleFailure/needsNetwork`` offline. Everything else arrives as the library threw it.
final class TaskRepository {

    /// The app's one offline client.
    let client: SupabaseOfflineClient

    /// Who is signed in.
    let session: any UserSession

    /// How a cached photo's bytes are read.
    private let photoBytes: PhotoBytesSource

    /// How many objects one Storage delete names at most.
    private static let storageDeleteBatch = 100

    /// How many photo rows one read of their paths asks for: a project answers at most its row
    /// limit (1,000 unless changed) to one request.
    private static let pathPageSize = 1_000

    /// A repository over `client` for `session`'s user, reading cached photos as `photoBytes` says.
    init(client: SupabaseOfflineClient, session: any UserSession, photoBytes: PhotoBytesSource = .cachedFile) {
        self.client = client
        self.session = session
        self.photoBytes = photoBytes
    }

    /// The bucket every photo lives in.
    private var photoBucket: OfflineBucketApi {
        client.storage.from(PhotoPath.bucket)
    }

    // MARK: - Live reads

    /// The user's tasks, newest first. The detail screen reads this query too and picks its task
    /// out: each query is cached on its own, and offline a query never fetched has nothing to show,
    /// while the list is fetched as the app starts.
    func taskSnapshots() -> QuerySnapshotSequence<[TaskRow]> {
        client.from(Tables.tasks)
            .selectAsSequence(as: [TaskRow].self)
            .order("created_at", ascending: false)
    }

    /// Every photo the user has, newest first; each task's screen picks out its own, as with
    /// ``taskSnapshots()``.
    func photoSnapshots() -> QuerySnapshotSequence<[TaskPhotoRow]> {
        client.from(Tables.taskPhotos)
            .selectAsSequence(as: [TaskPhotoRow].self)
            .order("created_at", ascending: false)
    }

    /// The writes waiting in the mutation queue for `table`, as the queue changes.
    func pendingMutations(table: String) -> PendingMutationSequence {
        client.pendingMutations(table: table)
    }

    /// How many writes wait for `table`, or for every table, as the queue changes.
    func pendingMutationCounts(table: String? = nil) -> PendingMutationCountSequence {
        client.pendingMutationCountSequence(table: table)
    }

    /// The writes the server refused on replay, oldest first, until each is dismissed or retried.
    func rejectedMutations() -> RejectedMutationSequence {
        client.rejectedMutations()
    }

    /// The network, the queue and the last sync, as one value: ``SyncStatusSequence/value`` now, or
    /// iterate it for each change.
    var syncStatus: SyncStatusSequence {
        client.syncStatus
    }

    // MARK: - Task writes

    /// Adds a task with a new id, the signed-in user and the device's clock as its creation time.
    @discardableResult
    func addTask(title: String, notes: String?) async throws -> MutationOutcome<Void> {
        let row = NewTaskRow(
            id: DeviceID.make(),
            userID: try signedInUserID(),
            title: title,
            notes: notes,
            done: false,
            createdAt: .now
        )
        return try await write(taskTitle: title) {
            try await client.from(Tables.tasks).insert(row).execute()
        }
    }

    /// Sets a task's title and notes; `nil` notes clears them on the server too.
    @discardableResult
    func updateTask(id: String, title: String, notes: String?) async throws -> MutationOutcome<Void> {
        // OfflineJSON, so a cleared note is sent as an explicit null rather than left out.
        let values: [String: OfflineJSON] = ["title": .string(title), "notes": notes.map(OfflineJSON.string) ?? .null]
        return try await write(taskTitle: title) {
            try await client.from(Tables.tasks).update(values).eq("id", value: id).execute()
        }
    }

    /// Marks a task done, or open again.
    @discardableResult
    func setTaskDone(id: String, isDone: Bool) async throws -> MutationOutcome<Void> {
        try await write(taskTitle: nil) {
            try await client.from(Tables.tasks).update(["done": .bool(isDone)]).eq("id", value: id).execute()
        }
    }

    /// Deletes a task: its photo files first (only online: Storage writes are never queued, so
    /// offline they stay in the bucket), then its photo rows, then the task.
    ///
    /// - Throws: ``SampleFailure/storageRefused(status:message:)`` when Storage refuses to delete
    ///   the files, before anything else is deleted.
    func deleteTask(id: String) async throws {
        let photos = try await cachedPhotos(ofTask: id)
        if canReachNetwork {
            if let photos {
                try await deleteFiles(photos.map(\.objectPath))
            } else {
                try await deleteFiles(photoPathsOnServer(column: "task_id", value: id))
            }
        }
        // The server's cascade would delete the photo rows, but the cache never sees a cascade.
        if photos == nil || photos?.isEmpty == false {
            try await write(taskTitle: nil) {
                try await client.from(Tables.taskPhotos).delete().eq("task_id", value: id).execute()
            }
        }
        try await write(taskTitle: nil) {
            try await client.from(Tables.tasks).delete().eq("id", value: id).execute()
        }
    }

    // MARK: - Photos

    /// Uploads `photo` for the task, then writes its row. The file goes first, so a row never names
    /// a file that is not there; a row the server refuses at once takes its file with it.
    ///
    /// - Throws: ``SampleFailure/needsNetwork`` at once while offline: uploads are never queued.
    @discardableResult
    func attachPhoto(taskID: String, photo: EncodedPhoto) async throws -> TaskPhoto {
        guard canReachNetwork else { throw SampleFailure.needsNetwork }
        let userID = try signedInUserID()
        let photoID = DeviceID.make()
        let objectPath = PhotoPath.objectPath(userID: userID, taskID: taskID, photoID: photoID)
        do {
            // No upsert: the bucket has no update policy, and a path is never reused.
            try await photoBucket.upload(
                objectPath,
                data: photo.jpeg,
                options: OfflineBucketApi.UploadOptions(contentType: "image/jpeg", upsert: false)
            )
        } catch {
            throw SampleFailure.storage(error)
        }
        let row = TaskPhotoRow(
            id: photoID,
            userID: userID,
            taskID: taskID,
            objectPath: objectPath,
            width: photo.width,
            height: photo.height,
            createdAt: .now
        )
        do {
            try await write(taskTitle: nil) {
                try await client.from(Tables.taskPhotos).insert(row).execute()
            }
        } catch let refusal as SampleFailure {
            // Refused at once (its task deleted on the server meanwhile, say): no row will ever name
            // the file, so it goes too, as far as Storage allows. A row that is only queued keeps
            // its file.
            if case .writeRefused = refusal { try? await photoBucket.remove(paths: [objectPath]) }
            throw refusal
        }
        return row.photo(pendingIDs: [])
    }

    /// Deletes a photo's file, then its row. The file needs the network.
    func deletePhoto(_ photo: TaskPhoto) async throws {
        guard canReachNetwork else { throw SampleFailure.needsNetwork }
        try await deleteFiles([photo.objectPath])
        try await write(taskTitle: nil) {
            try await client.from(Tables.taskPhotos).delete().eq("id", value: photo.id).execute()
        }
    }

    /// Where a photo's file stands on this device: kept, or fetched now and kept when it is not
    /// and the server can be asked.
    ///
    /// A private file is asked for only once a user is signed in: without the user's token,
    /// Storage answers "not found", which would drop a copy kept earlier.
    func photoFile(for photo: TaskPhoto) async -> PhotoFileState {
        do {
            if let file = try await photoBucket.cachedFile(path: photo.objectPath) { return .onDevice(file) }
            guard session.userID != nil, canReachNetwork else { return .notOnDevice }
            return .onDevice(try await photoBucket.ensureCachedFile(path: photo.objectPath))
        } catch {
            switch SampleFailure.storage(error) as? SampleFailure {
            case .needsNetwork?: return .notOnDevice
            case .photoMissing?: return .missing
            default: return .unavailable
            }
        }
    }

    /// The bytes of `file`, the device's copy of `photo`'s file.
    func photoData(of file: CachedFile, for photo: TaskPhoto) async throws -> Data {
        switch photoBytes {
        case .cachedFile:
            return try await Self.contents(of: file.url)
        case .bucketDownload:
            return try await photoBucket.download(path: photo.objectPath)
        }
    }

    /// The contents of the file at `url`, read off the main actor.
    @concurrent
    nonisolated private static func contents(of url: URL) async throws -> Data {
        try Data(contentsOf: url)
    }

    // MARK: - Sync

    /// Asks the server for the task list again, as pull to refresh does: the same query as
    /// ``taskSnapshots()``, so its answer replaces that query's cached rows and every reader of it
    /// sees them.
    func refreshTasks() async throws {
        do {
            try await client.from(Tables.tasks)
                .select(readStrategy: .networkOnly)
                .order("created_at", ascending: false)
                .execute()
        } catch {
            throw SampleFailure.read(error)
        }
    }

    /// Sends the queued writes now, in the order they were made; returns at once offline.
    @discardableResult
    func syncPendingMutations() async throws -> SyncReport {
        try await client.syncPendingMutations()
    }

    /// Forgets a refused write.
    func dismissRejectedMutation(id: Int64) async throws {
        try await client.dismissRejectedMutation(id: id)
    }

    /// Queues a refused write again, at the back of the queue; a second refusal comes back on the
    /// rejected list, never as an error.
    func retryRejectedMutation(id: Int64) async throws {
        try await client.retryRejectedMutation(id: id)
    }

    /// Switches the app's own network access off (Simulate offline) or back on.
    func setNetworkEnabled(_ isEnabled: Bool) async throws {
        if isEnabled {
            try await client.enableNetwork()
        } else {
            try await client.disableNetwork()
        }
    }

    /// Deletes everything this device holds: cached rows and files, queued and refused writes.
    /// Open lists then read their rows again from the server, once the network allows.
    func clearLocalData() async throws {
        try await client.clearLocalData()
    }

    /// Starts again as a new anonymous user with no tasks, in this order:
    ///
    /// 1. This user's queued writes are discarded (a write already being sent is waited for), so
    ///    none lands after the deletes.
    /// 2. Their photo files and rows are deleted from the server, with their token: an anonymous
    ///    user cannot sign back in to reach them.
    /// 3. The app signs out, so the client's token closure returns `nil`.
    /// 4. Local data is cleared. Only now, with no user's token live: a live read still open
    ///    refetches after a clear, and with this user's token it would cache their rows again for
    ///    the next user to see.
    /// 5. The app signs in as a new user.
    ///
    /// - Throws: ``SampleFailure/needsNetwork`` while offline, when nothing is discarded, deleted or
    ///   cleared. A failed delete is thrown with the same user still signed in, so it can be tried
    ///   again; their unsent changes are gone by then.
    func startOver() async throws {
        guard canReachNetwork else { throw SampleFailure.needsNetwork }
        for mutation in try await client.pendingMutations().first() {
            try await client.discardPendingMutation(id: mutation.id)
        }
        if let userID = session.userID { try await deleteEverything(of: userID) }
        await session.signOut()
        try await client.clearLocalData()
        try await session.signIn()
    }

    // MARK: - Helpers

    /// Whether the library may reach the network: not switched off, and not known to be offline.
    var canReachNetwork: Bool {
        let network = client.syncStatus.value.network
        return network != .offline && network != .disabled
    }

    /// The signed-in user's id; the app offers no write that needs one before there is a session.
    private func signedInUserID() throws -> String {
        guard let userID = session.userID else { throw SampleFailure.needsNetwork }
        return userID
    }

    /// Runs `write` and names its failure in the app's terms.
    @discardableResult
    private func write(
        taskTitle: String?,
        _ write: () async throws -> MutationOutcome<Void>
    ) async throws -> MutationOutcome<Void> {
        do {
            return try await write()
        } catch {
            throw SampleFailure.write(error, taskTitle: taskTitle)
        }
    }

    /// The photo rows of task `taskID` the device holds, from the query ``photoSnapshots()``
    /// reads, or `nil` when it holds none for that query.
    private func cachedPhotos(ofTask taskID: String) async throws -> [TaskPhotoRow]? {
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

    /// The object paths of the photo rows whose `column` equals `value`, asked of the server a page
    /// at a time until a page comes back empty.
    private func photoPathsOnServer(column: String, value: String) async throws -> [String] {
        var paths: [String] = []
        while true {
            let page: [ObjectPathRow]
            do {
                page = try await client.from(Tables.taskPhotos)
                    .select("object_path", readStrategy: .networkOnly)
                    .eq(column, value: value)
                    .order("id", ascending: true)
                    .range(from: paths.count, to: paths.count + Self.pathPageSize - 1)
                    .execute([ObjectPathRow].self)
                    .value
            } catch {
                throw SampleFailure.read(error)
            }
            if page.isEmpty { return paths }
            paths += page.map(\.objectPath)
        }
    }

    /// Deletes the files at `paths`, in batches; a missing file is no error.
    private func deleteFiles(_ paths: [String]) async throws {
        for start in stride(from: 0, to: paths.count, by: Self.storageDeleteBatch) {
            let batch = Array(paths[start..<min(start + Self.storageDeleteBatch, paths.count)])
            do {
                try await photoBucket.remove(paths: batch)
            } catch {
                throw SampleFailure.storage(error)
            }
        }
    }

    /// Deletes `userID`'s files, photo rows and tasks on the server, before that user is left.
    private func deleteEverything(of userID: String) async throws {
        try await deleteFiles(photoPathsOnServer(column: "user_id", value: userID))
        // Sent now or not at all: queued, they would outlive the user they are for.
        try await write(taskTitle: nil) {
            try await client.from(Tables.taskPhotos).delete(mutationStrategy: .onlineOnly).eq("user_id", value: userID).execute()
        }
        try await write(taskTitle: nil) {
            try await client.from(Tables.tasks).delete(mutationStrategy: .onlineOnly).eq("user_id", value: userID).execute()
        }
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
