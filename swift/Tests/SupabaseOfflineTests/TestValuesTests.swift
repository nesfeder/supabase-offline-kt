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
        #expect(given.stopReason.kind == .heldBehindOtherTables)
        #expect(defaulted.stopReason.kind == .completed)
    }

    @Test func aSyncReportTakesTheStopReasonItIsGiven() {
        // Given
        let failure = syncFailedError(message: "stopped", httpStatus: 503, code: "PGRST000")

        // When
        let reason = syncStopReason(kind: .serverFailure, error: failure)
        let given = syncReport(remainingCount: 2, stopReason: reason)
        let disabled = syncReport(stopReason: syncStopReason(kind: .networkDisabled))

        // Then
        #expect(given.stopReason == reason)
        #expect(given.stopReason.kind == .serverFailure)
        #expect(given.stopReason.error?.httpStatus == 503)
        #expect(given.stopReason.error?.serverError?.code == "PGRST000")
        #expect(given.stopReason.error?.message == "stopped")
        #expect(given.remainingCount == 2)
        #expect(given.syncedCount == 0)
        #expect(disabled.stopReason.kind == .networkDisabled)
        #expect(disabled.stopReason.error == nil)
        #expect(syncStopReason(kind: .localFailure, error: failure) != reason)
        #expect(SyncStopReason.Kind(rawValue: "serverFailure") == .serverFailure)
        #expect(SyncStopReason.Kind.sessionNotReady.description == "sessionNotReady")
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

    @Test func aCachedResponseReadsAsTheCachesAtTheTimeGiven() {
        // When
        let body = Data(#"[{"id":1}]"#.utf8)
        let given = cachedResponse(value: [1], data: body, count: 9, fetchedAt: Self.at, hasPendingMutations: true)
        let defaulted = cachedResponse(value: "done", fetchedAt: Self.at)

        // Then
        #expect(given.value == [1])
        #expect(given.data == body)
        #expect(given.count == 9)
        #expect(given.isFromCache)
        #expect(given.fetchedAt == Self.at)
        #expect(given.hasPendingMutations)
        #expect(defaulted.isFromCache)
        #expect(defaulted.fetchedAt == Self.at)
        #expect(defaulted.data.isEmpty)
        #expect(defaulted.count == nil)
        #expect(!defaulted.hasPendingMutations)
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

    @Test func aCachedFileTakesEveryField() {
        // When
        let given = cachedFile(path: "/tmp/photos/a.jpg", fetchedAt: Self.at, sizeBytes: 2_048)

        // Then
        #expect(given.path == "/tmp/photos/a.jpg")
        #expect(given.fetchedAt == Self.at)
        #expect(given.sizeBytes == 2_048)
        #expect(given.url == URL(filePath: "/tmp/photos/a.jpg"))
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

    @Test func aMutationConflictTakesEveryField() {
        // When
        let given = mutationConflict(
            mutationId: 7,
            table: "todos",
            operation: .update,
            rowKey: ["id": "1"],
            base: ["title": "Milk", "updated_at": "v1"],
            local: ["title": "Mine"],
            server: ["id": 1, "title": "Theirs", "updated_at": "v2"],
            changedOnServer: ["title"],
            conflictingColumns: ["title"]
        )
        let defaulted = mutationConflict(mutationId: 8, table: "todos", operation: .delete, rowKey: ["id": "2"])

        // Then
        #expect(given.mutationId == 7)
        #expect(given.table == "todos")
        #expect(given.operation == .update)
        #expect(given.rowKey == ["id": "1"])
        #expect(given.base == ["title": "Milk", "updated_at": "v1"])
        #expect(given.local == ["title": "Mine"])
        #expect(given.server == ["id": 1, "title": "Theirs", "updated_at": "v2"])
        #expect(given.changedOnServer == ["title"])
        #expect(given.conflictingColumns == ["title"])
        #expect(defaulted.base.isEmpty)
        #expect(defaulted.local == nil)
        #expect(defaulted.server == nil)
        #expect(defaulted.changedOnServer.isEmpty)
        #expect(defaulted.conflictingColumns.isEmpty)
    }

    @Test func aConflictedMutationTakesEveryField() {
        // When
        let given = conflictedMutation(
            id: 7,
            table: "todos",
            operation: .update,
            createdAt: Self.at,
            rowKeys: [["id": "1"]],
            body: ["title": "Mine"],
            base: ["title": "Milk"],
            server: ["title": "Theirs"],
            serverVersion: "v2",
            changedOnServer: ["title"],
            conflictingColumns: ["title"],
            message: "moved",
            conflictedAt: Self.at.addingTimeInterval(60)
        )
        let defaulted = conflictedMutation(
            id: 8,
            table: "todos",
            operation: .delete,
            createdAt: Self.at,
            message: "moved",
            conflictedAt: Self.at
        )

        // Then
        #expect(given.id == 7)
        #expect(given.table == "todos")
        #expect(given.operation == .update)
        #expect(given.createdAt == Self.at)
        #expect(given.rowKeys == [["id": "1"]])
        #expect(given.body == ["title": "Mine"])
        #expect(given.base == ["title": "Milk"])
        #expect(given.server == ["title": "Theirs"])
        #expect(given.serverVersion == "v2")
        #expect(given.changedOnServer == ["title"])
        #expect(given.conflictingColumns == ["title"])
        #expect(given.message == "moved")
        #expect(given.conflictedAt == Self.at.addingTimeInterval(60))
        #expect(defaulted.rowKeys.isEmpty)
        #expect(defaulted.body == nil)
        #expect(defaulted.base.isEmpty)
        #expect(defaulted.server == nil)
        #expect(defaulted.serverVersion == nil)
        #expect(defaulted.changedOnServer.isEmpty)
        #expect(defaulted.conflictingColumns.isEmpty)
    }

    @Test func theOverloadsTakeTheAppendedFields() {
        // When
        let pending = pendingMutation(id: 7, table: "todos", operation: .update, createdAt: Self.at, baseVersion: "v1")
        let parked = conflictedMutation(id: 8, table: "todos", operation: .update, createdAt: Self.at, message: "moved", conflictedAt: Self.at)
        let report = syncReport(syncedCount: 2, conflictsResolvedCount: 1, conflictsKeptServerCount: 3, conflicted: [parked])
        let plainReport = syncReport(syncedCount: 2)
        let status = syncStatus(pendingMutationCount: 1, conflictedMutationCount: 4)
        let plainStatus = syncStatus()

        // Then
        #expect(pending.baseVersion == "v1")
        #expect(pendingMutation(id: 7, table: "todos", operation: .update, createdAt: Self.at).baseVersion == nil)
        #expect(report.conflictsResolvedCount == 1)
        #expect(report.conflictsKeptServerCount == 3)
        #expect(report.conflicted == [parked])
        #expect(plainReport.conflictsResolvedCount == 0)
        #expect(plainReport.conflicted.isEmpty)
        #expect(status.conflictedMutationCount == 4)
        #expect(status.pendingMutationCount == 1)
        #expect(plainStatus.conflictedMutationCount == 0)
        #expect(status != syncStatus(pendingMutationCount: 1))
    }
}
