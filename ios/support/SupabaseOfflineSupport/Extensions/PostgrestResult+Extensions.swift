import Foundation
import SupabaseOffline

public extension PostgrestResult {
    
    /// Decodes the JSON `data` of the result into the specified type.
    /// - Returns: An instance of type `T`.
    /// - Throws: Throws if the data cannot be converted to UTF-8 or decoded.
    func decode<T: Decodable>(
        _ type: T.Type,
        dateDecodingStrategy: JSONDecoder.DateDecodingStrategy = .iso8601
    ) throws -> T {
        guard let data = self.data.data(using: .utf8) else {
            throw NSError(
                domain: "PostgrestResult",
                code: -1,
                userInfo: [NSLocalizedDescriptionKey: "Invalid UTF-8 response"]
            )
        }
        
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = dateDecodingStrategy
        return try decoder.decode(T.self, from: data)
    }
}
