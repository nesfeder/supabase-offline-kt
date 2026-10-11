// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import PhotosUI
import SwiftUI

/// A new task's title, notes and photos, and how it is sent. Add waits for the write: queued, it
/// returns at once; sent only straight to the server (Needs a connection), it fails offline, and
/// the sheet says why and stays open. The photos are filed once the task is saved, to wait on the
/// device until they upload.
struct NewTaskSheet: View {

    /// Adds the task with its title, notes, Needs a connection and photos; returns what went
    /// wrong, or `nil` once it is saved.
    let add: (String, String, Bool, [EncodedPhoto]) async -> String?

    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var notes = ""
    @State private var needsConnection = false
    @State private var isAdding = false
    @State private var failure: String?
    @State private var pickedItems: [PhotosPickerItem] = []
    @State private var photos: [PickedPhoto] = []
    @State private var preparingCount = 0
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
                photoSection
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
                            .disabled(isTitleBlank || preparingCount > 0)
                            .accessibilityIdentifier("add-task")
                    }
                }
            }
            .onChange(of: title) { _, typed in title = TaskRules.truncated(typed, to: TaskRules.titleMaxLength) }
            .onChange(of: notes) { _, typed in notes = TaskRules.truncated(typed, to: TaskRules.notesMaxLength) }
            .onChange(of: pickedItems) { _, items in
                guard !items.isEmpty else { return }
                pickedItems = []
                Task { await prepare(items) }
            }
            .onAppear { isTitleFocused = true }
        }
        .presentationDetents([.medium, .large])
    }

    /// The photos picked so far, each removable, and Add photo.
    private var photoSection: some View {
        Section {
            if !photos.isEmpty || preparingCount > 0 {
                ScrollView(.horizontal) {
                    HStack(spacing: 8) {
                        ForEach(photos) { picked in
                            Image(uiImage: picked.thumbnail)
                                .resizable()
                                .scaledToFill()
                                .frame(width: 64, height: 64)
                                .clipShape(.rect(cornerRadius: 8))
                                .contextMenu {
                                    Button("Remove", systemImage: "trash", role: .destructive) {
                                        photos.removeAll { $0.id == picked.id }
                                    }
                                }
                                .accessibilityLabel("Photo to add")
                        }
                        ForEach(0..<preparingCount, id: \.self) { _ in
                            ProgressView()
                                .frame(width: 64, height: 64)
                                .background(Color(.secondarySystemFill), in: .rect(cornerRadius: 8))
                        }
                    }
                }
                .scrollIndicators(.hidden)
            }
            PhotosPicker(selection: $pickedItems, maxSelectionCount: 10, selectionBehavior: .ordered, matching: .images) {
                Label("Add photo", systemImage: "photo.badge.plus")
            }
            .accessibilityIdentifier("new-task-add-photo")
        } footer: {
            if !photos.isEmpty {
                Text("The photos wait on this device and upload once the task is saved: now, or when you’re back online.")
            }
        }
    }

    /// Whether the title is blank, which the server refuses.
    private var isTitleBlank: Bool {
        title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// Reads and re-encodes each picked image, in the order picked, for the sheet to show and add.
    private func prepare(_ items: [PhotosPickerItem]) async {
        preparingCount += items.count
        for item in items {
            defer { preparingCount -= 1 }
            do {
                guard let data = try await item.loadTransferable(type: Data.self) else { throw SampleFailure.photoUnreadable }
                photos.append(try await PickedPhoto(encoding: data))
            } catch {
                failure = SampleFailure.message(for: error)
            }
        }
    }

    /// Adds the task; closes the sheet once it is saved, or says why it was not.
    private func addTask() async {
        isAdding = true
        failure = await add(title, notes, needsConnection, photos.map(\.photo))
        isAdding = false
        if failure == nil { dismiss() }
    }
}

/// A photo picked in New task, re-encoded for the bucket, with a thumbnail for the sheet.
private struct PickedPhoto: Identifiable {

    /// Tells one picked photo from the next.
    let id = UUID()

    /// The JPEG the task's photo is filed with.
    let photo: EncodedPhoto

    /// What the sheet shows.
    let thumbnail: UIImage

    /// The photo in the image `data`, re-encoded off the main actor.
    init(encoding data: Data) async throws {
        photo = try await PhotoEncoder.encode(data)
        thumbnail = await Self.thumbnail(of: photo.jpeg) ?? UIImage()
    }

    /// A small copy of the image in `jpeg`, decoded off the main actor.
    @concurrent
    nonisolated private static func thumbnail(of jpeg: Data) async -> UIImage? {
        PhotoEncoder.downsampled(jpeg, maxPixelSize: 192).map(UIImage.init(cgImage:))
    }
}

#Preview {
    Color.clear.sheet(isPresented: .constant(true)) {
        NewTaskSheet { _, _, _, _ in nil }
    }
}
