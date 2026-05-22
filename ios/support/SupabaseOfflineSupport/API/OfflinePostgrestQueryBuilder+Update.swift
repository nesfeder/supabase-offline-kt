import Foundation
import SupabaseOffline

public extension OfflinePostgrestQueryBuilder {

    /// Updates rows in the table with a JSON string object. Provide the row via the `filter` closure.
    /// - Parameters:
    ///   - jsonObject: The JSON string object representing the data to update.
    ///   - returningColumns: The columns to return after the update.
    ///   - count: The count option for the update operation.
    ///   - mutationStrategy: The strategy for the mutation operation (queueIfOffline, onlineOnly).
    ///   - filter: A closure to apply filters to the update operation.
    /// - Returns: A `PostgrestResult` containing the result of the update operation
    func updateJsonString(
        jsonObject: String,
        returningColumns: String? = nil,
        count: PostgrestCount? = nil,
        mutationStrategy: SupabaseMutationStrategy = .queueIfOffline,
        filter: ((PostgrestFilter) -> Void)? = nil
    ) async throws -> PostgrestResult {
        try await self.updateJsonStringKt(
            jsonObject: jsonObject,
            returningColumns: returningColumns,
            count: count,
            mutationStrategy: mutationStrategy.toKMP(),
            filter: filter
        )
    }

    /// Updates rows in the table with an encodable value. Provide the row via the `filter` closure.
    /// - Parameters:
    ///   - value: The encodable value representing the data to update.
    ///   - returningColumns: The columns to return after the update.
    ///   - count: The count option for the update operation.
    ///   - mutationStrategy: The strategy for the mutation operation (queueIfOffline, onlineOnly).
    ///   - filter: A closure to apply filters to the update operation.
    /// - Returns: A `PostgrestResult` containing the result of the update operation
    func updateOne<T: Encodable>(
        value: T,
        returningColumns: String? = nil,
        count: PostgrestCount? = nil,
        mutationStrategy: SupabaseMutationStrategy = .queueIfOffline,
        filter: ((PostgrestFilter) -> Void)? = nil
    ) async throws -> PostgrestResult {
        let jsonObject = try encodeToJsonObject(value: value)
        return try await self.updateJsonString(
            jsonObject: jsonObject,
            returningColumns: returningColumns,
            count: count,
            mutationStrategy: mutationStrategy,
            filter: filter
        )
    }
}
