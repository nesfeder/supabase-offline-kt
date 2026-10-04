// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline
import SupabaseOfflineTesting
import Testing

/// The testing product's factories build each value the client hands out, with every field as
/// given and the rest at their documented defaults. Written against the public API alone, as an
/// app's own tests would use them.
struct TestValuesTests {

    /// A time to stamp values with.
    private static let at = Date(timeIntervalSince1970: 1_791_115_200)

    @Test func aPendingMutationTakesEveryField() {
        // When
        let given = pendingMutation(
            id: 7,
            table: "todos",
            operation: .update,
            createdAt: Self.at,
            rowKeys: [["id": 1]],
            body: ["done": true]
        )
        let defaulted = pendingMutation(id: 8, table: "todos", operation: .delete, createdAt: Self.at)

        // Then
        #expect(given.id == 7)
        #expect(given.table == "todos")
        #expect(given.operation == .update)
        #expect(given.createdAt == Self.at)
        #expect(given.rowKeys == [["id": 1]])
        #expect(given.body == ["done": true])
        #expect(defaulted.rowKeys.isEmpty)
        #expect(defaulted.body == nil)
    }

    @Test func aRejectedMutationTakesEveryField() {
        // When
        let refusal = serverError(status: 409, code: "23505", message: "duplicate key")
        let given = rejectedMutation(
            id: 7,
            table: "todos",
            operation: .insert,
            createdAt: Self.at,
            rowKeys: [["id": 3]],
            body: [["id": 3]],
            serverError: refusal,
            message: "duplicate key",
            rejectedAt: Self.at.addingTimeInterval(60),
            isRetryable: false
        )
        let defaulted = rejectedMutation(
            id: 8,
            table: "todos",
            operation: .delete,
            createdAt: Self.at,
            message: "refused",
            rejectedAt: Self.at
        )

        // Then
        #expect(given.id == 7)
        #expect(given.table == "todos")
        #expect(given.operation == .insert)
        #expect(given.createdAt == Self.at)
        #expect(given.rowKeys == [["id": 3]])
        #expect(given.body == [["id": 3]])
        #expect(given.serverError == refusal)
        #expect(given.message == "duplicate key")
        #expect(given.rejectedAt == Self.at.addingTimeInterval(60))
        #expect(!given.isRetryable)
        #expect(defaulted.rowKeys.isEmpty)
        #expect(defaulted.body == nil)
        #expect(defaulted.serverError == nil)
        #expect(defaulted.isRetryable)
    }

    @Test func aServerErrorTakesEveryField() {
        // When
        let given = serverError(status: 403, code: "42501", message: "denied", details: "row-level security", hint: "check")
        let defaulted = serverError(status: 500, message: "failed")

        // Then
        #expect(given.status == 403)
        #expect(given.code == "42501")
        #expect(given.message == "denied")
        #expect(given.details == "row-level security")
        #expect(given.hint == "check")
        #expect(defaulted.code == nil)
        #expect(defaulted.details == nil)
        #expect(defaulted.hint == nil)
    }

    @Test func aSyncReportTakesEveryField() {
        // When
        let refused = rejectedMutation(id: 2, table: "todos", operation: .insert, createdAt: Self.at, message: "no", rejectedAt: Self.at)
        let given = syncReport(
            syncedCount: 3,
            rejected: [refused],
            remainingCount: 2,
            alreadyOnServerCount: 1,
            heldBehindOtherTablesCount: 1
        )
        let defaulted = syncReport(syncedCount: 0)

        // Then
        #expect(given.syncedCount == 3)
        #expect(given.rejected == [refused])
        #expect(given.remainingCount == 2)
        #expect(given.alreadyOnServerCount == 1)
        #expect(given.heldBehindOtherTablesCount == 1)
        #expect(defaulted.rejected.isEmpty)
        #expect(defaulted.remainingCount == 0)
        #expect(defaulted.alreadyOnServerCount == 0)
        #expect(defaulted.heldBehindOtherTablesCount == 0)
    }

    @Test func aSyncStatusTakesEveryFieldAndDefaultsToAClientThatHasDoneNothing() {
        // When
        let cause = syncFailedError(httpStatus: 503)
        let given = syncStatus(
            network: .offline,
            pendingMutationCount: 2,
            rejectedMutationCount: 1,
            isSyncing: true,
            lastSyncedAt: Self.at,
            lastSyncError: cause
        )
        let defaulted = syncStatus()

        // Then
        #expect(given.network == .offline)
        #expect(given.pendingMutationCount == 2)
        #expect(given.rejectedMutationCount == 1)
        #expect(given.isSyncing)
        #expect(given.lastSyncedAt == Self.at)
        #expect(given.lastSyncError?.httpStatus == 503)
        #expect(defaulted.network == .unknown)
        #expect(defaulted.pendingMutationCount == 0)
        #expect(defaulted.rejectedMutationCount == 0)
        #expect(!defaulted.isSyncing)
        #expect(defaulted.lastSyncedAt == nil)
        #expect(defaulted.lastSyncError == nil)
    }

    @Test func aQuerySnapshotTakesEveryField() {
        // When
        let stalled = RefreshState.stalled(cause: offlineError(code: .connectivity, message: "timed out"))
        let given = querySnapshot(result: ["Milk"], isFromCache: true, fetchedAt: Self.at, hasPendingMutations: true, refresh: stalled)
        let defaulted: QuerySnapshot<[String]> = querySnapshot(result: nil, isFromCache: false, fetchedAt: nil)

        // Then
        #expect(given.result == ["Milk"])
        #expect(given.isFromCache)
        #expect(given.fetchedAt == Self.at)
        #expect(given.hasPendingMutations)
        #expect(given.refresh == stalled)
        #expect(defaulted.result == nil)
        #expect(!defaulted.hasPendingMutations)
        #expect(defaulted.refresh == .idle)
    }

    @Test func anOfflineResponseTakesEveryFieldAndAnAppliedOutcomeWrapsIt() {
        // When
        let body = Data(#"[{"id":1}]"#.utf8)
        let given = offlineResponse(value: [1], data: body, count: 9, isFromCache: true, fetchedAt: Self.at, hasPendingMutations: true)
        let defaulted = offlineResponse(value: "done")
        let outcome = MutationOutcome.applied(result: given)

        // Then
        #expect(given.value == [1])
        #expect(given.data == body)
        #expect(given.count == 9)
        #expect(given.isFromCache)
        #expect(given.fetchedAt == Self.at)
        #expect(given.hasPendingMutations)
        #expect(defaulted.data.isEmpty)
        #expect(defaulted.count == nil)
        #expect(!defaulted.isFromCache)
        #expect(defaulted.fetchedAt == nil)
        #expect(!defaulted.hasPendingMutations)
        #expect(outcome.result?.value == [1])
        #expect(!outcome.isQueued)
    }

    @Test func aCacheMissErrorIsCaughtByItsCode() {
        // When
        let error = cacheMissError()

        // Then
        #expect(error.code == .cacheMiss)
        #expect(!error.isNetworkOrConnectivityFailure)
        #expect(cacheMissError(message: "nothing").message == "nothing")
        #expect(OfflineError.cacheMiss ~= error)
    }

    @Test func aMutationNotQueueableErrorIsANetworkFailure() {
        // When
        let error = mutationNotQueueableError()

        // Then
        #expect(error.code == .mutationNotQueueable)
        #expect(error.isNetworkOrConnectivityFailure)
        #expect(error.serverError == nil)
    }

    @Test func aNetworkDisabledErrorIsANetworkFailure() {
        // When
        let error = networkDisabledError(message: "off")

        // Then
        #expect(error.code == .networkDisabled)
        #expect(error.message == "off")
        #expect(error.isNetworkOrConnectivityFailure)
    }

    @Test func aSyncFailedErrorCarriesTheServersStatusAndCodeOrTheNetworksFailure() {
        // When
        let refused = syncFailedError(httpStatus: 429, code: "PGRST000")
        let unreachable = syncFailedError(isConnectivityFailure: true)

        // Then
        #expect(refused.code == .syncFailed)
        #expect(refused.httpStatus == 429)
        #expect(refused.serverError?.code == "PGRST000")
        #expect(!refused.isNetworkOrConnectivityFailure)
        #expect(unreachable.httpStatus == nil)
        #expect(unreachable.serverError == nil)
        #expect(unreachable.isNetworkOrConnectivityFailure)
    }

    @Test func anOfflineErrorTakesAnyCode() {
        // When
        let refusal = serverError(status: 409, code: "23505", message: "duplicate key")
        let server = offlineError(code: .server, message: "duplicate key", serverError: refusal)
        let unreachable = offlineError(code: .connectivity, message: "timed out")
        let closed = offlineError(code: .clientClosed, message: "closed")

        // Then
        #expect(server.code == .server)
        #expect(server.serverError == refusal)
        #expect(server.httpStatus == 409)
        #expect(!server.isNetworkOrConnectivityFailure)
        #expect(unreachable.isNetworkOrConnectivityFailure)
        #expect(closed.code == .clientClosed)
        #expect(!closed.isNetworkOrConnectivityFailure)
    }
}
