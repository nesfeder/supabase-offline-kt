// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import SupabaseOffline
import SwiftUI

/// Every photo the user has, newest first, in a grid that grows a page at a time. Each is marked
/// once it is on this device, which is when it shows offline too; tapping one opens it.
struct PhotosView: View {

    @Environment(AppModel.self) private var app

    var body: some View {
        content
            .navigationTitle("Photos")
            .task(id: PhotosKey(userID: app.session.userID, count: app.photos.shownCount)) { await app.photos.observe() }
            .navigationDestination(for: PhotoRoute.self) { route in
                PhotoViewer(model: PhotoViewerModel(photo: route.photo, repository: app.repository, failures: app.failureSink))
            }
    }

    /// The grid, or the state that stands in for it.
    @ViewBuilder
    private var content: some View {
        if app.session.userID == nil {
            ContentUnavailableView("Not signed in yet", systemImage: "person.crop.circle.badge.questionmark")
        } else if app.photos.rows == nil, app.photos.photos.isEmpty {
            ContentUnavailableView {
                Label("No photos saved on this device yet", systemImage: "photo.on.rectangle")
            } description: {
                Text("They load from the server when you’re online.")
            }
        } else if app.photos.photos.isEmpty {
            ContentUnavailableView {
                Label("No photos yet", systemImage: "photo.on.rectangle")
            } description: {
                Text("Add one from a task’s screen, or with New task.")
            }
        } else {
            grid
        }
    }

    /// The photos, then Show more while the server may have more.
    private var grid: some View {
        ScrollView {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 104), spacing: 4)], spacing: 4) {
                ForEach(app.photos.photos) { photo in
                    NavigationLink(value: PhotoRoute(photo: photo)) {
                        GridPhoto(
                            image: app.photos.images.image(of: photo),
                            isPending: photo.hasPendingMutations,
                            pendingUpload: photo.pendingUpload
                        )
                    }
                    .buttonStyle(.plain)
                    .task(id: PhotoLoadKey(photoID: photo.id, canReachNetwork: app.repository.canReachNetwork)) {
                        await app.photos.images.load(photo)
                    }
                }
            }
            if app.photos.mayHaveMore {
                Button("Show more") { app.photos.showMore() }
                    .padding()
            }
        }
    }
}

/// What the Photos grid depends on: the user and how many photos it shows.
private struct PhotosKey: Hashable {
    let userID: String?
    let count: Int
}

/// What a photo's loading depends on: the photo, and whether the server can be asked.
struct PhotoLoadKey: Hashable {
    let photoID: String
    let canReachNetwork: Bool
}

/// One photo of the grid, marked once it is on this device, or while it waits to upload.
struct GridPhoto: View {

    /// The photo's image as it stands.
    let image: TaskDetailModel.PhotoImage

    /// Whether its row waits in the queue.
    let isPending: Bool

    /// Where its upload stands while it waits on this device; `nil` once it is stored.
    var pendingUpload: PendingPhoto.State?

    var body: some View {
        Color(.secondarySystemFill)
            .aspectRatio(1, contentMode: .fit)
            .overlay { PhotoPicture(image: image) }
            .overlay(alignment: .bottom) {
                if let pendingUpload { PendingUploadMark(state: pendingUpload) }
            }
            .overlay(alignment: .bottomTrailing) {
                if case .loaded = image, pendingUpload == nil {
                    Image(systemName: isPending ? "icloud.and.arrow.up" : "checkmark.icloud")
                        .font(.caption)
                        .padding(4)
                        .background(.thinMaterial, in: .circle)
                        .padding(4)
                        .accessibilityLabel(isPending ? "Not synced yet" : "On this device")
                }
            }
            .clipShape(.rect(cornerRadius: 6))
    }
}

/// Where a photo picked on this device stands, on its thumbnail: a mark and a word, as a queued
/// task carries its cloud mark.
struct PendingUploadMark: View {

    /// Where its upload stands.
    let state: PendingPhoto.State

    var body: some View {
        Label {
            Text(Wording.uploadState(state))
        } icon: {
            if state == .uploading {
                ProgressView().controlSize(.mini)
            } else {
                Image(systemName: state == .waiting ? "icloud.and.arrow.up" : "exclamationmark.icloud")
            }
        }
        .font(.caption2)
        .labelStyle(.titleAndIcon)
        .lineLimit(2)
        .minimumScaleFactor(0.8)
        .foregroundStyle(state == .waiting || state == .uploading ? AnyShapeStyle(.primary) : AnyShapeStyle(.red))
        .padding(.horizontal, 6)
        .padding(.vertical, 3)
        .background(.thinMaterial, in: .capsule)
        .padding(4)
        .accessibilityIdentifier("pending-upload-mark")
    }
}

/// A photo's image, or a symbol standing in for it, with words for VoiceOver.
struct PhotoPicture: View {

    /// The image as it stands.
    let image: TaskDetailModel.PhotoImage

    /// How the image fills its space.
    var contentMode: ContentMode = .fill

    var body: some View {
        switch image {
        case .loading:
            ProgressView()
        case .loaded(let uiImage):
            Image(uiImage: uiImage)
                .resizable()
                .aspectRatio(contentMode: contentMode)
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
