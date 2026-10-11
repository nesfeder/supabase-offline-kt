// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline

/// Uploads the photos picked on this device, one at a time in the order they were picked, while
/// the library's sync status says the network can be reached: neither offline nor switched off, so
/// Simulate offline pauses it, as it pauses the mutation queue. A failed upload is marked with the
/// app's sentence for it and tried again as the network comes back, or on Retry; one the server
/// refused for good waits for Remove.
final class PendingPhotoUploader {

    /// Uploads, and the queue they come from.
    private let repository: TaskRepository

    /// Where a failure that belongs to no photo goes to be shown.
    private let failures: FailureSink

    /// An uploader through `repository`, reporting to `failures`.
    init(repository: TaskRepository, failures: FailureSink) {
        self.repository = repository
        self.failures = failures
    }

    /// The queue of photos waiting.
    private var pendingPhotos: PendingPhotoQueue {
        repository.pendingPhotos
    }

    /// Reads the photos an earlier launch left, then uploads whatever waits whenever the network
    /// is reachable, until the calling task is canceled.
    func run() async {
        do {
            try pendingPhotos.load()
        } catch {
            failures.report(error)
        }
        let (passes, requestPass) = AsyncStream.makeStream(of: Void.self, bufferingPolicy: .bufferingNewest(1))
        await withDiscardingTaskGroup { group in
            group.addTask { await self.followNetwork(requesting: requestPass) }
            group.addTask { await self.followWaitingPhotos(requesting: requestPass) }
            group.addTask {
                for await _ in passes { await self.uploadWaitingPhotos() }
            }
        }
    }

    /// Asks for a pass each time the network becomes reachable, after making the failed photos
    /// that another attempt can help wait again.
    private func followNetwork(requesting requestPass: AsyncStream<Void>.Continuation) async {
        var wasReachable = false
        do {
            for try await status in repository.syncStatus {
                let isReachable = status.network != .offline && status.network != .disabled
                if isReachable, !wasReachable {
                    do {
                        try pendingPhotos.markRetryableFailuresWaiting()
                    } catch {
                        failures.report(error)
                    }
                    requestPass.yield()
                }
                wasReachable = isReachable
            }
        } catch {
            // The status ends only when the client closes; there is nothing to upload through then.
        }
    }

    /// Asks for a pass each time a photo starts waiting (filed, retried, or let go when Start over
    /// ends), but not for the photos that were already waiting when a pass ended for want of the
    /// network.
    private func followWaitingPhotos(requesting requestPass: AsyncStream<Void>.Continuation) async {
        var known: Set<String> = []
        for await ids in Observations({ Set(self.pendingPhotos.idsReadyToUpload) }) {
            if !ids.subtracting(known).isEmpty { requestPass.yield() }
            known = ids
        }
    }

    /// Uploads the waiting photos of the signed-in user, oldest picked first, while the network
    /// can be reached and uploads are not held. A failure for want of the network ends the pass,
    /// and the photos after it keep waiting.
    private func uploadWaitingPhotos() async {
        while repository.canReachNetwork, let userID = repository.session.userID {
            let next: PendingPhoto?
            do {
                next = try pendingPhotos.startNextUpload(of: userID)
            } catch {
                failures.report(error)
                return
            }
            guard let next else { return }
            do {
                try await repository.uploadPendingPhoto(next)
            } catch is CancellationError {
                try? pendingPhotos.markWaiting(next)
                return
            } catch {
                do {
                    try pendingPhotos.markFailed(next, reason: SampleFailure.uploadMessage(for: error), isRetryable: SampleFailure.isRetryable(error))
                } catch {
                    failures.report(error)
                }
                if error as? SampleFailure == .needsNetwork { return }
            }
        }
    }
}
