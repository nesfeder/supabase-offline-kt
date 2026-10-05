// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import SwiftUI

/// A new task's title and notes. Add closes the sheet at once: the task is saved on the device
/// straight away and synced when the network allows.
struct NewTaskSheet: View {

    /// Adds the task with its title and notes.
    let add: (String, String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var notes = ""
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
                }
            }
            .navigationTitle("New task")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") {
                        add(title, notes)
                        dismiss()
                    }
                    .disabled(isTitleBlank)
                    .accessibilityIdentifier("add-task")
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
}
