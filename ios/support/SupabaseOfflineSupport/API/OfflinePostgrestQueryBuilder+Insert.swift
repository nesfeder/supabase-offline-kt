import Foundation
import SupabaseOffline

public extension OfflinePostgrestQueryBuilder {

    /// Perform an insert using an array of JSON-encoded objects.
    /// - Parameters:
    ///   - jsonArray : A JSON-encoded array of objects to insert.
    ///   - returningColumns: A comma-separated string of columns to return, or "*" to return all columns.
    ///   - count: The count option for the insert operation.
    ///   - defaultToNull: If true, missing fields in the JSON objects will be set to null.
    ///   - mutationStrategy: The strategy for the mutation operation (queueIfOffline, onlineOnly).
    /// - Returns: A `PostgrestResult` containing the result of the insert operation.
    func insertJsonString(
        jsonArray: String,
        returningColumns: String? = nil,
        count: PostgrestCount? = nil,
        defaultToNull: Bool? = nil,
        mutationStrategy: SupabaseMutationStrategy = .queueIfOffline
    ) async throws -> PostgrestResult {
        try await self.insertJsonStringKt(
            jsonArray: jsonArray,
            returningColumns: returningColumns,
            count: count,
            defaultToNull: defaultToNull.map(KotlinBoolean.init(bool:)),
            mutationStrategy: mutationStrategy.toKMP()
        )
    }

    /// Perform an insert using an array of `Encodable` objects.
    /// - Parameters:
    ///   - values: An array of `Encodable` objects to insert.
    ///   - returningColumns: A comma-separated string of columns to return, or "*" to return all columns.
    ///   - count: The count option for the insert operation.
    ///   - defaultToNull: If true, missing fields in the JSON objects will be set to null.
    ///   - mutationStrategy: The strategy for the mutation operation (queueIfOffline, onlineOnly).
    /// - Returns: A `PostgrestResult` containing the result of the insert operation.
    func insertMany<T: Encodable>(
        values: [T],
        returningColumns: String? = nil,
        count: PostgrestCount? = nil,
        defaultToNull: Bool? = nil,
        mutationStrategy: SupabaseMutationStrategy = .queueIfOffline
    ) async throws -> PostgrestResult {
        let jsonArray = try encodeToJsonArray(values: values)
        return try await self.insertJsonString(
            jsonArray: jsonArray,
            returningColumns: returningColumns,
            count: count,
            defaultToNull: defaultToNull,
            mutationStrategy: mutationStrategy
        )
    }

    /// Perform an insert using a single `Encodable` object.
    /// - Parameters:
    ///   - value: An `Encodable` object to insert.
    ///   - returningColumns: A comma-separated string of columns to return, or "*" to return all columns.
    ///   - count: The count option for the insert operation.
    ///   - defaultToNull: If true, missing fields in the JSON object will be set to null.
    ///   - mutationStrategy: The strategy for the mutation operation (queueIfOffline, onlineOnly).
    /// - Returns: A `PostgrestResult` containing the result of the insert operation.
    func insertOne<T: Encodable>(
        value: T,
        returningColumns: String? = nil,
        count: PostgrestCount? = nil,
        defaultToNull: Bool? = nil,
        mutationStrategy: SupabaseMutationStrategy = .queueIfOffline
    ) async throws -> PostgrestResult {
        try await self.insertMany(
            values: [value],
            returningColumns: returningColumns,
            count: count,
            defaultToNull: defaultToNull,
            mutationStrategy: mutationStrategy
        )
    }
}
