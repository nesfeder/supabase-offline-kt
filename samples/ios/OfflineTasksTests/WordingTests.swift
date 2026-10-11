// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline
import SupabaseOfflineTesting
import Testing

@testable import OfflineTasks

/// What the app says about the library's values, each value built with the testing product's
/// factories rather than a client.
@Suite("The app's words for the library's values")
struct WordingTests {

    /// A fixed moment the tests measure ages from.
    private static let now = Date(timeIntervalSince1970: 1_760_000_000)

    // MARK: - The queue

    @Test("A waiting change is named by its operation, its table, its title and its age")
    func waitingChanges() {
        // Given
        let added = pendingMutation(
            id: 1,
            table: Tables.tasks,
            operation: .insert,
            createdAt: Self.now - 120,
            rowKeys: [["id": "3f2a"]],
            body: ["id": "3f2a", "title": "Buy milk"]
        )
        let deleted = pendingMutation(id: 2, table: Tables.tasks, operation: .delete, createdAt: Self.now - 5)
        let photo = pendingMutation(id: 3, table: Tables.taskPhotos, operation: .insert, createdAt: Self.now - 7_200)
        let list = pendingMutation(
            id: 4,
            table: Tables.tasks,
            operation: .upsert,
            createdAt: Self.now - 30,
            body: [["title": "One"], ["title": "Two"], ["title": "Three"]]
        )

        // Then
        #expect(Wording.waitingChange(added, now: Self.now) == "Added “Buy milk” · 2 min ago")
        #expect(Wording.waitingChange(deleted, now: Self.now) == "Deleted a task · just now")
        #expect(Wording.waitingChange(photo, now: Self.now) == "Added a photo · 2 h ago")
        #expect(Wording.waitingChange(list, now: Self.now) == "Saved “One” and 2 others · just now")
        #expect(Wording.change(.update, table: Tables.tasks, body: ["done": true]) == "Edited a task")
    }

    // --8<-- [start:factories]
    @Test("A refused change says when it was made and refused, and which row it was for")
    func refusedChanges() {
        // Given
        let refusal = rejectedMutation(
            id: 7,
            table: Tables.tasks,
            operation: .insert,
            createdAt: Self.now - 60,
            rowKeys: [["id": "3f2a91"]],
            body: ["title": "   "],
            serverError: serverError(status: 400, code: "23514", message: "violates check constraint \"tasks_title_length\""),
            message: "Refused",
            rejectedAt: Self.now,
            isRetryable: false
        )

        // Then
        let made = (Self.now - 60).formatted(date: .omitted, time: .shortened)
        let refused = Self.now.formatted(date: .omitted, time: .shortened)
        #expect(Wording.refusedChange(refusal) == "Made \(made) · refused \(refused) · row 3f2a…")
        #expect(refusal.reason == "The server refused it: a title can’t be blank or longer than 200 characters.")
        #expect(Wording.title(refusal.taskTitle) == "(no title)")
        #expect(Wording.rowKeys([]) == "no row key")
        #expect(Wording.rowKeys([["id": "a"], ["id": "b"]]) == "2 rows")
    }
    // --8<-- [end:factories]

    @Test("Sync now's report spells out every count")
    func syncReportLine() {
        // Given
        let refused = rejectedMutation(id: 1, table: Tables.tasks, operation: .insert, createdAt: Self.now, message: "No", rejectedAt: Self.now)
        let report = syncReport(syncedCount: 3, rejected: [refused], remainingCount: 2, alreadyOnServerCount: 1, heldBehindOtherTablesCount: 2)

        // Then
        #expect(
            Wording.syncReport(report)
                == "Sent 3 · refused 1 · still waiting 2 · already on the server 1 · held behind other tables 2"
        )
    }

    // MARK: - Where a list came from

    @Test("The caption says where the list came from: the server, this device, or changes waiting")
    func captions() {
        // Given
        let fresh = querySnapshot(result: [Int](), isFromCache: false, fetchedAt: Self.now - 10)
        let saved = offlineResponse(value: [Int](), isFromCache: true, fetchedAt: Self.now - 180)
        let changed = querySnapshot(result: [Int](), isFromCache: true, fetchedAt: Self.now - 600, hasPendingMutations: true)
        let never = querySnapshot(result: [Int](), isFromCache: true, fetchedAt: nil)

        // Then
        #expect(Wording.caption(ListProvenance(fresh), pendingCount: 0, now: Self.now) == "Updated just now")
        #expect(Wording.caption(ListProvenance(saved), pendingCount: 0, now: Self.now) == "From this device, 3 min ago")
        #expect(Wording.caption(ListProvenance(changed), pendingCount: 2, now: Self.now) == "2 changes waiting")
        #expect(Wording.caption(ListProvenance(never), pendingCount: 0, now: Self.now) == "Not fetched from the server yet")
    }

    @Test("A photo's copy on the device says when it was fetched and how large it is")
    func deviceCopy() {
        // Given
        let file = cachedFile(path: "/cache/a.jpg", fetchedAt: Self.now - 240, sizeBytes: 2_048)

        // Then
        #expect(Wording.deviceCopy(file, now: Self.now) == "Fetched 4 min ago · \(2_048.formatted(.byteCount(style: .file)))")
    }

    @Test("New task's result says the server's time, or that it waits on this device")
    func newTaskResults() {
        let storedAt = Self.now
        let time = storedAt.formatted(date: .omitted, time: .shortened)
        #expect(Wording.newTaskResult(.saved(storedAt: storedAt)) == "Saved — the server stored it at \(time)")
        #expect(Wording.newTaskResult(.savedOnDevice(pendingMutationID: 4)) == "Saved on this device — it syncs when the server can be reached")
        let queued = MutationOutcome<Void>.queued(pendingMutation: pendingMutation(id: 4, table: Tables.tasks, operation: .insert, createdAt: Self.now))
        #expect(Wording.outcome(queued, applied: "Added") == "Added on this device — it syncs when the server can be reached")
    }

    // MARK: - Failures

    @Test("Every code of the library's failures has a sentence of its own")
    func failureSentences() {
        // Given
        let codes: [OfflineError.Code] = [
            .networkDisabled, .connectivity, .cacheMiss, .mutationNotQueueable, .syncFailed, .server, .clientClosed,
            .accessToken, .invalidRequest, .decoding, .encoding, .configuration, .unexpected,
        ]

        // When
        let sentences = codes.map { SampleFailure.sentence(for: offlineError(code: $0, message: "the library's words")) }

        // Then: thirteen sentences, no two alike
        #expect(Set(sentences).count == codes.count)
        #expect(SampleFailure.sentence(for: networkDisabledError()).hasPrefix("Simulate offline is on"))
        #expect(SampleFailure.sentence(for: cacheMissError()).hasPrefix("Nothing for that is saved on this device"))
        #expect(
            SampleFailure.sentence(for: mutationNotQueueableError(message: "A grouped delete cannot be queued"))
                == "That change can’t wait in the queue, and the server can’t be reached, so nothing was changed. (A grouped delete cannot be queued)"
        )
    }

    @Test("A stopped sync says the server's status and code, or neither when the network stopped it")
    func syncFailures() {
        #expect(
            SampleFailure.sentence(for: syncFailedError(httpStatus: 503, code: "PGRST001"))
                == "The last sync stopped early: the server answered 503 · PGRST001. The rest waits, and is sent on the next try."
        )
        #expect(
            SampleFailure.sentence(for: syncFailedError(isConnectivityFailure: true))
                == "The last sync stopped early. The rest waits, and is sent on the next try."
        )
    }

    @Test("A server's refusal is said by its constraint, and a write's names its task")
    func refusals() {
        // Given
        let refusal = serverError(status: 409, code: "23503", message: "violates foreign key constraint")
        let error = offlineError(code: .server, message: "Refused", serverError: refusal)

        // Then
        #expect(SampleFailure.sentence(for: error) == "The server refused it: its task isn’t on the server.")
        #expect(
            SampleFailure.message(for: SampleFailure.write(error, taskTitle: "Mend the gate"))
                == "Couldn’t save “Mend the gate”. The server refused it: its task isn’t on the server."
        )
    }
}
