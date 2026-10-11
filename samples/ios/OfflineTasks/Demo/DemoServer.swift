// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline
import SupabaseOfflineTesting

/// A stand-in for the samples' Supabase project, in memory: it answers the in-memory client's
/// PostgREST, database-function and Storage requests the way the project's schema would, and
/// reports each row change as Realtime would, for demo mode, previews and tests. It is not part of
/// what an app built on the library needs.
///
/// It keeps the schema's rules: a request without a user's token reaches nothing, each user reaches
/// only their own rows and files, a blank or over-long title and over-long notes are refused (HTTP
/// 400, 23514), a photo needs its owner's task (23503), an id already taken is refused (23505), and
/// the bucket takes only a JPEG under 5 MB at the owner's own path.
actor DemoServer: ServerChangeSource {

    /// One request the client sent, as a test reads it.
    struct RecordedRequest: Sendable, Hashable {

        /// The HTTP method.
        let method: String

        /// The path below the project URL, such as `/rest/v1/tasks`.
        let path: String

        /// The user whose token the request carried; `nil` for none.
        let userID: String?

        /// The body, as text.
        let body: String
    }

    /// One file in the bucket.
    struct StoredFile: Sendable {
        var data: Data
        var contentType: String
        var metadata: [String: OfflineJSON]
        var createdAt: Date
        var updatedAt: Date
    }

    /// The rows of each table, by id, in the order they were inserted.
    private var tables: [String: [(id: String, row: [String: OfflineJSON])]] = [Tables.tasks: [], Tables.taskPhotos: []]

    /// The bucket's files, by object path.
    private var files: [String: StoredFile] = [:]

    /// The upload tokens handed out, by the object path each one is for.
    private var uploadTokens: [String: String] = [:]

    /// An answer a test puts in front of the server's own: when it returns a response, that is the
    /// answer.
    private var interception: (@Sendable (FakeServer.Request, String?) -> FakeServer.Response?)?

    /// The Realtime-like feeds open now, with the tables each follows.
    private var feeds: [UUID: (tables: Set<String>, continuation: AsyncThrowingStream<ServerChange, any Error>.Continuation)] = [:]

    /// Every request so far, in order.
    private(set) var requests: [RecordedRequest] = []

    /// A server holding `tasks`, for previews.
    init(tasks: [TaskRow] = []) {
        for task in tasks {
            let row: [String: OfflineJSON] = [
                "id": .string(task.id), "user_id": .string(task.userID), "title": .string(task.title),
                "notes": task.notes.map(OfflineJSON.string) ?? .null, "done": .bool(task.done),
                "created_at": .string(Self.timestamp(task.createdAt)),
                "updated_at": .string(Self.timestamp(task.createdAt)),
            ]
            tables[Tables.tasks, default: []].append((task.id, row))
        }
    }

    /// The client's view of this server.
    nonisolated var fakeServer: FakeServer {
        FakeServer { request in await self.response(to: request) }
    }

    /// The access token that signs requests in as `userID`.
    nonisolated static func accessToken(for userID: String) -> String {
        "demo-access-token.\(userID)"
    }

    /// Puts `interception` in front of the server's answers; `nil` takes it away.
    func intercept(_ interception: (@Sendable (FakeServer.Request, String?) -> FakeServer.Response?)?) {
        self.interception = interception
    }

    /// The rows of `table` as the server holds them, whatever their owner.
    func rows(of table: String) -> [[String: OfflineJSON]] {
        tables[table, default: []].map(\.row)
    }

    /// The object paths in the bucket.
    func objectPaths() -> [String] {
        files.keys.sorted()
    }

    /// How many Realtime-like feeds are open: a test waits for one before it changes a row.
    func openFeedCount() -> Int {
        feeds.count
    }

    /// The bytes of the file at `objectPath`.
    func fileData(at objectPath: String) -> Data? {
        files[objectPath]?.data
    }

    /// Changes task `id` as another device would: `values` set on the server, and the change
    /// reported to the open feeds, never through this client.
    func changeElsewhere(task id: String, _ values: [String: OfflineJSON]) {
        guard let index = tables[Tables.tasks]?.firstIndex(where: { $0.id == id }) else { return }
        tables[Tables.tasks]?[index].row.merge(values) { _, given in given }
        tables[Tables.tasks]?[index].row["updated_at"] = .string(Self.timestamp(.now))
        if let row = tables[Tables.tasks]?[index].row { report(.saved(table: Tables.tasks, record: row)) }
    }

    // MARK: - Realtime

    nonisolated func changes(of tables: [String]) -> AsyncThrowingStream<ServerChange, any Error> {
        let (stream, continuation) = AsyncThrowingStream<ServerChange, any Error>.makeStream()
        let id = UUID()
        Task { await self.open(feed: id, tables: Set(tables), continuation: continuation) }
        continuation.onTermination = { _ in Task { await self.close(feed: id) } }
        return stream
    }

    /// Starts reporting changes to `tables` through `continuation`.
    private func open(feed id: UUID, tables: Set<String>, continuation: AsyncThrowingStream<ServerChange, any Error>.Continuation) {
        feeds[id] = (tables, continuation)
    }

    /// Stops reporting to feed `id`.
    private func close(feed id: UUID) {
        feeds[id] = nil
    }

    /// Reports `change` to every feed that follows its table, as Realtime would.
    private func report(_ change: ServerChange) {
        let table: String
        switch change {
        case .saved(let name, _), .deleted(let name, _): table = name
        }
        for feed in feeds.values where feed.tables.contains(table) { feed.continuation.yield(change) }
    }

    // MARK: - Answering

    /// The answer to `request`.
    private func response(to request: FakeServer.Request) -> FakeServer.Response {
        let userID = Self.userID(in: request.headers)
        let path = request.url.path(percentEncoded: false)
        requests.append(
            RecordedRequest(method: request.method, path: path, userID: userID, body: String(decoding: request.body, as: UTF8.self))
        )
        if let answer = interception?(request, userID) { return answer }
        if path.hasPrefix("/rest/v1/rpc/") {
            return functionResponse(to: request, function: String(path.dropFirst("/rest/v1/rpc/".count)), userID: userID)
        }
        if path.hasPrefix("/rest/v1/") {
            return postgrestResponse(to: request, table: String(path.dropFirst("/rest/v1/".count)), userID: userID)
        }
        if path.hasPrefix("/storage/v1/") {
            return storageResponse(to: request, path: String(path.dropFirst("/storage/v1/".count)), userID: userID)
        }
        return .json(#"{"message":"No such route"}"#, status: 404)
    }

    /// The user a request's bearer token signs in, if any.
    private static func userID(in headers: [String: String]) -> String? {
        let authorization = header("authorization", in: headers) ?? ""
        let prefix = "Bearer \(accessToken(for: ""))"
        guard authorization.hasPrefix(prefix) else { return nil }
        return String(authorization.dropFirst(prefix.count))
    }

    /// The value of the header `name`, matched without regard to case.
    private static func header(_ name: String, in headers: [String: String]) -> String? {
        headers.first { $0.key.lowercased() == name }?.value
    }

    // MARK: - Database functions

    /// The answer of the schema's database function `function`.
    private func functionResponse(to request: FakeServer.Request, function: String, userID: String?) -> FakeServer.Response {
        guard let userID else {
            return Self.postgrestError(status: 401, code: "42501", message: "permission denied for function \(function)")
        }
        switch function {
        case "complete_all_tasks":
            for index in tables[Tables.tasks, default: []].indices where tables[Tables.tasks]?[index].row["user_id"] == .string(userID) {
                guard tables[Tables.tasks]?[index].row["done"] != .bool(true) else { continue }
                tables[Tables.tasks]?[index].row["done"] = .bool(true)
                tables[Tables.tasks]?[index].row["updated_at"] = .string(Self.timestamp(.now))
                if let row = tables[Tables.tasks]?[index].row { report(.saved(table: Tables.tasks, record: row)) }
            }
            return .status(204)
        case "tasks_done_count":
            let done = ownRows(of: Tables.tasks, userID: userID).filter { $0["done"] == .bool(true) }.count
            return .json(String(done))
        default:
            return Self.postgrestError(status: 404, code: "PGRST202", message: "Could not find the function public.\(function)")
        }
    }

    // MARK: - PostgREST

    /// PostgREST's answer for `table`.
    private func postgrestResponse(to request: FakeServer.Request, table: String, userID: String?) -> FakeServer.Response {
        guard tables[table] != nil else {
            return Self.postgrestError(status: 404, code: "PGRST205", message: "Could not find the table 'public.\(table)'")
        }
        guard let userID else {
            return Self.postgrestError(status: 401, code: "42501", message: "permission denied for table \(table)")
        }
        let query = Query(url: request.url, headers: request.headers)
        switch request.method {
        case "GET", "HEAD":
            let rows = query.ordered(query.matching(ownRows(of: table, userID: userID)))
            let page = query.page(rows)
            return Self.rowsResponse(page.map(query.projected), method: request.method, total: rows.count, query: query)
        case "POST":
            return insert(Self.objects(in: request.body), into: table, userID: userID, query: query)
        case "PATCH":
            guard case .object(let values)? = Self.json(request.body) else {
                return Self.postgrestError(status: 400, code: "PGRST102", message: "Empty or invalid json")
            }
            return update(table, with: values, userID: userID, query: query)
        case "DELETE":
            return delete(from: table, userID: userID, query: query)
        default:
            return Self.postgrestError(status: 405, code: "PGRST117", message: "Unsupported HTTP method")
        }
    }

    /// The rows of `table` that `userID` owns, as row-level security admits them.
    private func ownRows(of table: String, userID: String) -> [[String: OfflineJSON]] {
        tables[table, default: []].map(\.row).filter { $0["user_id"] == .string(userID) }
    }

    /// Inserts `objects`, all or none, as `userID`; an upsert merges into a row with the same key,
    /// or leaves it as it is when it ignores duplicates.
    private func insert(
        _ objects: [[String: OfflineJSON]]?,
        into table: String,
        userID: String,
        query: Query
    ) -> FakeServer.Response {
        guard let objects, !objects.isEmpty else {
            return Self.postgrestError(status: 400, code: "PGRST102", message: "Empty or invalid json")
        }
        var rows = tables[table, default: []]
        var written: [[String: OfflineJSON]] = []
        for object in objects {
            var row = Self.defaults(for: table).merging(object) { _, given in given }
            row["updated_at"] = .string(Self.timestamp(.now))
            if case .string? = row["created_at"] {} else { row["created_at"] = .string(Self.timestamp(.now)) }
            guard case .string(let id)? = row["id"] else {
                return Self.postgrestError(
                    status: 400,
                    code: "23502",
                    message: "null value in column \"id\" of relation \"\(table)\" violates not-null constraint"
                )
            }
            guard row["user_id"] == .string(userID) else {
                return Self.postgrestError(
                    status: 403,
                    code: "42501",
                    message: "new row violates row-level security policy for table \"\(table)\""
                )
            }
            if let refusal = checkConstraints(of: row, in: table) { return refusal }
            if let index = rows.firstIndex(where: { $0.id == id }) {
                if query.ignoresDuplicates { continue }
                guard query.mergesDuplicates else {
                    return Self.postgrestError(
                        status: 409,
                        code: "23505",
                        message: "duplicate key value violates unique constraint \"\(table)_pkey\""
                    )
                }
                rows[index].row.merge(object) { _, given in given }
                written.append(rows[index].row)
            } else {
                rows.append((id, row))
                written.append(row)
            }
        }
        tables[table] = rows
        written.forEach { report(.saved(table: table, record: $0)) }
        return Self.writeResponse(written, query: query, created: true)
    }

    /// Sets `values` on the rows `query` matches, as `userID`.
    private func update(_ table: String, with values: [String: OfflineJSON], userID: String, query: Query) -> FakeServer.Response {
        // All or none: the table changes only once every row has passed.
        var rows = tables[table, default: []]
        var updated: [[String: OfflineJSON]] = []
        for index in rows.indices {
            var row = rows[index].row
            guard row["user_id"] == .string(userID), query.matches(row) else { continue }
            row.merge(values) { _, given in given }
            row["updated_at"] = .string(Self.timestamp(.now))
            if let refusal = checkConstraints(of: row, in: table) { return refusal }
            rows[index].row = row
            updated.append(row)
        }
        tables[table] = rows
        updated.forEach { report(.saved(table: table, record: $0)) }
        return Self.writeResponse(updated, query: query, created: false)
    }

    /// Deletes the rows `query` matches, as `userID`; a task takes its photo rows with it, as the
    /// schema's cascade does (but never their files).
    private func delete(from table: String, userID: String, query: Query) -> FakeServer.Response {
        let doomed = tables[table, default: []].filter { $0.row["user_id"] == .string(userID) && query.matches($0.row) }
        let doomedIDs = Set(doomed.map(\.id))
        tables[table]?.removeAll { doomedIDs.contains($0.id) }
        var cascaded: [String] = []
        if table == Tables.tasks {
            tables[Tables.taskPhotos]?.removeAll { photo in
                guard case .string(let taskID)? = photo.row["task_id"], doomedIDs.contains(taskID) else { return false }
                cascaded.append(photo.id)
                return true
            }
        }
        doomed.forEach { report(.deleted(table: table, oldRecord: ["id": .string($0.id)])) }
        cascaded.forEach { report(.deleted(table: Tables.taskPhotos, oldRecord: ["id": .string($0)])) }
        return Self.writeResponse(doomed.map(\.row), query: query, created: false)
    }

    /// The schema's check and foreign-key refusal of `row`, if it breaks a rule.
    private func checkConstraints(of row: [String: OfflineJSON], in table: String) -> FakeServer.Response? {
        if table == Tables.tasks {
            if case .string(let title)? = row["title"], TaskRules.isTitleRefused(title) {
                return Self.checkViolation(table: table, constraint: "tasks_title_length")
            }
            if case .string(let notes)? = row["notes"], TaskRules.characterCount(notes) > TaskRules.notesMaxLength {
                return Self.checkViolation(table: table, constraint: "tasks_notes_length")
            }
            return nil
        }
        for column in ["width", "height"] {
            if case .int(let size)? = row[column], size <= 0 {
                return Self.checkViolation(table: table, constraint: "task_photos_\(column)_positive")
            }
        }
        guard case .string(let taskID)? = row["task_id"], case .string(let ownerID)? = row["user_id"],
            tables[Tables.tasks, default: []].contains(where: { $0.id == taskID && $0.row["user_id"] == .string(ownerID) })
        else {
            return Self.postgrestError(
                status: 409,
                code: "23503",
                message: "insert or update on table \"task_photos\" violates foreign key constraint \"task_photos_task_fkey\""
            )
        }
        guard case .string(let id)? = row["id"],
            row["object_path"] == .string(PhotoPath.objectPath(userID: ownerID, taskID: taskID, photoID: id))
        else {
            return Self.checkViolation(table: table, constraint: "task_photos_object_path_layout")
        }
        return nil
    }

    /// The columns a new row of `table` starts with.
    private static func defaults(for table: String) -> [String: OfflineJSON] {
        table == Tables.tasks ? ["notes": .null, "done": .bool(false)] : ["width": .null, "height": .null]
    }

    // MARK: - Storage

    /// Storage's answer for `path`, below `/storage/v1/`.
    private func storageResponse(to request: FakeServer.Request, path: String, userID: String?) -> FakeServer.Response {
        guard let userID else {
            return Self.storageError(status: 400, code: "Unauthorized", message: "Invalid Compact JWS")
        }
        let route = StorageRoute(method: request.method, path: path)
        switch route {
        case .upload(let objectPath):
            return upload(request, to: objectPath, userID: userID, replacing: false)
        case .update(let objectPath):
            return upload(request, to: objectPath, userID: userID, replacing: true)
        case .download(let objectPath):
            guard Self.owner(of: objectPath) == userID, let file = files[objectPath] else { return Self.notFound }
            return .status(200, headers: ["Content-Type": file.contentType], body: file.data)
        case .exists(let objectPath):
            return .status(Self.owner(of: objectPath) == userID && files[objectPath] != nil ? 200 : 404)
        case .info(let objectPath):
            guard Self.owner(of: objectPath) == userID, let file = files[objectPath] else { return Self.notFound }
            return .json(Self.text(Self.details(of: file, at: objectPath)))
        case .list:
            return list(request, userID: userID)
        case .move, .copy:
            return moveOrCopy(request, route: route, userID: userID)
        case .remove:
            return remove(request, userID: userID)
        case .sign(let objectPath):
            guard Self.owner(of: objectPath) == userID, files[objectPath] != nil else { return Self.notFound }
            return .json(Self.text(["signedURL": .string(Self.signedPath(objectPath))]))
        case .signSeveral:
            guard case .object(let body)? = Self.json(request.body), case .array(let paths)? = body["paths"] else {
                return Self.storageError(status: 400, code: "invalid_request", message: "paths is required")
            }
            let signed: [OfflineJSON] = paths.compactMap { path in
                guard case .string(let objectPath) = path else { return nil }
                return ["error": .null, "path": .string(objectPath), "signedURL": .string(Self.signedPath(objectPath))]
            }
            return .json(Self.text(.array(signed)))
        case .signUpload(let objectPath):
            guard Self.owner(of: objectPath) == userID else { return Self.unauthorized }
            let token = DeviceID.make()
            uploadTokens[objectPath] = token
            return .json(Self.text(["url": .string("/object/upload/sign/\(PhotoPath.bucket)/\(objectPath)?token=\(token)")]))
        case .uploadToSigned(let objectPath):
            let token = URLComponents(url: request.url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "token" }?.value
            guard let token, uploadTokens.removeValue(forKey: objectPath) == token else { return Self.unauthorized }
            return upload(request, to: objectPath, userID: userID, replacing: false)
        case .unknown:
            return Self.storageError(status: 400, code: "invalid_request", message: "No such route")
        }
    }

    /// Stores an upload at `objectPath` as `userID`, as the bucket's settings and policies allow;
    /// `replacing` writes over a file that is there, as an update does.
    private func upload(_ request: FakeServer.Request, to objectPath: String, userID: String, replacing: Bool) -> FakeServer.Response {
        guard Self.owner(of: objectPath) == userID else { return Self.unauthorized }
        let contentType = Self.header("content-type", in: request.headers) ?? ""
        guard contentType.hasPrefix("image/jpeg") else {
            return Self.storageError(status: 400, code: "invalid_mime_type", message: "mime type \(contentType) is not supported", statusText: "415")
        }
        guard request.body.count <= PhotoPath.maxBytes else {
            return Self.storageError(status: 413, code: "Payload too large", message: "The object exceeded the maximum allowed size")
        }
        let upsert = Self.header("x-upsert", in: request.headers) == "true"
        let existing = files[objectPath]
        if replacing, existing == nil { return Self.notFound }
        if !replacing, existing != nil, !upsert {
            return Self.storageError(status: 409, code: "Duplicate", message: "The resource already exists")
        }
        files[objectPath] = StoredFile(
            data: request.body,
            contentType: "image/jpeg",
            metadata: Self.userMetadata(in: request.headers) ?? existing?.metadata ?? [:],
            createdAt: existing?.createdAt ?? .now,
            updatedAt: .now
        )
        return .json(Self.text(["Key": .string("\(PhotoPath.bucket)/\(objectPath)"), "Id": .string(DeviceID.make())]))
    }

    /// Storage's listing of a folder: the folders and files right below it, by name or by time,
    /// filtered by `search`, a page at a time.
    private func list(_ request: FakeServer.Request, userID: String) -> FakeServer.Response {
        guard case .object(let body)? = Self.json(request.body), case .string(let prefix)? = body["prefix"] else {
            return Self.storageError(status: 400, code: "invalid_request", message: "prefix is required")
        }
        let folder = prefix.hasSuffix("/") ? prefix : prefix + "/"
        guard Self.owner(of: folder) == userID else { return .json("[]") }
        var entries: [String: [String: OfflineJSON]] = [:]
        for (objectPath, file) in files where objectPath.hasPrefix(folder) {
            let rest = objectPath.dropFirst(folder.count)
            if let slash = rest.firstIndex(of: "/") {
                let name = String(rest[..<slash])
                entries[name] = [
                    "name": .string(name), "id": .null, "updated_at": .null, "created_at": .null,
                    "last_accessed_at": .null, "metadata": .null,
                ]
            } else {
                entries[String(rest)] = Self.listing(of: file, named: String(rest))
            }
        }
        var found = Array(entries.values)
        if case .string(let search)? = body["search"], !search.isEmpty {
            found = found.filter { Self.text(of: $0["name"]).localizedCaseInsensitiveContains(search) }
        }
        let column: String
        let isAscending: Bool
        if case .object(let sortBy)? = body["sortBy"], case .string(let sortColumn)? = sortBy["column"] {
            column = sortColumn
            isAscending = sortBy["order"] != .string("desc")
        } else {
            column = "name"
            isAscending = true
        }
        found.sort {
            let left = Self.text(of: $0[column]), right = Self.text(of: $1[column])
            return isAscending ? left < right : left > right
        }
        let offset = Self.int(body["offset"]) ?? 0
        let limit = Self.int(body["limit"]) ?? 100
        let page = Array(found.dropFirst(offset).prefix(limit))
        return .json(Self.text(.array(page.map(OfflineJSON.object))))
    }

    /// Moves or copies a file within the user's folder.
    private func moveOrCopy(_ request: FakeServer.Request, route: StorageRoute, userID: String) -> FakeServer.Response {
        guard case .object(let body)? = Self.json(request.body), case .string(let source)? = body["sourceKey"],
            case .string(let destination)? = body["destinationKey"]
        else {
            return Self.storageError(status: 400, code: "invalid_request", message: "sourceKey and destinationKey are required")
        }
        guard Self.owner(of: source) == userID, Self.owner(of: destination) == userID else { return Self.unauthorized }
        guard var file = files[source] else { return Self.notFound }
        guard files[destination] == nil else {
            return Self.storageError(status: 409, code: "Duplicate", message: "The resource already exists")
        }
        if case .move = route {
            files[source] = nil
            files[destination] = file
            return .json(#"{"message":"Successfully moved"}"#)
        }
        file.createdAt = .now
        file.updatedAt = .now
        files[destination] = file
        return .json(Self.text(["Key": .string("\(PhotoPath.bucket)/\(destination)")]))
    }

    /// Deletes the user's files the body names.
    private func remove(_ request: FakeServer.Request, userID: String) -> FakeServer.Response {
        guard case .object(let body)? = Self.json(request.body), case .array(let prefixes)? = body["prefixes"] else {
            return Self.storageError(status: 400, code: "invalid_request", message: "prefixes is required")
        }
        var removed: [OfflineJSON] = []
        for case .string(let objectPath) in prefixes where Self.owner(of: objectPath) == userID {
            if files.removeValue(forKey: objectPath) != nil {
                removed.append(["name": .string(objectPath), "bucket_id": .string(PhotoPath.bucket)])
            }
        }
        return .json(Self.text(.array(removed)))
    }

    /// The user an object path belongs to: its first folder.
    private static func owner(of objectPath: String) -> String? {
        objectPath.split(separator: "/").first.map(String.init)
    }

    /// The path of a signed link to `objectPath`, as Storage answers it.
    private static func signedPath(_ objectPath: String) -> String {
        "/object/sign/\(PhotoPath.bucket)/\(objectPath)?token=demo-signed"
    }

    /// Storage's details of `file`, as its info call answers them.
    private static func details(of file: StoredFile, at objectPath: String) -> OfflineJSON {
        [
            "id": .string(DeviceID.derived(from: objectPath)), "version": "1", "name": .string(objectPath),
            "bucket_id": .string(PhotoPath.bucket), "size": .int(file.data.count), "content_type": .string(file.contentType),
            "cache_control": "max-age=3600", "etag": .string("\"\(file.data.count)\""),
            "created_at": .string(timestamp(file.createdAt)), "updated_at": .string(timestamp(file.updatedAt)),
            "last_modified": .string(timestamp(file.updatedAt)), "last_accessed_at": .null,
            "metadata": .object(file.metadata),
        ]
    }

    /// A file as a listing names it.
    private static func listing(of file: StoredFile, named name: String) -> [String: OfflineJSON] {
        [
            "name": .string(name), "id": .string(DeviceID.derived(from: name)),
            "created_at": .string(timestamp(file.createdAt)), "updated_at": .string(timestamp(file.updatedAt)),
            "last_accessed_at": .null,
            "metadata": ["size": .int(file.data.count), "mimetype": .string(file.contentType)],
        ]
    }

    /// The user metadata an upload carries, base64-encoded JSON in `x-metadata`.
    private static func userMetadata(in headers: [String: String]) -> [String: OfflineJSON]? {
        guard let encoded = header("x-metadata", in: headers), let data = Data(base64Encoded: encoded),
            case .object(let metadata)? = json(data)
        else { return nil }
        return metadata
    }

    /// Storage's answer to an object the user cannot reach.
    private static let unauthorized = storageError(
        status: 400,
        code: "Unauthorized",
        message: "new row violates row-level security policy",
        statusText: "403"
    )

    /// Storage's answer to an object that is not there.
    private static let notFound = storageError(status: 400, code: "not_found", message: "Object not found", statusText: "404")

    // MARK: - Encoding

    /// `body` read as JSON.
    private static func json(_ body: Data) -> OfflineJSON? {
        try? JSONDecoder().decode(OfflineJSON.self, from: body)
    }

    /// The objects of an insert's body: one object, or an array of them.
    private static func objects(in body: Data) -> [[String: OfflineJSON]]? {
        switch json(body) {
        case .object(let object)?:
            return [object]
        case .array(let elements)?:
            return elements.compactMap { element in
                guard case .object(let object) = element else { return nil }
                return object
            }
        default:
            return nil
        }
    }

    /// `json` as JSON text.
    private static func text(_ json: OfflineJSON) -> String {
        (try? String(decoding: JSONEncoder().encode(json), as: UTF8.self)) ?? "null"
    }

    /// A JSON value as text, for sorting and searching a listing.
    private static func text(of value: OfflineJSON?) -> String {
        if case .string(let text)? = value { return text }
        return ""
    }

    /// A JSON number as an `Int`.
    private static func int(_ value: OfflineJSON?) -> Int? {
        switch value {
        case .int(let int)?: return int
        case .double(let double)?: return Int(double)
        default: return nil
        }
    }

    /// A read's answer: the rows, one row when the request asked for an object, or their count
    /// alone for `HEAD`.
    private static func rowsResponse(_ rows: [[String: OfflineJSON]], method: String, total: Int, query: Query) -> FakeServer.Response {
        let range = rows.isEmpty ? "*/\(total)" : "0-\(rows.count - 1)/\(total)"
        if method == "HEAD" { return .status(200, headers: ["Content-Range": range]) }
        if query.wantsObject {
            guard rows.count == 1, let row = rows.first else {
                return postgrestError(status: 406, code: "PGRST116", message: "Cannot coerce the result to a single JSON object")
            }
            return .json(text(.object(row)), headers: ["Content-Range": range])
        }
        return .json(text(.array(rows.map(OfflineJSON.object))), headers: ["Content-Range": range])
    }

    /// A write's answer: the written rows when the request asked for them (one, as an object, when
    /// it asked for an object), otherwise none.
    private static func writeResponse(_ rows: [[String: OfflineJSON]], query: Query, created: Bool) -> FakeServer.Response {
        guard query.returnsRepresentation else { return .status(created ? 201 : 204) }
        let projected = rows.map(query.projected)
        if query.wantsObject, projected.count == 1, let row = projected.first {
            return .json(text(.object(row)), status: created ? 201 : 200)
        }
        return .json(text(.array(projected.map(OfflineJSON.object))), status: created ? 201 : 200)
    }

    /// PostgREST's error body.
    private static func postgrestError(status: Int, code: String, message: String) -> FakeServer.Response {
        .json(text(["code": .string(code), "message": .string(message), "details": .null, "hint": .null]), status: status)
    }

    /// A check constraint's refusal, as Postgres words it.
    private static func checkViolation(table: String, constraint: String) -> FakeServer.Response {
        postgrestError(
            status: 400,
            code: "23514",
            message: "new row for relation \"\(table)\" violates check constraint \"\(constraint)\""
        )
    }

    /// Storage's error body; `statusText` is the status Storage writes inside it when it differs
    /// from the HTTP status.
    private static func storageError(status: Int, code: String, message: String, statusText: String? = nil) -> FakeServer.Response {
        .json(
            text(["statusCode": .string(statusText ?? "\(status)"), "error": .string(code), "message": .string(message)]),
            status: status
        )
    }

    /// `date` as PostgREST writes a `timestamptz`.
    nonisolated static func timestamp(_ date: Date) -> String {
        date.formatted(.iso8601.year().month().day().time(includingFractionalSeconds: true).timeZone(separator: .omitted))
    }
}

/// The Storage operation a request is, from its method and its path below `/storage/v1/`.
nonisolated private enum StorageRoute {
    case upload(String)
    case update(String)
    case download(String)
    case exists(String)
    case info(String)
    case list
    case move
    case copy
    case remove
    case sign(String)
    case signSeveral
    case signUpload(String)
    case uploadToSigned(String)
    case unknown

    /// The operation `method` on `path` asks for.
    init(method: String, path: String) {
        let bucket = PhotoPath.bucket
        let below = { (prefix: String) -> String? in path.hasPrefix(prefix) ? String(path.dropFirst(prefix.count)) : nil }
        switch method {
        case "POST" where path == "object/move": self = .move
        case "POST" where path == "object/copy": self = .copy
        case "POST" where path == "object/list/\(bucket)": self = .list
        case "POST" where path == "object/sign/\(bucket)": self = .signSeveral
        case "DELETE" where path == "object/\(bucket)": self = .remove
        default:
            if let objectPath = below("object/upload/sign/\(bucket)/") {
                self = method == "POST" ? .signUpload(objectPath) : .uploadToSigned(objectPath)
            } else if let objectPath = below("object/sign/\(bucket)/"), method == "POST" {
                self = .sign(objectPath)
            } else if let objectPath = below("object/info/authenticated/\(bucket)/") ?? below("object/info/\(bucket)/") {
                self = .info(objectPath)
            } else if let objectPath = below("object/authenticated/\(bucket)/"), method == "GET" {
                self = .download(objectPath)
            } else if let objectPath = below("object/\(bucket)/") {
                switch method {
                case "POST": self = .upload(objectPath)
                case "PUT": self = .update(objectPath)
                case "HEAD": self = .exists(objectPath)
                case "GET": self = .download(objectPath)
                default: self = .unknown
                }
            } else {
                self = .unknown
            }
        }
    }
}

/// The parts of a PostgREST request the demo server reads: filters (a group of them included),
/// order, paging, and what to send back.
nonisolated private struct Query {

    /// One condition: a column, an operator (negated or not) and its value as PostgREST text.
    struct Condition {
        let column: String
        let isNegated: Bool
        let operation: String
        let value: String
    }

    /// The conditions, every one of which a row must meet: the top-level filters and the
    /// conditions of an `and` group.
    let conditions: [Condition]

    /// The `order` terms: a column and whether it sorts ascending.
    let order: [(column: String, ascending: Bool)]

    /// The columns asked for; `nil` for every column.
    let columns: [String]?

    /// `offset` and `limit`, when given.
    let offset: Int?
    let limit: Int?

    /// What the `Prefer` header asks for.
    let preferences: String

    /// Whether the request asked for one object rather than a list.
    let wantsObject: Bool

    /// The parts of `url` and `headers`.
    init(url: URL, headers: [String: String]) {
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        var conditions: [Condition] = []
        var order: [(String, Bool)] = []
        var columns: [String]?
        var offset: Int?
        var limit: Int?
        for item in items {
            let value = item.value ?? ""
            switch item.name {
            case "select":
                columns = value == "*" ? nil : value.split(separator: ",").map(String.init)
            case "order":
                order = value.split(separator: ",").map { term in
                    let parts = term.split(separator: ".")
                    return (String(parts[0]), !parts.contains("desc"))
                }
            case "offset":
                offset = Int(value)
            case "limit":
                limit = Int(value)
            case "columns", "on_conflict":
                continue
            case "and":
                conditions += Self.groupConditions(value)
            default:
                if let condition = Self.condition(column: item.name, text: value) { conditions.append(condition) }
            }
        }
        self.conditions = conditions
        self.order = order
        self.columns = columns
        self.offset = offset
        self.limit = limit
        preferences = headers.first { $0.key.lowercased() == "prefer" }?.value ?? ""
        wantsObject = headers.first { $0.key.lowercased() == "accept" }?.value.contains("vnd.pgrst.object") == true
    }

    /// Whether the write asked for its rows back.
    var returnsRepresentation: Bool {
        preferences.contains("return=representation")
    }

    /// Whether an insert merges into a row with the same key (an upsert).
    var mergesDuplicates: Bool {
        preferences.contains("resolution=merge-duplicates")
    }

    /// Whether an insert leaves a row with the same key as it is (an upsert that ignores duplicates).
    var ignoresDuplicates: Bool {
        preferences.contains("resolution=ignore-duplicates")
    }

    /// Whether `row` meets every condition.
    func matches(_ row: [String: OfflineJSON]) -> Bool {
        conditions.allSatisfy { condition in
            let isMet = Self.isMet(condition, by: Self.text(of: row[condition.column] ?? .null))
            return condition.isNegated ? !isMet : isMet
        }
    }

    /// The rows that meet every condition.
    func matching(_ rows: [[String: OfflineJSON]]) -> [[String: OfflineJSON]] {
        rows.filter(matches)
    }

    /// `rows` in the order asked for.
    func ordered(_ rows: [[String: OfflineJSON]]) -> [[String: OfflineJSON]] {
        rows.sorted { left, right in
            for term in order {
                let leftText = Self.text(of: left[term.column] ?? .null)
                let rightText = Self.text(of: right[term.column] ?? .null)
                guard leftText != rightText else { continue }
                let ascending = Self.sortKey(leftText) < Self.sortKey(rightText)
                return term.ascending ? ascending : !ascending
            }
            return false
        }
    }

    /// The page of `rows` that `offset` and `limit` ask for.
    func page(_ rows: [[String: OfflineJSON]]) -> [[String: OfflineJSON]] {
        let start = min(offset ?? 0, rows.count)
        let end = limit.map { min(start + $0, rows.count) } ?? rows.count
        return Array(rows[start..<end])
    }

    /// `row` with only the columns asked for.
    func projected(_ row: [String: OfflineJSON]) -> [String: OfflineJSON] {
        guard let columns else { return row }
        return row.filter { columns.contains($0.key) }
    }

    /// The condition `text` (`gte.2026-10-05`, `not.is.null`) sets on `column`.
    private static func condition(column: String, text: String) -> Condition? {
        var rest = Substring(text)
        let isNegated = rest.hasPrefix("not.")
        if isNegated { rest = rest.dropFirst("not.".count) }
        guard let dot = rest.firstIndex(of: ".") else { return nil }
        return Condition(
            column: column,
            isNegated: isNegated,
            operation: String(rest[..<dot]),
            value: unquoted(String(rest[rest.index(after: dot)...]))
        )
    }

    /// The conditions of an `and` group: `(created_at.gte."2026-10-05T00:00:00Z",created_at.lt."…")`.
    private static func groupConditions(_ text: String) -> [Condition] {
        let inner = text.hasPrefix("(") && text.hasSuffix(")") ? String(text.dropFirst().dropLast()) : text
        return topLevelParts(of: inner).compactMap { part in
            guard let dot = part.firstIndex(of: ".") else { return nil }
            return condition(column: String(part[..<dot]), text: String(part[part.index(after: dot)...]))
        }
    }

    /// `text` split at the commas outside quotes and parentheses.
    private static func topLevelParts(of text: String) -> [String] {
        var parts: [String] = []
        var current = ""
        var depth = 0
        var isQuoted = false
        for character in text {
            switch character {
            case "\"": isQuoted.toggle()
            case "(" where !isQuoted: depth += 1
            case ")" where !isQuoted: depth -= 1
            case "," where !isQuoted && depth == 0:
                parts.append(current)
                current = ""
                continue
            default: break
            }
            current.append(character)
        }
        if !current.isEmpty { parts.append(current) }
        return parts
    }

    /// `value` without the double quotes PostgREST text may wrap it in.
    private static func unquoted(_ value: String) -> String {
        guard value.count >= 2, value.hasPrefix("\""), value.hasSuffix("\"") else { return value }
        return String(value.dropFirst().dropLast())
    }

    /// Whether a column's text `text` meets `condition`, as PostgREST reads it.
    private static func isMet(_ condition: Condition, by text: String) -> Bool {
        switch condition.operation {
        case "eq", "is": return text == condition.value
        case "neq": return text != condition.value
        case "gt": return sortKey(text) > sortKey(condition.value)
        case "gte": return sortKey(text) >= sortKey(condition.value)
        case "lt": return sortKey(text) < sortKey(condition.value)
        case "lte": return sortKey(text) <= sortKey(condition.value)
        case "ilike": return text.range(of: likePattern(condition.value), options: [.regularExpression, .caseInsensitive]) != nil
        case "in": return inValues(condition.value).contains(text)
        default: return false
        }
    }

    /// A `like` pattern as a regular expression: `%` and `*` match anything, the rest itself.
    private static func likePattern(_ pattern: String) -> String {
        let escaped = NSRegularExpression.escapedPattern(for: pattern)
        return "^" + escaped.replacingOccurrences(of: "%", with: ".*").replacingOccurrences(of: "\\*", with: ".*") + "$"
    }

    /// The values of an `in` list: `(a,"b",c)`.
    private static func inValues(_ list: String) -> Set<String> {
        let inner = list.hasPrefix("(") && list.hasSuffix(")") ? String(list.dropFirst().dropLast()) : list
        return Set(topLevelParts(of: inner).map(unquoted))
    }

    /// A value as PostgREST compares it in a filter.
    private static func text(of value: OfflineJSON) -> String {
        switch value {
        case .null: return "null"
        case .bool(let bool): return bool ? "true" : "false"
        case .int(let int): return String(int)
        case .double(let double): return String(double)
        case .string(let string): return string
        case .array, .object: return ""
        }
    }

    /// A sort key that orders UTC timestamps by time, whatever their fraction digits, and other
    /// text as text.
    private static func sortKey(_ text: String) -> String {
        let characters = Array(text)
        guard characters.count >= 19, characters[10] == "T", characters[13] == ":", characters[16] == ":" else {
            return text
        }
        let fraction = characters.dropFirst(19).first == "." ? characters.dropFirst(20).prefix { $0.isNumber } : []
        let digits = String(fraction.prefix(6)).padding(toLength: 6, withPad: "0", startingAt: 0)
        return String(characters.prefix(19)) + "." + digits
    }
}
