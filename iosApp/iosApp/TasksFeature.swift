import Foundation
import ComposableArchitecture
import SupabaseOfflineSupport

// MARK: - SupabaseOfflineClient dependency

private enum SupabaseOfflineClientKey: DependencyKey {
    static var liveValue: SupabaseOfflineClient {
        fatalError("SupabaseOfflineClient not configured. Inject via .dependency(\\.supabaseOfflineClient, …)")
    }
}

extension DependencyValues {
    var supabaseOfflineClient: SupabaseOfflineClient {
        get { self[SupabaseOfflineClientKey.self] }
        set { self[SupabaseOfflineClientKey.self] = newValue }
    }
}

// MARK: - Tasks Feature

@Reducer
struct TasksFeature {

    @ObservableState
    struct State: Equatable {
        var tasks: IdentifiedArrayOf<TodoTask> = []
        var error: String?
        var isLoading: Bool = false
        var pendingMutationCount: Int = 0
        var isSyncingMutations: Bool = false
        var isOnline: Bool = true

        // New task draft
        var newTaskTitle: String = ""
        var newTaskNotes: String = ""
    }

    enum Action: Equatable {
        case onAppear
        case onlineStatusChanged(Bool)

        case loadTasks
        case tasksLoaded([TodoTask])
        case tasksFailed(String)

        case setNewTaskTitle(String)
        case setNewTaskNotes(String)
        case addTaskTapped

        case toggleDoneTapped(taskId: String, currentlyDone: Bool)
        case deleteTapped(taskId: String)

        case mutationCompleted(Int)
        case mutationFailed(String, pendingCount: Int?)

        case syncPendingMutations
        case syncFinished(pendingCount: Int)
        case syncFailed(String)

        case clearError
    }

    @Dependency(\.supabaseOfflineClient) var offlineClient

    var body: some ReducerOf<Self> {
        Reduce { state, action in
            switch action {
            case .onAppear:
                return .merge(
                    .send(.loadTasks),
                    .send(.syncPendingMutations)
                )

            case let .onlineStatusChanged(isOnline):
                state.isOnline = isOnline
                return isOnline ? .send(.syncPendingMutations) : .none

            case .loadTasks:
                state.isLoading = true
                state.error = nil
                return .run { [client = offlineClient] send in
                    do {
                        let result = try await client.get(table: "tasks").selectAll(
                            readStrategy: .networkFirst,
                            orderBy: "created_at",
                            ascending: false
                        )
                        let rows = try result.decode([TodoTask].self)
                        await send(.tasksLoaded(rows))
                    } catch {
                        await send(.tasksFailed(error.localizedDescription))
                    }
                }

            case let .tasksLoaded(rows):
                state.isLoading = false
                state.tasks = IdentifiedArrayOf(
                    uniqueElements: rows.compactMap { row -> TodoTask? in
                        guard row.id != nil else { return nil }
                        return row
                    }
                )
                return .none

            case let .tasksFailed(message):
                state.isLoading = false
                state.error = message
                return .none

            case let .setNewTaskTitle(value):
                state.newTaskTitle = value
                return .none

            case let .setNewTaskNotes(value):
                state.newTaskNotes = value
                return .none

            case .addTaskTapped:
                let trimmedTitle = state.newTaskTitle.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !trimmedTitle.isEmpty else { return .none }
                let notes = state.newTaskNotes
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                    .nilIfEmpty
                state.newTaskTitle = ""
                state.newTaskNotes = ""

                return .run { [client = offlineClient] send in
                    do {
                        let newTask = TodoTask(title: trimmedTitle, notes: notes)
                        _ = try await client.get(table: "tasks").insertOne(value: newTask)
                        let pending = try await client.pendingMutationCount()
                        await send(.mutationCompleted(Int(truncatingIfNeeded: pending.intValue)))
                        await send(.loadTasks)
                    } catch {
                        let pending = try? await client.pendingMutationCount()
                        await send(.mutationFailed(
                            error.localizedDescription,
                            pendingCount: pending.map { Int(truncatingIfNeeded: $0.intValue) }
                        ))
                    }
                }

            case let .toggleDoneTapped(taskId, currentlyDone):
                state.tasks[id: taskId]?.isDone = !currentlyDone
                return .run { [client = offlineClient] send in
                    do {
                        let patch = TodoTaskPatch(isDone: !currentlyDone)
                        _ = try await client.get(table: "tasks").updateOne(
                            value: patch,
                            filter: { $0.eq(column: "id", value: taskId) }
                        )
                        let pending = try await client.pendingMutationCount()
                        await send(.mutationCompleted(Int(truncatingIfNeeded: pending.intValue)))
                    } catch {
                        let pending = try? await client.pendingMutationCount()
                        await send(.mutationFailed(
                            error.localizedDescription,
                            pendingCount: pending.map { Int(truncatingIfNeeded: $0.intValue) }
                        ))
                    }
                }

            case let .deleteTapped(taskId):
                state.tasks.remove(id: taskId)
                return .run { [client = offlineClient] send in
                    do {
                        _ = try await client.get(table: "tasks").deleteWhere(
                            filter: { $0.eq(column: "id", value: taskId) }
                        )
                        let pending = try await client.pendingMutationCount()
                        await send(.mutationCompleted(Int(truncatingIfNeeded: pending.intValue)))
                    } catch {
                        let pending = try? await client.pendingMutationCount()
                        await send(.mutationFailed(
                            error.localizedDescription,
                            pendingCount: pending.map { Int(truncatingIfNeeded: $0.intValue) }
                        ))
                    }
                }

            case let .mutationCompleted(pendingCount):
                state.pendingMutationCount = pendingCount
                return .none

            case let .mutationFailed(message, pendingCount):
                state.error = message
                if let pendingCount {
                    state.pendingMutationCount = pendingCount
                }
                return .none

            case .syncPendingMutations:
                state.isSyncingMutations = true
                return .run { [client = offlineClient] send in
                    do {
                        let pending = try await client.pendingMutationCount()
                        let pendingInt = Int(truncatingIfNeeded: pending.intValue)
                        guard pendingInt > 0 else {
                            await send(.syncFinished(pendingCount: pendingInt))
                            return
                        }
                        _ = try await client.syncAllPendingMutations()
                        let remaining = try await client.pendingMutationCount()
                        await send(.syncFinished(pendingCount: Int(truncatingIfNeeded: remaining.intValue)))
                        await send(.loadTasks)
                    } catch {
                        await send(.syncFailed(error.localizedDescription))
                    }
                }

            case let .syncFinished(pendingCount):
                state.isSyncingMutations = false
                state.pendingMutationCount = pendingCount
                return .none

            case let .syncFailed(message):
                state.isSyncingMutations = false
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
