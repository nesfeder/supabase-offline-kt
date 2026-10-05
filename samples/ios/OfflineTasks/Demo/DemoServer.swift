// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline
import SupabaseOfflineTesting

/// A stand-in for the samples' Supabase project, in memory: it answers the in-memory client's
/// PostgREST and Storage requests the way the project's schema would, for demo mode, previews and
/// tests. It is not part of what an app built on the library needs.
///
/// It keeps the schema's rules: a request without a user's token reaches nothing, each user reaches
/// only their own rows and files, a blank or over-long title and over-long notes are refused (HTTP
/// 400, 23514), a photo needs its owner's task (23503), an id already taken is refused (23505), and
/// the bucket takes only a JPEG under 5 MB at the owner's own path, never replaced.
actor DemoServer {

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

    /// The rows of each table, by id, in the order they were inserted.
    private var tables: [String: [(id: String, row: [String: OfflineJSON])]] = [Tables.tasks: [], Tables.taskPhotos: []]

    /// The bucket's files, by object path.
    private var files: [String: Data] = [:]

    /// An answer a test puts in front of the server's own: when it returns a response, that is the
    /// answer.
    private var interception: (@Sendable (FakeServer.Request, String?) -> FakeServer.Response?)?

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

    // MARK: - Answering

    /// The answer to `request`.
    private func response(to request: FakeServer.Request) -> FakeServer.Response {
        let userID = Self.userID(in: request.headers)
        requests.append(
            RecordedRequest(
                method: request.method,
                path: request.url.path(percentEncoded: false),
                userID: userID,
                body: String(decoding: request.body, as: UTF8.self)
            )
        )
        if let answer = interception?(request, userID) { return answer }
        let path = request.url.path(percentEncoded: false)
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
        let authorization = headers.first { $0.key.lowercased() == "authorization" }?.value ?? ""
        let prefix = "Bearer \(accessToken(for: ""))"
        guard authorization.hasPrefix(prefix) else { return nil }
        return String(authorization.dropFirst(prefix.count))
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
            return Self.rowsResponse(page.map(query.projected), method: request.method, total: rows.count)
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

    /// Inserts `objects`, all or none, as `userID`.
    private func insert(
        _ objects: [[String: OfflineJSON]]?,
        into table: String,
        userID: String,
        query: Query
    ) -> FakeServer.Response {
        guard let objects, !objects.isEmpty else {
            return Self.postgrestError(status: 400, code: "PGRST102", message: "Empty or invalid json")
        }
        var inserted: [[String: OfflineJSON]] = []
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
            if let index = tables[table, default: []].firstIndex(where: { $0.id == id }) {
                guard query.mergesDuplicates else {
                    return Self.postgrestError(
                        status: 409,
                        code: "23505",
                        message: "duplicate key value violates unique constraint \"\(table)_pkey\""
                    )
                }
                tables[table]?[index].row.merge(object) { _, given in given }
            } else {
                tables[table, default: []].append((id, row))
            }
            inserted.append(row)
        }
        return Self.writeResponse(inserted, query: query, created: true)
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
        return Self.writeResponse(updated, query: query, created: false)
    }

    /// Deletes the rows `query` matches, as `userID`; a task takes its photo rows with it, as the
    /// schema's cascade does (but never their files).
    private func delete(from table: String, userID: String, query: Query) -> FakeServer.Response {
        let doomed = tables[table, default: []].filter { $0.row["user_id"] == .string(userID) && query.matches($0.row) }
        let doomedIDs = Set(doomed.map(\.id))
        tables[table]?.removeAll { doomedIDs.contains($0.id) }
        if table == Tables.tasks {
            tables[Tables.taskPhotos]?.removeAll { photo in
                guard case .string(let taskID)? = photo.row["task_id"] else { return false }
                return doomedIDs.contains(taskID)
            }
        }
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
        let bucketPrefix = "object/\(PhotoPath.bucket)"
        let authenticatedPrefix = "object/authenticated/\(PhotoPath.bucket)/"
        let isUpload = request.method == "POST" || request.method == "PUT"
        switch request.method {
        case _ where isUpload && path.hasPrefix(bucketPrefix + "/"):
            let objectPath = String(path.dropFirst(bucketPrefix.count + 1))
            return upload(request, to: objectPath, userID: userID)
        case "GET" where path.hasPrefix(authenticatedPrefix):
            let objectPath = String(path.dropFirst(authenticatedPrefix.count))
            guard Self.owner(of: objectPath) == userID, let data = files[objectPath] else {
                return Self.storageError(status: 400, code: "not_found", message: "Object not found", statusText: "404")
            }
            return .status(200, headers: ["Content-Type": "image/jpeg"], body: data)
        case "DELETE" where path == bucketPrefix:
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
        default:
            return Self.storageError(status: 400, code: "invalid_request", message: "No such route")
        }
    }

    /// Stores an upload at `objectPath` as `userID`, as the bucket's settings and policies allow.
    private func upload(_ request: FakeServer.Request, to objectPath: String, userID: String) -> FakeServer.Response {
        guard Self.owner(of: objectPath) == userID else {
            return Self.storageError(status: 400, code: "Unauthorized", message: "new row violates row-level security policy", statusText: "403")
        }
        let contentType = request.headers.first { $0.key.lowercased() == "content-type" }?.value ?? ""
        guard contentType.hasPrefix("image/jpeg") else {
            return Self.storageError(status: 400, code: "invalid_mime_type", message: "mime type \(contentType) is not supported", statusText: "415")
        }
        guard request.body.count <= PhotoPath.maxBytes else {
            return Self.storageError(status: 413, code: "Payload too large", message: "The object exceeded the maximum allowed size")
        }
        let upsert = request.headers.first { $0.key.lowercased() == "x-upsert" }?.value == "true"
        if files[objectPath] != nil, !upsert {
            return Self.storageError(status: 409, code: "Duplicate", message: "The resource already exists")
        }
        files[objectPath] = request.body
        return .json(Self.text(["Key": .string("\(PhotoPath.bucket)/\(objectPath)"), "Id": .string(DeviceID.make())]))
    }

    /// The user an object path belongs to: its first folder.
    private static func owner(of objectPath: String) -> String? {
        objectPath.split(separator: "/").first.map(String.init)
    }

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

    /// A read's answer: the rows, or their count alone for `HEAD`.
    private static func rowsResponse(_ rows: [[String: OfflineJSON]], method: String, total: Int) -> FakeServer.Response {
        let range = rows.isEmpty ? "*/\(total)" : "0-\(rows.count - 1)/\(total)"
        if method == "HEAD" { return .status(200, headers: ["Content-Range": range]) }
        return .json(text(.array(rows.map(OfflineJSON.object))), headers: ["Content-Range": range])
    }

    /// A write's answer: the written rows when the request asked for them, otherwise none.
    private static func writeResponse(_ rows: [[String: OfflineJSON]], query: Query, created: Bool) -> FakeServer.Response {
        guard query.returnsRepresentation else { return .status(created ? 201 : 204) }
        return .json(text(.array(rows.map(OfflineJSON.object))), status: created ? 201 : 200)
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

/// The parts of a PostgREST request the demo server reads: filters, order, paging, and what to
/// send back.
nonisolated private struct Query {

    /// The `column=operator.value` filters.
    let filters: [(column: String, operator: String, value: String)]

    /// The `order` terms: a column and whether it sorts ascending.
    let order: [(column: String, ascending: Bool)]

    /// The columns asked for; `nil` for every column.
    let columns: [String]?

    /// `offset` and `limit`, when given.
    let offset: Int?
    let limit: Int?

    /// What the `Prefer` header asks for.
    let preferences: String

    /// The parts of `url` and `headers`.
    init(url: URL, headers: [String: String]) {
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        var filters: [(String, String, String)] = []
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
            default:
                guard let dot = value.firstIndex(of: ".") else { continue }
                filters.append((item.name, String(value[..<dot]), String(value[value.index(after: dot)...])))
            }
        }
        self.filters = filters
        self.order = order
        self.columns = columns
        self.offset = offset
        self.limit = limit
        preferences = headers.first { $0.key.lowercased() == "prefer" }?.value ?? ""
    }

    /// Whether the write asked for its rows back.
    var returnsRepresentation: Bool {
        preferences.contains("return=representation")
    }

    /// Whether an insert merges into a row with the same key (an upsert).
    var mergesDuplicates: Bool {
        preferences.contains("resolution=merge-duplicates")
    }

    /// Whether `row` meets every filter.
    func matches(_ row: [String: OfflineJSON]) -> Bool {
        filters.allSatisfy { filter in
            let text = Self.text(of: row[filter.column] ?? .null)
            switch filter.operator {
            case "eq": return text == filter.value
            case "neq": return text != filter.value
            case "is": return text == filter.value
            default: return false
            }
        }
    }

    /// The rows that meet every filter.
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
