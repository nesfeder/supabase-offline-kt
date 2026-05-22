import os
import SwiftUI
import ComposableArchitecture
import SupabaseOffline
import SupabaseOfflineSupport

private let sdkLogger = Logger(subsystem: "io.github.nesfeder.supabase.demo", category: "SDK")

struct ContentView: View {

    private let store: StoreOf<TasksFeature>
    @StateObject private var connectivity = IOSConnectivityObserver()

    init() {
        let offlineClient = SupabaseFactory.offlineClient(
            supabaseUrl: AppSecrets.supabaseURL,
            supabaseAnonKey: AppSecrets.supabaseAnonKey,
            logger: { level, message in
                switch level {
                case .debug:   sdkLogger.debug("\(message)")
                case .info:    sdkLogger.info("\(message)")
                case .warning: sdkLogger.warning("\(message)")
                case .error:   sdkLogger.error("\(message)")
                }
            },
            minLogLevel: .debug
        )

        self.store = Store(
            initialState: TasksFeature.State()
        ) {
            TasksFeature()
        } withDependencies: {
            $0.supabaseOfflineClient = offlineClient
        }
    }

    var body: some View {
        TasksView(store: store)
            .onChange(of: connectivity.isOnline) { _, isOnline in
                store.send(.onlineStatusChanged(isOnline))
            }
    }
}

#Preview {
    ContentView()
}
