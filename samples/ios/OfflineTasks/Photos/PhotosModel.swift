// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline

/// The Photos screen: every photo the user has, newest first, a page at a time, each marked once
/// it is on this device.
@Observable
final class PhotosModel {

    /// How many photos a page holds.
    static let pageSize = 30

    /// The photo rows read so far; `nil` while nothing is known.
    private(set) var rows: [TaskPhotoRow]?

    /// The ids of the photos whose row waits in the queue.
    private(set) var pendingIDs: Set<String> = []

    /// How many photos the grid asks for: a page more each time it reaches the end.
    private(set) var shownCount = pageSize

    /// The photos' images.
    let images: PhotoImages

    /// Reads.
    @ObservationIgnored private let repository: TaskRepository

    /// Where a failure goes to be shown.
    @ObservationIgnored private let failures: FailureSink

    /// A model over `repository`, reporting failures to `failures`.
    init(repository: TaskRepository, failures: FailureSink) {
        self.repository = repository
        self.failures = failures
        images = PhotoImages(repository: repository, maxPixelSize: 360)
    }

    /// The photos read so far, newest first.
    var photos: [TaskPhoto] {
        (rows ?? []).map { $0.photo(pendingIDs: pendingIDs) }
    }

    /// Whether the server may have more than the grid shows.
    var mayHaveMore: Bool {
        (rows?.count ?? 0) >= shownCount
    }

    /// Follows the newest ``shownCount`` photos and the queue's photo writes; the view starts it
    /// again for each user and each page.
    func observe() async {
        guard repository.session.userID != nil else {
            rows = nil
            return
        }
        await withDiscardingTaskGroup { group in
            group.addTask { await self.observeRows() }
            group.addTask { await self.observePending() }
        }
    }

    /// Asks for a page more.
    func showMore() {
        shownCount += Self.pageSize
    }

    /// Follows the newest ``shownCount`` photo rows.
    private func observeRows() async {
        do {
            for try await snapshot in repository.photoPage(count: shownCount) {
                rows = snapshot.result
            }
        } catch is CancellationError {
            return
        } catch {
            failures.report(error)
        }
    }

    /// Follows which photo rows wait in the queue.
    private func observePending() async {
        do {
            for try await mutations in repository.pendingMutations(table: Tables.taskPhotos) {
                pendingIDs = Set(mutations.flatMap(\.rowIDs))
            }
        } catch is CancellationError {
            return
        } catch {
            failures.report(error)
        }
    }
}
