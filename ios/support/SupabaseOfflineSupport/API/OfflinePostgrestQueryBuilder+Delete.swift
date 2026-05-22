import Foundation
import SupabaseOffline

public extension OfflinePostgrestQueryBuilder {

    /// Deletes rows in the table. Provide the row via the `filter` closure.
    /// - Parameters:
    ///   - returningColumns: A comma-separated list of columns to return after the delete, or "*" to return all columns.
    ///   - count: The count option for the delete operation.
    ///   - mutationStrategy: The strategy for the mutation operation (queueIfOffline, onlineOnly).
    ///   - filter: A closure to apply filters to the delete operation.
    /// - Returns: A `PostgrestResult` containing the result of the delete operation
    func deleteWhere(
        returningColumns: String? = nil,
        count: PostgrestCount? = nil,
        mutationStrategy: SupabaseMutationStrategy = .queueIfOffline,
        filter: ((PostgrestFilter) -> Void)? = nil
    ) async throws -> PostgrestResult {
        try await self.deleteWhereKt(
            returningColumns: returningColumns,
            count: count,
            mutationStrategy: mutationStrategy.toKMP(),
            filter: filter
        )
    }
}
