// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import SupabaseOfflineCore

/// Hands the app's connectivity signal, ``SupabaseOfflineClient/Config/networkAvailability``, to the
/// core, which then watches no path monitor of its own.
enum NetworkAvailabilityRelay {

    /// Reads `reports` into the core once it opens, until the stream ends or the core closes.
    ///
    /// The core's sync-status subscription ends when the core closes, by ``SupabaseOfflineClient/close()``
    /// or by a dropped client's background close, so watching it ends the relay without a hook in
    /// the close paths. The relay holds the connection, never the client, so it keeps no client alive.
    static func start(reading reports: AsyncStream<Bool>, into connection: CoreConnection) {
        Task.detached {
            guard let reference = try? await connection.openedClient() else { return }
            await withTaskGroup(of: Void.self) { group in
                group.addTask {
                    for await isAvailable in reports {
                        reference.client.setNetworkAvailable(available: isAvailable)
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

    /// Returns once the core has closed, or once the task is cancelled.
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
