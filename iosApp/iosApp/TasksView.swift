import SwiftUI
import ComposableArchitecture

struct TasksView: View {
    @Bindable var store: StoreOf<TasksFeature>

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                ConnectivityBanner(
                    isOnline: store.isOnline,
                    isSyncingMutations: store.isSyncingMutations,
                    pendingMutationCount: store.pendingMutationCount
                )

                NewTaskCard(
                    title: $store.newTaskTitle.sending(\.setNewTaskTitle),
                    notes: $store.newTaskNotes.sending(\.setNewTaskNotes),
                    onAdd: { store.send(.addTaskTapped) }
                )
                .padding(.horizontal, 16)
                .padding(.top, 12)

                if store.tasks.isEmpty && !store.isLoading {
                    Spacer()
                    Text("No tasks yet")
                        .foregroundColor(.secondary)
                    Spacer()
                } else {
                    List {
                        ForEach(store.tasks) { task in
                            NavigationLink(
                                destination: TaskDetailView(
                                    store: Store(
                                        initialState: TaskDetailFeature.State(taskId: task.id ?? "")
                                    ) {
                                        TaskDetailFeature()
                                    }
                                )
                            ) {
                                TaskRow(
                                    task: task,
                                    onToggle: {
                                        guard let id = task.id else { return }
                                        store.send(.toggleDoneTapped(taskId: id, currentlyDone: task.isDone))
                                    }
                                )
                            }
                        }
                        .onDelete { indexSet in
                            for index in indexSet {
                                if let id = store.tasks[index].id {
                                    store.send(.deleteTapped(taskId: id))
                                }
                            }
                        }
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle("Tasks")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        store.send(.loadTasks)
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                }
            }
            .alert(
                "Error",
                isPresented: Binding(
                    get: { store.error != nil },
                    set: { newValue in if !newValue { store.send(.clearError) } }
                ),
                actions: {
                    Button("OK") { store.send(.clearError) }
                },
                message: {
                    Text(store.error ?? "")
                }
            )
        }
        .onAppear { store.send(.onAppear) }
    }
}

// MARK: - Row

private struct TaskRow: View {
    let task: TodoTask
    let onToggle: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Button(action: onToggle) {
                Image(systemName: task.isDone ? "checkmark.circle.fill" : "circle")
                    .font(.title2)
                    .foregroundStyle(task.isDone ? Color.accentColor : Color.secondary)
            }
            .buttonStyle(.plain)

            VStack(alignment: .leading, spacing: 4) {
                Text(task.title)
                    .font(.headline)
                    .strikethrough(task.isDone)
                if let notes = task.notes, !notes.isEmpty {
                    Text(notes)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
            }
        }
        .padding(.vertical, 4)
    }
}

// MARK: - New task card

private struct NewTaskCard: View {
    @Binding var title: String
    @Binding var notes: String
    let onAdd: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            TextField("New task title", text: $title)
                .textFieldStyle(.roundedBorder)
            TextField("Notes (optional)", text: $notes, axis: .vertical)
                .lineLimit(2...4)
                .textFieldStyle(.roundedBorder)
            HStack {
                Spacer()
                Button("Add", action: onAdd)
                    .buttonStyle(.borderedProminent)
                    .disabled(title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding(12)
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(Color(.secondarySystemBackground))
        )
    }
}

// MARK: - Connectivity banner

struct ConnectivityBanner: View {
    let isOnline: Bool
    let isSyncingMutations: Bool
    let pendingMutationCount: Int

    private var message: (String, Color)? {
        if !isOnline {
            let label = pendingMutationCount > 0
                ? "Offline — \(pendingMutationCount) change(s) will sync when online"
                : "Offline — changes will sync when online"
            return (label, Color.red.opacity(0.85))
        }
        if isSyncingMutations {
            return ("Syncing…", Color.blue.opacity(0.85))
        }
        if pendingMutationCount > 0 {
            return ("\(pendingMutationCount) change(s) pending sync", Color.green.opacity(0.85))
        }
        return nil
    }

    var body: some View {
        if let (text, color) = message {
            HStack(spacing: 6) {
                Text(text)
                    .font(.caption)
                    .foregroundColor(.white)
                Spacer()
                if isSyncingMutations {
                    ProgressView()
                        .progressViewStyle(.circular)
                        .tint(.white)
                        .scaleEffect(0.7)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .frame(maxWidth: .infinity)
            .background(color)
        }
    }
}
