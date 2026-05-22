import SupabaseOffline

final class AsyncToKotlinSuspendString: KotlinSuspendFunction0 {
    
    private let block: () async throws -> String?
    
    init(block: @escaping () async throws -> String?) {
        self.block = block
    }
    
    func __invoke(completionHandler: @escaping @Sendable (Any?, (any Error)?) -> Void) {
        Task {
            do {
                let result = try await block()
                completionHandler(result, nil)
            } catch {
                completionHandler(nil, error)
            }
        }
    }
}
