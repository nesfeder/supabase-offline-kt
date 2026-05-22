import Foundation
import SupabaseOffline

/// A Swift-friendly representation of the KMP `ReadStrategy`.
public enum SupabaseReadStrategy {
    /// Only fetch from the network. If the network is unavailable, throw an error.
    case networkOnly
    
    /// Try to fetch from the network first. If the network is unavailable,
    /// fall back to the local cache.
    /// - Parameter cacheDuration: Optional duration (in seconds) for cache freshness.
    case networkFirst(cacheDuration: TimeInterval?)
    
    /// Try to fetch from the local cache first. If the data is not in the cache,
    /// fetch from the network.
    /// - Parameter cacheDuration: Optional duration (in seconds) for cache freshness.
    case cacheFirst(cacheDuration: TimeInterval?)
    
    /// Only fetch from the local cache. If the data is not in the cache, throw an error.
    /// - Parameter cacheDuration: Optional duration (in seconds) for cache freshness.
    case cacheOnly(cacheDuration: TimeInterval?)

    // MARK: - Convenience Initializers
    /// Only fetch from the network. If the network is unavailable, throw an error.
    public static var networkFirst: SupabaseReadStrategy {
        return .networkFirst(cacheDuration: nil)
    }

    /// Try to fetch from the local cache first. If the data is not in the cache,
    /// fetch from the network.
    public static var cacheFirst: SupabaseReadStrategy {
        return .cacheFirst(cacheDuration: nil)
    }

    /// Only fetch from the local cache. If the data is not in the cache, throw an error.
    public static var cacheOnly: SupabaseReadStrategy {
        return .cacheOnly(cacheDuration: nil)
    }

    /// Converts the Swift enum to the KMP `ReadStrategy`.
    func toKMP() -> ReadStrategy {
        switch self {
        case .networkOnly:
            return ReadStrategyNetworkOnly.shared
        case .networkFirst(let duration):
            return ReadStrategyNetworkFirst(cacheDurationMillis: duration?.toKotlinMillis())
        case .cacheFirst(let duration):
            return ReadStrategyCacheFirst(cacheDurationMillis: duration?.toKotlinMillis())
        case .cacheOnly(let duration):
            return ReadStrategyCacheOnly(cacheDurationMillis: duration?.toKotlinMillis())
        }
    }
}
