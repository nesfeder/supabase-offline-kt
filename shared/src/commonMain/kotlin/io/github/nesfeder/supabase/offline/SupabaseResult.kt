package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.result.PostgrestResult
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerializationException

/**
 * Typed outcome of decoding a Supabase [PostgrestResult].
 *
 * Replaces the legacy `runCatching { result.decode...() }.getOrNull()` pattern that silently
 * mapped decode failures to `null` / empty list — making malformed rows invisible to consumers
 * and producing user-visible "missing data" bugs that no log line could explain (e.g. a
 * screen flickering and then going blank because a stringified jsonb column failed to
 * deserialize but the failure was swallowed two layers down).
 *
 * Consumers MUST handle every variant. Treating [DecodeError] / [NetworkError] as "no rows"
 * is what this type exists to prevent — they are real signals that something upstream is
 * broken (schema drift, a stringified jsonb column, a transient network blip, an RLS policy
 * change) and need to be surfaced, not swallowed.
 *
 * Empty data is a value; decode failure is a signal.
 */
public sealed interface SupabaseResult<out T> {
    /** Decode succeeded. [value] is the parsed payload (may itself be an empty list). */
    public data class Ok<T>(val value: T) : SupabaseResult<T>

    /** Request succeeded but returned no rows. Legitimate cache miss / unknown id. */
    public data object Empty : SupabaseResult<Nothing>

    /**
     * Decode failed — the row exists but does not match the expected schema.
     *
     * @property cause The underlying [SerializationException].
     * @property rawSample First [DECODE_SAMPLE_LIMIT] characters of the raw response body,
     *   for diagnostics. `null` if the body could not be read.
     */
    public data class DecodeError(
        val cause: SerializationException,
        val rawSample: String?,
    ) : SupabaseResult<Nothing>

    /**
     * Network or REST-layer failure (timeout, 5xx, RLS rejection). Distinct from [DecodeError]
     * so consumers can retry vs. surface a hard error.
     */
    public data class NetworkError(val cause: Throwable) : SupabaseResult<Nothing>
}

/** Maximum characters of the raw response retained on [SupabaseResult.DecodeError.rawSample]. */
public const val DECODE_SAMPLE_LIMIT: Int = 512

/**
 * Pluggable logging seam for [supabaseDecodeError]'s decode diagnostics.
 *
 * The offline SDK owns no logging facility and depends on no app symbol. A consumer that wants
 * the raw-sample decode diagnostic (logged locally for triage, never to a crash reporter, because
 * a raw sample can contain row data) installs an [OfflineLogger] here at startup. Until then
 * [logger] is `null` and decode errors are thrown silently — the standalone-consumer default,
 * matching an un-wired SDK. An app that has its own logging facade installs a bridge to it here
 * during platform startup, right after that facade is initialised.
 *
 * Only the throw path (`decodeListOrThrow` / `decodeSingleOrThrow`) emits here, and only at
 * [OfflineLogLevel.DEBUG]; the log-then-throw behavior is unchanged when a logger is wired.
 */
public object SupabaseResultLogging {
    /**
     * Sink for decode diagnostics, or `null` (default) to emit nothing. Written once at process
     * startup and read on every decode-error throw, so it is [Volatile] to publish the install
     * across threads.
     */
    @Volatile
    public var logger: OfflineLogger? = null
}

/**
 * Decode this [PostgrestResult] as a `List<T>`, returning a [SupabaseResult] that captures
 * decode and network failures as typed variants instead of silently mapping them to empty.
 *
 * Use at every API-layer call site that currently wraps `result.decodeList<T>()` in
 * `runCatching { ... }.getOrNull()` / `.getOrDefault(emptyList())`: a decode error must never be
 * swallowed.
 *
 * [CancellationException] is rethrown — never coerced to [SupabaseResult.NetworkError] — so
 * coroutine cancellation continues to work as expected.
 */
public inline fun <reified T : Any> PostgrestResult.decodeListSafely(): SupabaseResult<List<T>> =
    try {
        SupabaseResult.Ok(decodeList<T>())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (serialization: SerializationException) {
        SupabaseResult.DecodeError(serialization, rawSample = data.take(DECODE_SAMPLE_LIMIT))
    } catch (http: HttpRequestException) {
        SupabaseResult.NetworkError(http)
    } catch (rest: RestException) {
        SupabaseResult.NetworkError(rest)
    }

/**
 * Decode this [PostgrestResult] as a single `T`, returning [SupabaseResult.Empty] when the
 * underlying query returned zero rows. See [decodeListSafely] for the rationale.
 */
public inline fun <reified T : Any> PostgrestResult.decodeSingleSafely(): SupabaseResult<T> =
    when (val list = decodeListSafely<T>()) {
        is SupabaseResult.Ok -> list.value.firstOrNull()
            ?.let { SupabaseResult.Ok(it) }
            ?: SupabaseResult.Empty
        SupabaseResult.Empty -> SupabaseResult.Empty
        is SupabaseResult.DecodeError -> list
        is SupabaseResult.NetworkError -> list
    }

/**
 * Decode this [PostgrestResult] as a `List<T>`, throwing an [IllegalStateException] tagged
 * with `[feature][decode-error] feature.op decode failed. Raw sample: …` on decode failure
 * (after logging the raw sample to stderr) and rethrowing the underlying cause on network
 * failure. [SupabaseResult.Empty] resolves to an empty list.
 *
 * This is the most common usage shape for the never-swallow-a-decode-error pattern: a
 * repository / API impl wants a `List<T>` back,
 * preserves transient-network tolerance via an outer try-catch on `HttpRequestException` /
 * `RestException`, and lets decode errors propagate as loud `IllegalStateException`s.
 *
 * @param feature Short feature tag for the log prefix and exception message — e.g. `"Todos"`,
 *   `"Profile"`, `"Settings"`.
 * @param op Operation name within the feature — typically the calling method's name.
 */
public inline fun <reified T : Any> PostgrestResult.decodeListOrThrow(
    feature: String,
    op: String,
): List<T> = when (val decoded = decodeListSafely<T>()) {
    is SupabaseResult.Ok -> decoded.value
    SupabaseResult.Empty -> emptyList()
    is SupabaseResult.DecodeError -> throw supabaseDecodeError(feature, op, decoded)
    is SupabaseResult.NetworkError -> throw decoded.cause
}

/**
 * Decode this [PostgrestResult] as a single nullable `T`. [SupabaseResult.Empty] resolves
 * to `null`. See [decodeListOrThrow] for the rest of the contract.
 */
public inline fun <reified T : Any> PostgrestResult.decodeSingleOrThrow(
    feature: String,
    op: String,
): T? = when (val decoded = decodeSingleSafely<T>()) {
    is SupabaseResult.Ok -> decoded.value
    SupabaseResult.Empty -> null
    is SupabaseResult.DecodeError -> throw supabaseDecodeError(feature, op, decoded)
    is SupabaseResult.NetworkError -> throw decoded.cause
}

/**
 * Run [block] and tolerate transient Supabase network errors by returning [fallback].
 * Decode failures and other unexpected exceptions propagate. Cancellation always propagates.
 *
 * Use to wrap one-shot reads where a DNS blip, refresh-failed auth, or RLS-mid-migration
 * race shouldn't crash the caller, while still surfacing genuine decode / schema failures
 * loudly via [decodeListOrThrow] / [decodeSingleOrThrow] inside [block].
 */
public inline fun <T> toleratingTransientSupabaseErrors(
    fallback: () -> T,
    block: () -> T,
): T = try {
    block()
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (_: HttpRequestException) {
    fallback()
} catch (_: RestException) {
    fallback()
}

/**
 * Build the diagnostic exception thrown by [decodeListOrThrow] / [decodeSingleOrThrow].
 *
 * The raw sample (and the serializer's own message, which can embed row excerpts) is logged
 * at debug level only, so it stays on the local console and never reaches a crash reporter when
 * a downstream `Flow.catch` records the returned exception as a non-fatal: row data must not
 * leave the device. For the same reason the serializer cause is deliberately NOT
 * chained onto the returned exception; its type is folded into the message instead.
 *
 * `@PublishedApi internal` so reified inlines can call it across module boundaries.
 */
@PublishedApi
internal fun supabaseDecodeError(
    feature: String,
    op: String,
    decoded: SupabaseResult.DecodeError,
): IllegalStateException {
    val sample = decoded.rawSample ?: "<unavailable>"
    SupabaseResultLogging.logger?.log(
        OfflineLogLevel.DEBUG,
        "[$feature] [decode-error] $op decode failed. Raw sample (first ${sample.length} chars): " +
            "$sample. Cause: ${decoded.cause}",
    )
    return IllegalStateException(
        "$feature.$op decode failed: ${decoded.cause::class.simpleName} " +
            "(raw sample logged locally, ${sample.length} chars)",
    )
}
