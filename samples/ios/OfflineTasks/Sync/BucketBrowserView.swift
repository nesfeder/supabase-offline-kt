// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import SupabaseOffline
import SwiftUI

/// A folder of the user's in the bucket, as the navigation stack opens it.
struct BucketFolder: Hashable {

    /// Its path below the user's own folder: `""` for the user's folder, a task id for a task's.
    let path: String

    /// What the screen calls it.
    let title: String
}

/// Storage's own listing of a folder: entries filtered by name, sorted, a page at a time. Every
/// page is asked of the server; listings are never kept on the device.
@Observable
final class BucketBrowserModel {

    /// The folder listed.
    let folder: BucketFolder

    /// The entries read so far.
    private(set) var entries: [OfflineBucketApi.FileObject] = []

    /// The last page asked for.
    private(set) var page = BucketPage()

    /// Whether another page may follow.
    private(set) var mayHaveMore = false

    /// What went wrong with the last page, in words.
    private(set) var failure: String?

    /// Reads.
    @ObservationIgnored private let repository: TaskRepository

    /// A browser of `folder`.
    init(folder: BucketFolder, repository: TaskRepository) {
        self.folder = folder
        self.repository = repository
    }

    /// Lists the folder again from its first page, filtered by `search`, newest first or by name.
    func reload(search: String, newestFirst: Bool) async {
        page = BucketPage(search: search, newestFirst: newestFirst)
        entries = []
        await loadPage()
    }

    /// Adds the next page.
    func loadMore() async {
        page = page.next
        await loadPage()
    }

    /// Asks for ``page``.
    private func loadPage() async {
        do {
            let found = try await repository.bucketEntries(in: folder.path, page: page)
            entries += found
            mayHaveMore = found.count == page.limit
            failure = nil
        } catch is CancellationError {
            return
        } catch {
            failure = SampleFailure.message(for: error)
        }
    }
}

/// The bucket browser's screen.
struct BucketBrowserView: View {

    @Environment(AppModel.self) private var app
    @State private var model: BucketBrowserModel?
    @State private var search = ""
    @State private var newestFirst = false

    /// The folder listed.
    let folder: BucketFolder

    var body: some View {
        List {
            Section {
                Toggle("Newest first", isOn: $newestFirst)
            }
            if let model {
                Section {
                    ForEach(model.entries, id: \.name) { entry in row(entry) }
                    if model.mayHaveMore {
                        Button("Show more") { Task { await model.loadMore() } }
                    }
                } footer: {
                    Text(model.failure ?? (model.entries.isEmpty ? "Nothing here." : ""))
                }
            }
        }
        .navigationTitle(folder.title)
        .searchable(text: $search, prompt: "Names containing")
        .task(id: ListingKey(search: search, newestFirst: newestFirst)) {
            let model = model ?? BucketBrowserModel(folder: folder, repository: app.repository)
            self.model = model
            await model.reload(search: search, newestFirst: newestFirst)
        }
    }

    /// One entry: a folder opens; a file shows its size and when it changed.
    @ViewBuilder
    private func row(_ entry: OfflineBucketApi.FileObject) -> some View {
        if entry.id == nil {
            NavigationLink(value: BucketFolder(path: entry.name, title: folderTitle(entry.name))) {
                Label(folderTitle(entry.name), systemImage: "folder")
            }
        } else {
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.name).font(.subheadline.monospaced())
                Text(fileFacts(entry)).font(.footnote).foregroundStyle(.secondary)
            }
        }
    }

    /// A task folder by its task's title, when the device knows it.
    private func folderTitle(_ name: String) -> String {
        app.tasks.rows?.first { $0.id == name }.map { Wording.title($0.title) } ?? name
    }

    /// "48 KB · changed 10:42 · task 3f2a…".
    private func fileFacts(_ entry: OfflineBucketApi.FileObject) -> String {
        var facts: [String] = []
        if case .int(let size)? = entry.metadata?["size"] { facts.append(size.formatted(.byteCount(style: .file))) }
        if let updatedAt = entry.updatedAt ?? entry.createdAt {
            facts.append("changed \(updatedAt.formatted(date: .abbreviated, time: .shortened))")
        }
        if let lastAccessedAt = entry.lastAccessedAt {
            facts.append("read \(lastAccessedAt.formatted(date: .abbreviated, time: .shortened))")
        }
        return facts.joined(separator: " · ")
    }
}

/// What the listing depends on.
private struct ListingKey: Hashable {
    let search: String
    let newestFirst: Bool
}
