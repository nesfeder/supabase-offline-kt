// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import PhotosUI
import SupabaseOffline
import SwiftUI

/// One task: title and notes saved as you type, the done switch, its photos, and delete.
struct TaskDetailView: View {

    @State private var model: TaskDetailModel
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var pickedPhoto: PhotosPickerItem?
    @State private var isConfirmingDelete = false
    @State private var sharedLinks: SharedItems?

    /// The screen for `model`'s task.
    init(model: TaskDetailModel) {
        _model = State(initialValue: model)
    }

    var body: some View {
        content
            .navigationTitle(model.lookup.task.map { Wording.title($0.title) } ?? "")
            .navigationBarTitleDisplayMode(.inline)
            .task { await model.observeTask() }
            .task { await model.observePendingTask() }
            .task(id: app.isSimulatingOffline) { await model.observePhotos(isSimulatingOffline: app.isSimulatingOffline) }
            .onDisappear { model.saveNow() }
    }

    /// The form, or the state that stands in for it.
    @ViewBuilder
    private var content: some View {
        switch model.lookup {
        case .unknown:
            ProgressView()
        case .missing:
            ContentUnavailableView {
                Label("This task is gone", systemImage: "trash")
            } description: {
                Text("It was deleted, here or on the server, or the server refused it.")
            }
        case .found(let task):
            form(task)
        }
    }

    /// Whether stored photos can be deleted or shared by link now: Storage calls need the network.
    private var canChangePhotos: Bool {
        app.repository.canReachNetwork && app.session.userID != nil
    }

    /// Whether photos can be added: offline too, as they wait on the device, but only for a user.
    private var canAddPhotos: Bool {
        app.session.userID != nil
    }

    /// The task's fields, done switch and photos, with Add photo and Delete below.
    private func form(_ task: TaskItem) -> some View {
        Form {
            Section {
                TextField("Title", text: Binding(get: { model.draftTitle }, set: { model.editTitle($0) }))
                    .accessibilityIdentifier("detail-title")
                TextField("Notes", text: Binding(get: { model.draftNotes }, set: { model.editNotes($0) }), axis: .vertical)
                    .lineLimit(2...10)
                    .accessibilityIdentifier("detail-notes")
            } footer: {
                if model.isTitleBlank {
                    Text("A task needs a title")
                } else {
                    Label(TaskSyncState.of(task, status: app.syncStatus).text, systemImage: syncSymbol(for: task))
                        .accessibilityIdentifier("detail-sync-state")
                }
            }
            Section {
                Toggle("Done", isOn: Binding(get: { task.isDone }, set: { model.setDone($0) }))
            } footer: {
                Text("Done tasks move to the end of the list")
            }
            Section {
                photoGrid
            } header: {
                Text("Photos")
            } footer: {
                Text(photosFooter)
            }
        }
        .toolbar {
            ToolbarItem(placement: .bottomBar) {
                PhotosPicker(selection: $pickedPhoto, matching: .images) {
                    Label("Add photo", systemImage: "photo.badge.plus")
                }
                .disabled(!canAddPhotos)
                .accessibilityIdentifier("add-photo")
            }
            ToolbarSpacer(.flexible, placement: .bottomBar)
            ToolbarItem(placement: .bottomBar) {
                Button("Delete task", systemImage: "trash", role: .destructive) { isConfirmingDelete = true }
            }
            ToolbarItem(placement: .primaryAction) {
                Menu("More", systemImage: "ellipsis") {
                    Button("Duplicate task", systemImage: "plus.square.on.square") { model.duplicate() }
                    Button("Share all photos of this task", systemImage: "link") {
                        Task { sharedLinks = await model.shareLinks().map(SharedItems.init) }
                    }
                    .disabled(!model.photos.contains { $0.pendingUpload == nil } || !canChangePhotos)
                }
            }
        }
        .sheet(item: $sharedLinks) { ShareSheet(items: $0.items) }
        .onChange(of: pickedPhoto) { _, item in
            guard let item else { return }
            model.attach(item)
            pickedPhoto = nil
        }
        .confirmationDialog("Delete this task?", isPresented: $isConfirmingDelete, titleVisibility: .visible) {
            Button("Delete", role: .destructive) {
                model.delete()
                dismiss()
            }
        } message: {
            Text(deleteMessage)
        }
    }

    /// The photos, oldest first, each waiting to upload marked, then a placeholder for each photo
    /// being prepared.
    @ViewBuilder
    private var photoGrid: some View {
        if model.photos.isEmpty, model.preparingCount == 0 {
            Text("No photos yet")
                .foregroundStyle(.secondary)
        } else {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 88), spacing: 8)], spacing: 8) {
                ForEach(model.photos) { photo in
                    NavigationLink(value: PhotoRoute(photo: photo)) {
                        PhotoThumbnail(
                            image: model.images[photo.id] ?? .loading,
                            pendingUpload: photo.pendingUpload,
                            canDelete: photo.pendingUpload != nil || canChangePhotos,
                            delete: { model.deletePhoto(photo) },
                            retry: { model.retryUpload(of: photo) }
                        )
                    }
                    .buttonStyle(.plain)
                    .task(id: PhotoLoadKey(photoID: photo.id, canReachNetwork: canChangePhotos)) {
                        await model.loadImage(for: photo)
                    }
                }
                ForEach(0..<model.preparingCount, id: \.self) { _ in
                    PreparingPlaceholder()
                }
            }
            .padding(.vertical, 4)
        }
    }

    /// What the Photos section says about where its photos come from.
    private var photosFooter: String {
        if app.isSimulatingOffline {
            return "Simulate offline is on: these are the photos saved on this device, read without asking the server. Photos you add wait here, and upload when it’s off."
        }
        return canChangePhotos
            ? "Photos upload as you add them. Tap one to open it."
            : "Photos you add wait on this device, and upload when you’re online. The ones already here still show."
    }

    /// What deleting does to the task's photos, as the delete confirmation says it.
    private var deleteMessage: String {
        let base = "It’s removed from this device now and from the server when it syncs."
        let stored = model.photos.filter { $0.pendingUpload == nil }
        let waiting = model.photos.count - stored.count
        let pending =
            switch waiting {
            case 0: ""
            case 1: " Its photo waiting to upload is deleted from this device."
            default: " Its \(waiting) photos waiting to upload are deleted from this device."
            }
        guard !stored.isEmpty else { return base + pending }
        guard app.repository.canReachNetwork else {
            return base + pending + " You’re offline, so its uploaded photo files stay on the server; delete it while online to remove them too."
        }
        return base + pending + " Its uploaded photos are deleted too."
    }

    /// The symbol beside the sync state.
    private func syncSymbol(for task: TaskItem) -> String {
        task.hasPendingMutations ? "icloud.and.arrow.up" : "checkmark.icloud"
    }
}

/// One photo, or what stands in for it, marked while it waits to upload.
struct PhotoThumbnail: View {

    /// The photo's image as it stands.
    let image: TaskDetailModel.PhotoImage

    /// Where its upload stands while it waits on this device; `nil` once it is stored.
    let pendingUpload: PendingPhoto.State?

    /// Whether Delete photo, or Remove, is on: deleting a stored photo needs the network.
    let canDelete: Bool

    /// Deletes the photo, or removes it from the device while it waits.
    let delete: () -> Void

    /// Tries a failed upload again.
    let retry: () -> Void

    var body: some View {
        Color(.secondarySystemFill)
            .aspectRatio(1, contentMode: .fit)
            .overlay { picture }
            .overlay(alignment: .bottom) {
                if let pendingUpload { PendingUploadMark(state: pendingUpload) }
            }
            .clipShape(.rect(cornerRadius: 12))
            .contextMenu { menu }
    }

    /// Retry and Remove while it waits to upload; Delete photo once it is stored.
    @ViewBuilder
    private var menu: some View {
        switch pendingUpload {
        case nil:
            Button("Delete photo", systemImage: "trash", role: .destructive, action: delete)
                .disabled(!canDelete)
        case .failed(_, let isRetryable)?:
            Button("Retry", systemImage: "arrow.clockwise", action: retry)
                .disabled(!isRetryable)
            Button("Remove", systemImage: "trash", role: .destructive, action: delete)
        case .waiting?:
            Button("Remove", systemImage: "trash", role: .destructive, action: delete)
        case .uploading?:
            EmptyView()
        }
    }

    /// The image, or a symbol and its words.
    @ViewBuilder
    private var picture: some View {
        switch image {
        case .loading:
            ProgressView()
        case .loaded(let uiImage):
            Image(uiImage: uiImage)
                .resizable()
                .scaledToFill()
                .accessibilityLabel("Task photo, available offline")
        case .notOnDevice:
            placeholder("icloud.and.arrow.down", "Not on this device yet; it loads when you’re online")
        case .missing:
            placeholder("questionmark.square.dashed", "This photo’s file is missing")
        case .unavailable:
            placeholder("exclamationmark.triangle", "This photo couldn’t be loaded")
        }
    }

    /// A symbol standing in for the photo, with its words for VoiceOver.
    private func placeholder(_ systemImage: String, _ description: String) -> some View {
        Image(systemName: systemImage)
            .font(.title2)
            .foregroundStyle(.secondary)
            .accessibilityLabel(description)
    }
}

/// A picked photo being read and re-encoded, before it is filed.
struct PreparingPlaceholder: View {

    var body: some View {
        Color(.secondarySystemFill)
            .aspectRatio(1, contentMode: .fit)
            .overlay { ProgressView().accessibilityLabel("Adding a photo") }
            .clipShape(.rect(cornerRadius: 12))
    }
}
