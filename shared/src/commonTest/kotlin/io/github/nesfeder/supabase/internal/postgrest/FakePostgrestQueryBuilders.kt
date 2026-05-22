package io.github.nesfeder.supabase.internal.postgrest

import io.github.nesfeder.supabase.internal.FakePostgrest
import io.ktor.client.network.sockets.ConnectTimeoutException

/**
 * Helpers for configuring [FakePostgrest] with table-specific exception
 * behaviour via its [FakePostgrest.throwException] callback.
 *
 * These utilities replace the older `BaseFakePostgrestQueryBuilder`-style
 * subclasses, which are no longer viable now that [PostgrestQueryBuilder]
 * and its mutation methods are effectively final.
 */

/**
 * Type alias for the callback shape used by [FakePostgrest.throwException].
 */
typealias FakePostgrestThrower = (table: String) -> Throwable?

/**
 * Configure [FakePostgrest] so that all `from(..)` lookups succeed without
 * throwing (the default behaviour). This is mostly provided for symmetry and
 * clarity in tests.
 */
fun FakePostgrest.withNoThrowingFrom(): FakePostgrest = apply {
    throwException = null
}

/**
 * Returns a [FakePostgrestThrower] that always throws a network/connectivity
 * style failure whenever `from(table)` (or `from(schema, table)`) is called
 * for the given [targetTable]. Other tables are unaffected.
 */
fun networkFailureOnTable(targetTable: String): FakePostgrestThrower {
    return { table ->
        if (table == targetTable) ConnectTimeoutException("network") else null
    }
}

/**
 * Returns a [FakePostgrestThrower] that always throws an unknown
 * server/application error whenever `from(table)` is called for [targetTable].
 * Other tables are unaffected.
 */
fun unknownServerErrorOnTable(targetTable: String): FakePostgrestThrower {
    return { table ->
        if (table == targetTable) IllegalStateException("server error") else null
    }
}

/**
 * Convenience extension to apply a [FakePostgrestThrower] to this
 * [FakePostgrest] instance.
 */
fun FakePostgrest.withThrower(thrower: FakePostgrestThrower): FakePostgrest = apply {
    throwException = thrower
}