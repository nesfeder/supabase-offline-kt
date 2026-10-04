// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import OSLog
import SupabaseOfflineCore
import SupabaseOfflineTesting
import Testing
@testable import SupabaseOffline

/// The core's log messages, in the unified log under the package's own names.
struct LogRelayTests {

    @Test(arguments: [
        ("Supabase-Offline", "Offline"),
        ("Supabase-Offline-Bridge", "Offline"),
        ("Supabase-Postgrest", "PostgREST"),
        ("Supabase-Storage", "Storage"),
        ("Supabase-Core", "Client"),
        ("Something-Else", "Client"),
    ])
    func eachTagHasACategoryOfThePackagesOwn(tag: String, category: String) {
        #expect(logCategory(forTag: tag) == category)
    }

    @Test func eachLevelHasItsUnifiedLogType() {
        #expect(logType(for: .debug) == .debug)
        #expect(logType(for: .info) == .info)
        #expect(logType(for: .warning) == .default)
        #expect(logType(for: .error) == .error)
        #expect(logType(for: .none) == nil)
    }

    @Test(arguments: [
        ("A bridge call failed unexpectedly (IllegalStateException)", "A core call failed unexpectedly (IllegalStateException)"),
        ("Refused (kotlinx.coroutines.JobCancellationException)", "Refused (JobCancellationException)"),
        ("io.github.jan.supabase.exceptions.HttpRequestException: offline", "HttpRequestException: offline"),
        ("Kotlin/Native runtime: KONAN", "core/Native runtime: core"),
        ("The bridges table was read", "The bridges table was read"),
    ])
    func messagesNameNoRuntime(message: String, redacted: String) {
        #expect(redactingRuntimeNames(message) == redacted)
    }

    @Test func linesReachTheUnifiedLogUnderThePackagesSubsystemWithNoRuntimeName() async throws {
        // Given a marked line through the relay, and a client whose failed read the core logs
        let started = Date()
        let marker = UUID().uuidString
        SystemLogRelay().log(
            level: .error,
            tag: "Supabase-Offline-Bridge",
            message: "A bridge call failed unexpectedly (kotlin.IllegalStateException) \(marker)"
        )
        let client = SupabaseOfflineClient.inMemory(options: .init(logLevel: .debug))
        _ = try? await client.rawSelect(readDescription(strategy: "networkOnly"))
        try await client.close()

        // When
        let store = try OSLogStore(scope: .currentProcessIdentifier)
        let deadline = ContinuousClock.now + .seconds(5)
        var lines = try packageLines(in: store, since: started)
        while !lines.contains(where: { $0.composedMessage.contains(marker) }), ContinuousClock.now < deadline {
            try await Task.sleep(for: .milliseconds(50))
            lines = try packageLines(in: store, since: started)
        }

        // Then
        let marked = lines.first { $0.composedMessage.contains(marker) }
        #expect(marked != nil)
        #expect(marked?.category == "Offline")
        #expect(marked?.level == .error)
        #expect(marked?.composedMessage == "A core call failed unexpectedly (IllegalStateException) \(marker)")
        for line in lines {
            let text = "\(line.category) \(line.composedMessage)".lowercased()
            #expect(!text.contains("kotlin") && !text.contains("bridge"), "\(line.composedMessage)")
        }
    }
}

/// Every line of the package's subsystem the store holds since `date`.
private func packageLines(in store: OSLogStore, since date: Date) throws -> [OSLogEntryLog] {
    try store.getEntries(
        at: store.position(date: date),
        matching: NSPredicate(format: "subsystem == %@", logSubsystem)
    )
    .compactMap { $0 as? OSLogEntryLog }
}
