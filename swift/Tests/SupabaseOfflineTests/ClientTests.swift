// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOfflineCore
import SupabaseOfflineTesting
import Testing
@testable import SupabaseOffline

/// Opening, configuration and closing, on the real core.
struct ClientTests {

    /// The project every test here names.
    private static let projectURL = URL(string: "https://project.supabase.co")!

    @Test func everySettingReachesTheCore() throws {
        // Given
        let directory = URL(filePath: "/tmp/offline-config", directoryHint: .isDirectory)
        let config = SupabaseOfflineClient.Config(
            defaultSchema: "app",
            accessToken: { "token" },
            dataDirectory: directory,
            cacheSize: .bytes(1_000),
            fileCacheSize: .unlimited,
            autoSync: false,
            primaryKeys: ["memberships": ["team_id", "user_id"]],
            logLevel: .debug
        )

        // When
        let core = try SupabaseOfflineClient.coreOptions(supabaseURL: Self.projectURL, supabaseKey: "key", config: config)

        // Then
        #expect(core.supabaseUrl == "https://project.supabase.co")
        #expect(core.supabaseKey == "key")
        #expect(core.dataDirectory == "/tmp/offline-config/")
        #expect(core.schema == "app")
        #expect(core.accessTokenProvider is AccessTokenRelay)
        #expect(core.cacheSizeBytes == 1_000)
        #expect(core.fileCacheSizeBytes == BridgeClientOptions.companion.CACHE_SIZE_UNLIMITED)
        #expect(core.autoSync == false)
        #expect(core.primaryKeys == ["memberships": ["team_id", "user_id"]])
        #expect(core.logLevel == .debug)
        #expect(core.logSink is SystemLogRelay)
    }

    @Test func theDefaultsAreTheCoresAndTheDataLivesInApplicationSupport() throws {
        // When
        let core = try SupabaseOfflineClient.coreOptions(supabaseURL: Self.projectURL, supabaseKey: "key", config: .init())

        // Then
        #expect(core.schema == "public")
        #expect(core.accessTokenProvider == nil)
        #expect(core.cacheSizeBytes == BridgeClientOptions.companion.DEFAULT_CACHE_SIZE_BYTES)
        #expect(core.fileCacheSizeBytes == BridgeClientOptions.companion.DEFAULT_FILE_CACHE_SIZE_BYTES)
        #expect(core.autoSync == true)
        #expect(core.primaryKeys.isEmpty)
        #expect(core.logLevel == .warning)
        #expect(core.dataDirectory.hasSuffix("/Library/Application Support/SupabaseOffline/"))
    }

    @Test func everyLogLevelHasTheCoresCounterpart() {
        let levels: [SupabaseOfflineClient.LogLevel: BridgeLogLevel] = [
            .debug: .debug, .info: .info, .warning: .warning, .error: .error, .off: .none,
        ]
        for (level, coreLevel) in levels {
            #expect(level.coreLevel == coreLevel)
        }
    }

    @MainActor
    @Test func opensOffTheCallingThreadAndACallWaitsForIt() async throws {
        // Given an open that takes 300 ms
        let openedOnMain = RecordingList<Bool>()
        let created = ContinuousClock.now

        // When
        let client = SupabaseOfflineClient(supabaseURL: Self.projectURL, supabaseKey: "key", config: .init()) {
            openedOnMain.append(isOnMainThread())
            Thread.sleep(forTimeInterval: 0.3)
            return BridgeTesting.shared.openInMemory(options: $0, server: FakeSilentServer(), clock: nil, realtime: nil)
        }
        let returnedAfter = ContinuousClock.now - created
        let count = try await client.rawPendingMutationCount()

        // Then
        #expect(returnedAfter < .milliseconds(100))
        #expect(ContinuousClock.now - created >= .milliseconds(300))
        #expect(count == 0)
        #expect(openedOnMain.values == [false])
        try await client.close()
    }

    @MainActor
    @Test func aCoreAnswerArrivesOffTheMainThreadAndResumesOnTheCaller() async throws {
        // Given
        let client = SupabaseOfflineClient.inMemory()
        let answeredOnMain = RecordingList<Bool>()

        // When
        let count: Int = try await client.connection.call { core, complete in
            core.pendingMutationCount(table: nil) { count, failure in
                answeredOnMain.append(isOnMainThread())
                complete(coreResult(count, failure) { try requiredValue($0).intValue })
            }
        }

        // Then
        #expect(count == 0)
        #expect(answeredOnMain.values == [false])
        MainActor.assertIsolated()
        try await client.close()
    }

    @Test func aClientThatCannotOpenThrowsConfigurationFromEveryCall() async {
        // Given
        let client = SupabaseOfflineClient(
            supabaseURL: Self.projectURL,
            supabaseKey: "key",
            config: .init(dataDirectory: URL(string: "https://not-a-directory.example")!)
        )

        // When
        let error = await #expect(throws: OfflineError.self) { try await client.rawPendingMutationCount() }

        // Then
        #expect(error?.code == .configuration)
        await #expect(throws: Never.self) { try await client.close() }
    }

    @Test func aSecondClientOverTheSameDirectoryCannotOpenUntilTheFirstCloses() async throws {
        // Given a client open over a directory of its own
        let directory = FileManager.default.temporaryDirectory.appending(path: "offline-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: directory) }
        let config = SupabaseOfflineClient.Config(dataDirectory: directory)
        let first = SupabaseOfflineClient(supabaseURL: Self.projectURL, supabaseKey: "key", config: config)
        #expect(try await first.rawPendingMutationCount() == 0)

        // When
        let second = SupabaseOfflineClient(supabaseURL: Self.projectURL, supabaseKey: "key", config: config)
        let error = await #expect(throws: OfflineError.self) { try await second.rawPendingMutationCount() }
        try await first.close()
        let third = SupabaseOfflineClient(supabaseURL: Self.projectURL, supabaseKey: "key", config: config)

        // Then
        #expect(error?.code == .configuration)
        #expect(try await third.rawPendingMutationCount() == 0)
        try await third.close()
    }

    @MainActor
    @Test func closingTwiceDoesNothingMore() async throws {
        // Given
        let client = SupabaseOfflineClient.inMemory()
        #expect(try await client.rawPendingMutationCount() == 0)

        // When
        try await client.close()
        try await client.close()

        // Then
        let error = await #expect(throws: OfflineError.self) { try await client.rawPendingMutationCount() }
        #expect(error?.code == .clientClosed)
    }

    @Test func aLiveSequenceFinishesWhenTheClientCloses() async throws {
        // Given
        let client = SupabaseOfflineClient.inMemory()
        let received = RecordingList<Int>()
        let reading = Task {
            for try await count in client.pendingMutationCounts() { received.append(count) }
        }
        #expect(await eventually { received.values == [0] })

        // When
        try await client.close()

        // Then
        await #expect(throws: Never.self) { try await reading.value }
    }

    @Test func anIteratorKeepsItsClientOpenAndLeavingItLetsTheClientGo() async throws {
        // Given an iterator whose client nothing else holds
        var client: SupabaseOfflineClient? = SupabaseOfflineClient.inMemory()
        let reference = WeakReference(try #require(client))
        let ends = RecordingList<String>()
        var iterator: SubscriptionSequence<Int>.AsyncIterator? =
            client?.pendingMutationCounts(recordingEndsIn: ends).makeAsyncIterator()
        client = nil

        // When
        let first = try await iterator?.next()
        try await Task.sleep(for: .milliseconds(100))

        // Then the subscription runs on, and the client is open
        #expect(first == 0)
        #expect(reference.value != nil)
        #expect(ends.values.isEmpty)

        // When the iterator is let go
        iterator = nil

        // Then the subscription ends (canceled, or by the freed client's close) and the client is freed
        #expect(await eventually { ends.values.count == 1 })
        #expect(["CANCELLED", "nil"].contains(ends.values.first ?? ""))
        #expect(await eventually { reference.value == nil })
    }

    @Test func leavingALoopCancelsTheCoresSubscription() async throws {
        // Given
        let client = SupabaseOfflineClient.inMemory()
        let ends = RecordingList<String>()

        // When
        for try await count in client.pendingMutationCounts(recordingEndsIn: ends) {
            #expect(count == 0)
            break
        }

        // Then
        #expect(await eventually { ends.values == ["CANCELLED"] })
        try await client.close()
    }
}
