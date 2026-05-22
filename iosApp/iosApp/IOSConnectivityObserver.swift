import Foundation
import Network
import Combine

/// Simple NWPathMonitor wrapper that exposes `isOnline` as @Published.
/// Mirrors the Android ConnectivityObserver pattern.
final class IOSConnectivityObserver: ObservableObject {

    @Published var isOnline: Bool = true

    private let monitor: NWPathMonitor
    private let queue = DispatchQueue(label: "SupabaseOffline.IOSConnectivityObserver")

    init() {
        monitor = NWPathMonitor()

        monitor.pathUpdateHandler = { [weak self] path in
            DispatchQueue.main.async {
                self?.isOnline = (path.status == .satisfied)
            }
        }

        monitor.start(queue: queue)
    }

    deinit {
        monitor.cancel()
    }
}