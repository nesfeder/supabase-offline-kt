// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import SupabaseOfflineCore

/// Hands the app's connectivity signal, ``SupabaseOfflineClient/Config/networkAvailability``, to the
/// engine, which then watches no path monitor of its own.
enum NetworkAvailabilityRelay {

    /// Once the client opens, makes its stream with `makeReports` and reads it into the engine until
    /// the stream ends or the client closes, which finishes the stream. A stream that ends by itself
    /// is reported as reachable, so a signal that stops cannot hold the client offline.
    ///
    /// The engine's sync-status subscription ends when the client closes, by ``SupabaseOfflineClient/close()``
    /// or by a dropped client's background close, so watching it ends the relay without a hook in
    /// the close paths. The relay holds the connection, never the client, so it keeps no client alive.
    static func start(reading makeReports: @escaping @Sendable () -> AsyncStream<Bool>, into connection: CoreConnection) {
        Task.detached {
            guard let reference = try? await connection.openedClient() else { return }
            let reports = makeReports()
            await withTaskGroup(of: Void.self) { group in
                group.addTask {
                    for await isAvailable in reports {
                        reference.client.setNetworkAvailable(available: isAvailable)
                    }
                    if !Task.isCancelled {
                        reference.client.setNetworkAvailable(available: true)
                    }
                }
                group.addTask {
                    await waitForClosing(of: reference)
                }
                await group.next()
                group.cancelAll()
            }
        }
    }

    /// Returns once the client has closed, or once the task is canceled.
    private static func waitForClosing(of reference: CoreClientReference) async {
        let statuses: AsyncThrowingStream<Void, any Error> = subscriptionStream { _, finish in
            reference.client.observeSyncStatus(
                onStatus: { _ in },
                onCompletion: { failure in finish(failure.map(OfflineError.thrown(for:))) }
            )
        }
        do {
            for try await _ in statuses {}
        } catch OfflineError.clientClosed {
            // A client closed before the relay began: nothing is left to report to.
        } catch is CancellationError {
            // The app's stream ended first.
        } catch {
            packageLogger.warning("The network reports stopped: \(error.localizedDescription, privacy: .public)")
        }
    }
}
