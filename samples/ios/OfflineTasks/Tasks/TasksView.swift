// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import SupabaseOffline
import SwiftUI

/// A task the navigation stack opens.
struct TaskRoute: Hashable {

    /// The task's id.
    let id: String
}

/// The task list: open and done, newest first, each marked while its change waits to sync.
struct TasksView: View {

    @Environment(AppModel.self) private var app
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isAddingTask = false

    var body: some View {
        let state = app.tasks.state(session: app.session.state, network: app.syncStatus.network)
        content(for: state)
            .navigationTitle("Tasks")
            .navigationSubtitle(subtitle(for: state))
            .toolbar {
                // Every task needs its user: before the first sign-in there is no one to add it for.
                if state.canAddTasks {
                    ToolbarItem(placement: .primaryAction) {
                        Button("New task", systemImage: "plus") { isAddingTask = true }
                            .accessibilityIdentifier("new-task")
                    }
                }
            }
            .sheet(isPresented: $isAddingTask) {
                NewTaskSheet { title, notes in app.tasks.addTask(title: title, notes: notes) }
            }
            .navigationDestination(for: TaskRoute.self) { route in
                TaskDetailView(
                    model: TaskDetailModel(taskID: route.id, repository: app.repository, failures: app.failureSink)
                )
            }
    }

    /// The list, or the state that stands in for it.
    @ViewBuilder
    private func content(for state: TasksListState) -> some View {
        switch state {
        case .noSession(let isSigningIn):
            ContentUnavailableView {
                Label("Not signed in yet", systemImage: "person.crop.circle.badge.questionmark")
            } description: {
                Text(
                    isSigningIn
                        ? "Signing you in, anonymously. Your list starts here in a moment."
                        : "The app signs you in, anonymously, the first time it reaches the internet. Until then there’s nothing to show and nothing to add."
                )
            }
        case .noDataYet(isLoading: true):
            ContentUnavailableView {
                Label("Loading your tasks", systemImage: "arrow.down.circle")
            } description: {
                Text("Nothing is saved on this device yet, so the list comes from the server this once.")
            } actions: {
                ProgressView()
            }
        case .noDataYet(isLoading: false):
            ContentUnavailableView {
                Label("Nothing saved on this device yet", systemImage: "icloud.slash")
            } description: {
                Text("Connect to the internet once to load your tasks. After that they’re here offline too.")
            }
        case .loaded(let open, let done):
            list(open: open, done: done)
        }
    }

    /// The open and done sections.
    private func list(open: [TaskItem], done: [TaskItem]) -> some View {
        List {
            if app.mode.isDemo {
                Section { DemoModeNotice(reason: app.mode) }
            }
            if !open.isEmpty {
                Section("Open · \(open.count)") {
                    ForEach(open) { TaskRowView(task: $0) }
                }
            }
            if !done.isEmpty {
                Section("Done · \(done.count)") {
                    ForEach(done) { TaskRowView(task: $0) }
                }
            }
        }
        .overlay {
            if open.isEmpty, done.isEmpty {
                ContentUnavailableView {
                    Label("No tasks yet", systemImage: "checklist")
                } description: {
                    Text("Add one with New task. It’s saved on this device at once and synced when you’re online.")
                }
            }
        }
        .refreshable { await app.tasks.refreshList() }
        .animation(reduceMotion ? nil : .snappy, value: open.map(\.id) + done.map(\.id))
    }

    /// "3 open · 2 waiting to sync" under the large title.
    private func subtitle(for state: TasksListState) -> String {
        guard case .loaded(let open, _) = state else { return app.headline.subtitle }
        return "\(open.count) open · \(app.headline.subtitle)"
    }
}

/// One task in the list: tap the circle to complete it, the row to open it, swipe to delete.
struct TaskRowView: View {

    /// The task shown.
    let task: TaskItem

    @Environment(AppModel.self) private var app
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: 12) {
            Button {
                withAnimation(reduceMotion ? nil : .snappy) { app.tasks.setDone(task, isDone: !task.isDone) }
            } label: {
                Image(systemName: task.isDone ? "checkmark.circle.fill" : "circle")
                    .font(.title2)
                    .foregroundStyle(task.isDone ? Color.accentColor : .secondary)
                    .contentTransition(.symbolEffect(.replace))
            }
            .buttonStyle(.borderless)
            .sensoryFeedback(.success, trigger: task.isDone) { _, isDone in isDone }
            .accessibilityLabel(task.isDone ? "Done: \(Wording.title(task.title))" : "Open: \(Wording.title(task.title))")
            .accessibilityHint(task.isDone ? "Marks it open again" : "Marks it done")

            NavigationLink(value: TaskRoute(id: task.id)) {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(Wording.title(task.title))
                            .strikethrough(task.isDone)
                            .foregroundStyle(task.isDone ? .secondary : .primary)
                        if let notes = task.notes, !notes.isEmpty {
                            Text(notes)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                    }
                    Spacer(minLength: 8)
                    if task.hasPendingMutations {
                        Image(systemName: "icloud.and.arrow.up")
                            .foregroundStyle(.secondary)
                            .symbolEffect(.pulse, isActive: app.syncStatus.isSyncing && !reduceMotion)
                            .accessibilityLabel("Not synced yet")
                            .accessibilityIdentifier("not-synced")
                    }
                }
            }
        }
        .swipeActions(edge: .trailing) {
            Button("Delete", systemImage: "trash", role: .destructive) { app.tasks.delete(task) }
        }
    }
}

/// Says the app is in demo mode, and how to point it at a project.
struct DemoModeNotice: View {

    /// Why the app is in demo mode.
    let reason: LaunchMode

    var body: some View {
        Label {
            VStack(alignment: .leading, spacing: 4) {
                Text("Demo mode").font(.headline)
                Text(reason == .demo(reason: .noKeys) ? Self.noKeys : Self.requested)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        } icon: {
            Image(systemName: "testtube.2")
        }
        .accessibilityIdentifier("demo-mode")
    }

    /// Why, when no keys were given.
    static let noKeys =
        "No Supabase project is set, so the app runs on a stand-in kept in memory, and starts empty each launch. "
        + "Add SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY to Configuration/Secrets.xcconfig, then build again."

    /// Why, when the launch arguments asked for it.
    static let requested =
        "The launch arguments asked for demo mode: the app runs on a stand-in kept in memory, and starts empty each launch."
}
