import Foundation
import SupabaseOffline

/// A Swift-friendly representation of the KMP `MutationStrategy`.
public enum SupabaseMutationStrategy {
    /// The mutation is only attempted over the network. If the network is
    /// unavailable or the request fails, an exception is thrown.
    case onlineOnly
    
    /// If the network is unavailable, the mutation is enqueued locally and
    /// will be synced later.
    case queueIfOffline
    
    /// Converts the Swift enum to the KMP `MutationStrategy`.
    func toKMP() -> MutationStrategy {
        switch self {
        case .queueIfOffline:
            return MutationStrategy.queueIfOffline
        case .onlineOnly:
            return MutationStrategy.onlineOnly
        }
    }
}
