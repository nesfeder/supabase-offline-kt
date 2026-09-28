package io.github.nesfeder.supabase.offline.internal.outbox

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/*
 * The outbox's own vocabulary, named after the offline API sheet so a later change can expose these
 * types publicly under the same names. They stay internal until then.
 */

/** The PostgREST write a queued mutation performs. */
internal enum class MutationOperation {
    /** `POST` of new rows. */
    Insert,

    /** `POST` with a conflict resolution. */
    Upsert,

    /** `PATCH` of the rows a filter selects. */
    Update,

    /** `DELETE` of the rows a filter selects. */
    Delete,
}

/**
 * One write waiting in the queue.
 *
 * @property id The mutation's stable id: the queue row's autoincrement id. It survives process death
 *   and is carried by a [RejectedMutation] when the server refuses the write.
 * @property table The table the write targets.
 * @property operation What the write does.
 * @property createdAt When the write was queued, by the device clock. Replay order is [id], not this.
 * @property rowKeys The primary-key values the engine can determine for the rows the write touches:
 *   one object per body row that carries the key, or the key's `eq` conditions of the filter. Empty
 *   when they cannot be determined.
 * @property body The write's body, or `null` for a DELETE or a body that no longer parses.
 */
internal data class PendingMutation(
    val id: Long,
    val table: String,
    val operation: MutationOperation,
    val createdAt: Instant,
    val rowKeys: List<JsonObject>,
    val body: JsonElement?,
)

/**
 * What the server said when it refused a write, as data rather than an exception so it survives a
 * restart. The fields are PostgREST's error fields.
 *
 * @property status The HTTP status.
 * @property code The Postgres SQLSTATE or PostgREST error code, when the server sent one.
 * @property message The server's message.
 * @property details The server's details, when it sent any.
 * @property hint The server's hint, when it sent one.
 */
internal data class ServerError(
    val status: Int,
    val code: String?,
    val message: String,
    val details: String?,
    val hint: String?,
)

/**
 * A write the server refused for good (or the engine refused to send), kept until the app dismisses
 * or retries it.
 *
 * @property id The id the write had in the queue.
 * @property table The table the write targeted.
 * @property operation What the write did.
 * @property createdAt When the write was queued.
 * @property rowKeys As for [PendingMutation.rowKeys].
 * @property body The write's body, or `null` for a DELETE, a body that no longer parses, or a body
 *   too large to keep on the rejected list (see [bodyOmittedChars]).
 * @property serverError The server's refusal, or `null` when the engine refused it locally (a body
 *   that no longer parses, or a filter that cannot be replayed faithfully).
 * @property message A human-readable reason.
 * @property rejectedAt When the write was refused.
 * @property bodyOmittedChars The size of the body when it was too large to keep on the rejected list
 *   and was dropped from it, or `null` when it was kept. Such a write cannot be retried.
 */
internal data class RejectedMutation(
    val id: Long,
    val table: String,
    val operation: MutationOperation,
    val createdAt: Instant,
    val rowKeys: List<JsonObject>,
    val body: JsonElement?,
    val serverError: ServerError?,
    val message: String,
    val rejectedAt: Instant,
    val bodyOmittedChars: Int? = null,
)

/**
 * The result of one replay pass. Every count is the current identity's: another identity's writes
 * are neither sent nor counted.
 *
 * @property syncedCount Writes the server accepted in this pass.
 * @property rejected Writes refused in this pass, now on the rejected list.
 * @property remainingCount The current identity's writes still pending once the pass ended, within
 *   its scope (one table or every table). When the identity could not be told, every pending write.
 * @property stop Why the pass ended before the queue was empty, or `null` when it ran to the end.
 */
internal data class SyncReport(
    val syncedCount: Int,
    val rejected: List<RejectedMutation>,
    val remainingCount: Int,
    val stop: SyncStop?,
)

/** Why a replay pass ended with writes still pending. */
internal sealed interface SyncStop {

    /**
     * The session did not settle (signed in, or signed out) within the bounded wait, so nothing was
     * sent: a replay now could go out as the wrong identity.
     */
    data object SessionNotReady : SyncStop

    /**
     * The identity a request would go out as could not be told (the session restoring or
     * refreshing), at the start of the pass or before a request, so nothing more was sent.
     */
    data object IdentityUnknown : SyncStop

    /**
     * The session changed identity during the pass — a sign-out, a sign-in, or another account — so
     * the pass stopped before its next request. The new identity's writes go in the next pass.
     */
    data object SessionChanged : SyncStop

    /**
     * The record of which identity made each write could not be read, so no write was sent: sending
     * one could send it as someone who did not make it. They stay until local data is cleared.
     */
    data object OwnershipUnreadable : SyncStop

    /**
     * The next write is being sent right now by the call that made it, so the pass stopped rather
     * than send it twice or overtake it. That call asks for another pass when it lands with more
     * pending.
     */
    data object HeadInFlight : SyncStop

    /** Local data was cleared while the pass was running; nothing was sent after that. */
    data object LocalDataCleared : SyncStop

    /**
     * A write could not go through for a reason that may pass — no connectivity, a `401`, `408`,
     * `429` or `5xx`, or an unexpected error — so the pass stopped to keep later writes behind it.
     *
     * @property cause The failure.
     */
    data class TransientFailure(val cause: Throwable) : SyncStop

    /**
     * The server refused a write with `403` or `409` while an earlier write of the same identity — in
     * any table — was still pending. The earlier write may be the parent row or the row a policy
     * checks, so the refusal is not taken as final: the write is kept and the pass stops.
     *
     * @property mutationId The held write.
     * @property cause The refusal.
     */
    data class HeldBehindEarlierMutations(val mutationId: Long, val cause: Throwable) : SyncStop
}
