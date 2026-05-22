import Foundation
import SupabaseOffline

public extension ResumableClient {

    /// Create or continue a resumable upload using `Data`.
    /// - Parameters:
    ///   - data: The data to upload.
    ///   - source: The source identifier for the upload.
    ///   - path: The path where the data should be uploaded.
    ///   - options: A closure to configure upload options.
    func createOrContinueUpload(
        data: Data,
        source: String,
        path: String,
        options: @escaping (UploadOptions) -> Void = { _ in }
    ) async throws -> ResumableUpload {
        let kmpData = data.toKotlinByteArray()
        
        return try await self.createOrContinueUpload(
            data: kmpData,
            source: source,
            path: path,
            options: options
        )
    }
}
