// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import SwiftUI

/// A new task's title and notes, and how it is sent. Add waits for the write: queued, it returns at
/// once; sent only straight to the server (Needs a connection), it fails offline, and the sheet
/// says why and stays open.
struct NewTaskSheet: View {

    /// Adds the task with its title, notes and Needs a connection; returns what went wrong, or
    /// `nil` once it is saved.
    let add: (String, String, Bool) async -> String?

    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var notes = ""
    @State private var needsConnection = false
    @State private var isAdding = false
    @State private var failure: String?
    @FocusState private var isTitleFocused: Bool

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Title", text: $title)
                        .focused($isTitleFocused)
                        .submitLabel(.next)
                        .accessibilityIdentifier("new-task-title")
                } footer: {
                    Text(isTitleBlank ? "A task needs a title" : "\(TaskRules.characterCount(title)) / \(TaskRules.titleMaxLength)")
                }
                Section {
                    TextField("Notes", text: $notes, axis: .vertical)
                        .lineLimit(3...8)
                        .accessibilityIdentifier("new-task-notes")
                }
                Section {
                    Toggle("Needs a connection", isOn: $needsConnection)
                        .accessibilityIdentifier("needs-connection")
                } footer: {
                    Text(
                        needsConnection
                            ? "Sent straight to the server, never kept to send later: offline, it isn’t saved."
                            : "Saved on this device at once, and sent to the server now or when you’re back online."
                    )
                }
                if let failure {
                    Section {
                        Label(failure, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.red)
                            .accessibilityIdentifier("new-task-failure")
                    }
                }
            }
            .navigationTitle("New task")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if isAdding {
                        ProgressView()
                    } else {
                        Button("Add") { Task { await addTask() } }
                            .disabled(isTitleBlank)
                            .accessibilityIdentifier("add-task")
                    }
                }
            }
            .onChange(of: title) { _, typed in title = TaskRules.truncated(typed, to: TaskRules.titleMaxLength) }
            .onChange(of: notes) { _, typed in notes = TaskRules.truncated(typed, to: TaskRules.notesMaxLength) }
            .onAppear { isTitleFocused = true }
        }
        .presentationDetents([.medium, .large])
    }

    /// Whether the title is blank, which the server refuses.
    private var isTitleBlank: Bool {
        title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// Adds the task; closes the sheet once it is saved, or says why it was not.
    private func addTask() async {
        isAdding = true
        failure = await add(title, notes, needsConnection)
        isAdding = false
        if failure == nil { dismiss() }
    }
}

#Preview {
    Color.clear.sheet(isPresented: .constant(true)) {
        NewTaskSheet { _, _, _ in nil }
    }
}
