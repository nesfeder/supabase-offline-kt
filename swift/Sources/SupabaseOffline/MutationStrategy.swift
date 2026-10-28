// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT

/// Whether a write may wait in the mutation queue when it cannot be sent now.
public enum MutationStrategy: Sendable, Hashable, CaseIterable {

    /// Saves the write to the mutation queue, sends it at once when nothing waits ahead of it, and
    /// otherwise leaves it for a later sync; cached reads show it at once. The default.
    case queueIfOffline

    /// Sends the write now and throws when it cannot: never queued, and never shown in the cache
    /// before the server applies it.
    case onlineOnly

    /// The strategy as a write description's `strategy` value.
    var descriptionValue: OfflineJSON {
        .string(coreName)
    }

    /// The strategy's name as the core reads it.
    var coreName: String {
        switch self {
        case .queueIfOffline: return "queueIfOffline"
        case .onlineOnly: return "onlineOnly"
        }
    }
}
