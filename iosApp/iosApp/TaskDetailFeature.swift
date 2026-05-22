import Foundation
import ComposableArchitecture
import SupabaseOfflineSupport

@Reducer
struct TaskDetailFeature {

    @ObservableState
    struct State: Equatable {
        let taskId: String
        var task: TodoTask?
        var titleDraft: String = ""
        var notesDraft: String = ""
        var photos: IdentifiedArrayOf<TaskPhotoRow> = []
        var error: String?
        var isLoading: Bool = false
        var isUploading: Bool = false

        init(taskId: String) {
            self.taskId = taskId
        }
    }

    enum Action: Equatable {
        case onAppear
        case loadTask
        case taskLoaded(TodoTask?)
        case loadFailed(String)

        case setTitleDraft(String)
        case setNotesDraft(String)
        case saveTapped
        case saveSucceeded
        case saveFailed(String)

        case loadPhotos
        case photosLoaded([TaskPhotoRow])

        case addPhotoPicked(Data, contentType: String)
        case uploadFinished
        case uploadFailed(String)

        case clearError
    }

    @Dependency(\.supabaseOfflineClient) var offlineClient

    var body: some ReducerOf<Self> {
        Reduce { state, action in
            switch action {
            case .onAppear:
                return .merge(.send(.loadTask), .send(.loadPhotos))

            case .loadTask:
                state.isLoading = true
                let taskId = state.taskId
                return .run { [client = offlineClient] send in
                    do {
                        let result = try await client.get(table: "tasks").selectAll(
                            readStrategy: .cacheFirst,
                            filter: { $0.eq(column: "id", value: taskId) }
                        )
                        let rows = try result.decode([TodoTask].self)
                        await send(.taskLoaded(rows.first))
                    } catch {
                        await send(.loadFailed(error.localizedDescription))
                    }
                }

            case let .taskLoaded(task):
                state.isLoading = false
                state.task = task
                if let task {
                    state.titleDraft = task.title
                    state.notesDraft = task.notes ?? ""
                }
                return .none

            case let .loadFailed(message):
                state.isLoading = false
                state.error = message
                return .none

            case let .setTitleDraft(value):
                state.titleDraft = value
                return .none

            case let .setNotesDraft(value):
                state.notesDraft = value
                return .none

            case .saveTapped:
                let title = state.titleDraft.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !title.isEmpty else { return .none }
                let notes = state.notesDraft
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                    .nilIfEmpty
                let taskId = state.taskId
                state.task?.title = title
                state.task?.notes = notes

                return .run { [client = offlineClient] send in
                    do {
                        let patch = TodoTaskPatch(title: title, notes: notes)
                        _ = try await client.get(table: "tasks").updateOne(
                            value: patch,
                            filter: { $0.eq(column: "id", value: taskId) }
                        )
                        await send(.saveSucceeded)
                    } catch {
                        await send(.saveFailed(error.localizedDescription))
                    }
                }

            case .saveSucceeded:
                return .none

            case let .saveFailed(message):
                state.error = message
                return .none

            case .loadPhotos:
                let taskId = state.taskId
                return .run { [client = offlineClient] send in
                    do {
                        let bucket = client.storage(bucket: "task-photos")
                        let result = try await client.get(table: "task_photos").selectAll(
                            readStrategy: .networkFirst,
                            orderBy: "uploaded_at",
                            ascending: false,
                            filter: { $0.eq(column: "task_id", value: taskId) }
                        )
                        let rows = try result.decode([TaskPhoto].self)

                        var entries: [TaskPhotoRow] = []
                        for row in rows {
                            let localUrl = try? await bucket.ensureLocalFileUrl(
                                objectPath: row.objectPath,
                                isPublic: true
                            )
                            entries.append(
                                TaskPhotoRow(
                                    id: row.id ?? row.objectPath,
                                    objectPath: row.objectPath,
                                    localFileUrl: localUrl
                                )
                            )
                        }
                        await send(.photosLoaded(entries))
                    } catch {
                        await send(.photosLoaded([]))
                    }
                }

            case let .photosLoaded(rows):
                state.photos = IdentifiedArrayOf(uniqueElements: rows)
                return .none

            case let .addPhotoPicked(data, contentType):
                guard !data.isEmpty else { return .none }
                state.isUploading = true
                let taskId = state.taskId
                let bytes = data
                let mime = contentType
                return .run { [client = offlineClient] send in
                    do {
                        let bucket = client.storage(bucket: "task-photos")
                        let resumable = bucket.resumable
                        let objectPath = "\(taskId)/\(UUID().uuidString)"

                        let upload = try await resumable.createOrContinueUpload(
                            data: bytes,
                            source: objectPath,
                            path: objectPath
                        ) { opts in
                            opts.upsert = true
                        }
                        try await upload.startOrResumeUploading()

                        let newRow = TaskPhoto(
                            taskId: taskId,
                            bucketId: "task-photos",
                            objectPath: objectPath,
                            contentType: mime,
                            sizeBytes: Int64(bytes.count)
                        )
                        _ = try await client.get(table: "task_photos").insertOne(value: newRow)

                        await send(.uploadFinished)
                        await send(.loadPhotos)
                    } catch {
                        await send(.uploadFailed(error.localizedDescription))
                    }
                }

            case .uploadFinished:
                state.isUploading = false
                return .none

            case let .uploadFailed(message):
                state.isUploading = false
                state.error = message
                return .none

            case .clearError:
                state.error = nil
                return .none
            }
        }
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
