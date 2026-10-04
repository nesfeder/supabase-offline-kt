// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.query.PostgrestQueryBuilder
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import io.ktor.util.AttributeKey
import io.ktor.util.pipeline.PipelinePhase
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** What the dry-run check learned about a write without sending it. */
internal sealed interface DryRunVerdict {

    /** The write asks PostgREST to roll its transaction back: `Prefer: tx=rollback`. */
    data object DryRun : DryRunVerdict

    /** An ordinary write. */
    data object NotDryRun : DryRunVerdict

    /** The write failed with [cause] before its request was built, so it was not sent. */
    data class NotBuilt(val cause: Exception) : DryRunVerdict

    /** The check is not installed on this client, so it cannot tell. */
    data object Unavailable : DryRunVerdict

    /** Nothing stopped the request, so the write went out and the server answered [result]. */
    data class Sent(val result: PostgrestResult) : DryRunVerdict
}

/**
 * Tells a dry run (`Prefer: tx=rollback`) from an ordinary write before it is queued. The queue
 * does not keep the `Prefer` header, so a queued dry run would replay as a real write.
 */
internal fun interface DryRunProbe {

    /** Builds the request [write] would send and reads it, stopping it before the network. */
    suspend fun classify(write: suspend () -> PostgrestResult): DryRunVerdict
}

/**
 * The [DryRunProbe] over supabase-kt's HTTP pipeline. A dry run shows only as `tx=rollback` in a
 * `Prefer` header (supabase-kt 3.8's `dryRun()`, or the header a caller adds), so the write runs
 * under a [PreferHeaderCapture], and the phase that [installDryRunCheck] puts ahead of every plugin
 * reads every `Prefer` value and stops the request.
 *
 * A dry run is never queued; whether the server rolled it back is read from [wasRolledBack].
 *
 * @param stopsEveryRequest whether the check stops every request the write makes, not only the
 *   PostgREST write: for a write built on a handle whose requests carry a placeholder token, which
 *   must never leave the device.
 */
internal class PreferHeaderDryRunProbe(
    private val supabase: SupabaseClient,
    private val stopsEveryRequest: Boolean = false,
) : DryRunProbe {

    override suspend fun classify(write: suspend () -> PostgrestResult): DryRunVerdict {
        if (!supabase.hasDryRunCheck()) return DryRunVerdict.Unavailable
        val capture = PreferHeaderCapture(stopsEveryRequest)
        val result =
            try {
                withContext(capture) { write() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                if (!capture.hasObservedWrite) return DryRunVerdict.NotBuilt(failure)
                null
            }
        return when {
            result != null -> DryRunVerdict.Sent(result)
            capture.requestedRollback -> DryRunVerdict.DryRun
            else -> DryRunVerdict.NotDryRun
        }
    }
}

/**
 * A coroutine-context element that makes the phase [installDryRunCheck] adds record the `Prefer`
 * header of a PostgREST write sent from its coroutine, then stop the request.
 *
 * @property stopsEveryRequest whether the phase stops every request sent from its coroutine, a
 *   write or not.
 */
internal class PreferHeaderCapture(val stopsEveryRequest: Boolean = false) :
    AbstractCoroutineContextElement(Key) {

    /** The key a capture is found under in a coroutine context. */
    companion object Key : CoroutineContext.Key<PreferHeaderCapture>

    /**
     * Every `Prefer` value of the request the capture saw, joined with commas, or `null` while it
     * has seen none or the request carried none.
     */
    var prefer: String? = null
        private set

    /** How many PostgREST write requests the capture saw. */
    var observations: Int = 0
        private set

    /** Whether the capture saw a PostgREST write request. */
    val hasObservedWrite: Boolean
        get() = observations > 0

    /** Whether the request the capture saw asked PostgREST to roll its transaction back. */
    val requestedRollback: Boolean
        get() = namesRollback(prefer)

    /** Records the `Prefer` values of a request, in order, or `null` when it carried none. */
    fun record(preferValues: List<String>?) {
        prefer = preferValues?.joinToString(",")
        observations++
    }
}

/** Whether a `Prefer` or `Preference-Applied` header value carries `tx=rollback`. */
internal fun namesRollback(header: String?): Boolean =
    header.orEmpty().split(',').any { preference -> preference.trim() == TX_ROLLBACK_PREFERENCE }

/**
 * Whether PostgREST rolled the write that produced this result back: its `Preference-Applied`
 * header says `tx=rollback`, which PostgREST sends only when it honored the preference.
 */
internal val PostgrestResult.wasRolledBack: Boolean
    get() = namesRollback(headers.getAll(PREFERENCE_APPLIED_HEADER)?.joinToString(","))

/** The preference that makes a write a dry run, as supabase-kt 3.8's `dryRun()` writes it. */
private const val TX_ROLLBACK_PREFERENCE = "tx=rollback"

/** The response header PostgREST lists the preferences it applied in. */
private const val PREFERENCE_APPLIED_HEADER = "Preference-Applied"

/** Thrown by the dry-run check's phase to stop a request; caught by [PreferHeaderDryRunProbe]. */
private class DryRunCheckStop : Exception("Request stopped by the offline engine's dry-run check")

/** The request-pipeline phase the dry-run check runs in, ahead of every plugin's. */
private val DryRunCheckPhase = PipelinePhase("SupabaseOfflineDryRunCheck")

/** Set on a client's HTTP client once the dry-run check is installed. */
private val DryRunCheckInstallation =
    AttributeKey<AtomicBoolean>("io.github.nesfeder.supabase.offline.PreferHeaderProbe")

/**
 * Installs the dry-run check on this client's request pipeline, in a phase ahead of every other,
 * once however many threads call it. A flag in the client's attributes, claimed by compare-and-set,
 * admits one installer. The check acts only on a PostgREST write sent under a
 * [PreferHeaderCapture]; no plugin sees the request it stops, but an `HttpRequestCreated` monitor
 * subscriber does, since that event fires before the pipeline runs.
 *
 * @return `true` when this call installed it.
 */
@OptIn(SupabaseInternal::class)
internal fun SupabaseClient.installDryRunCheck(): Boolean {
    val client = httpClient.httpClient
    // computeIfAbsent may run its block more than once under a race, but every caller gets the one
    // instance that was stored; the compare-and-set on it is what admits a single installer.
    val installation =
        client.attributes.computeIfAbsent(DryRunCheckInstallation) { AtomicBoolean(false) }
    if (!installation.compareAndSet(expectedValue = false, newValue = true)) return false
    client.requestPipeline.insertPhaseBefore(HttpRequestPipeline.Before, DryRunCheckPhase)
    client.requestPipeline.intercept(DryRunCheckPhase) {
        val capture = currentCoroutineContext()[PreferHeaderCapture] ?: return@intercept
        val write = isPostgrestWrite(context)
        // Every value: supabase-kt writes its own Prefer first and appends one the caller adds.
        if (write) capture.record(context.headers.getAll(PostgrestQueryBuilder.HEADER_PREFER))
        if (write || capture.stopsEveryRequest) throw DryRunCheckStop()
    }
    return true
}

/** Set on a client's HTTP client once the committed dry-run report is installed. */
private val CommittedDryRunReportInstallation =
    AttributeKey<AtomicBoolean>("io.github.nesfeder.supabase.offline.CommittedDryRunReport")

/**
 * Installs, once per client, a check beside the engine that calls [onCommitted] when a dry run was
 * committed. That is a PostgREST write that asked for `Prefer: tx=rollback` and succeeded without
 * `Preference-Applied` listing it, as hosted projects answer: their `db-tx-end` allows no override.
 */
@OptIn(SupabaseInternal::class)
internal fun SupabaseClient.installCommittedDryRunReport(onCommitted: () -> Unit) {
    val client = httpClient.httpClient
    val installation =
        client.attributes.computeIfAbsent(CommittedDryRunReportInstallation) {
            AtomicBoolean(false)
        }
    if (!installation.compareAndSet(expectedValue = false, newValue = true)) return
    client.plugin(HttpSend).intercept { request ->
        val call = execute(request)
        val askedForRollback =
            request.headers
                .getAll(PostgrestQueryBuilder.HEADER_PREFER)
                .orEmpty()
                .any(::namesRollback)
        if (
            askedForRollback &&
                call.response.status.isSuccess() &&
                !namesRollback(
                    call.response.headers.getAll(PREFERENCE_APPLIED_HEADER)?.joinToString(",")
                ) &&
                isPostgrestWrite(request)
        )
            onCommitted()
        call
    }
}

/** Whether the dry-run check is on this client's pipeline. */
@OptIn(SupabaseInternal::class)
private fun SupabaseClient.hasDryRunCheck(): Boolean =
    httpClient.httpClient.attributes.getOrNull(DryRunCheckInstallation)?.load() == true

/** Whether [request] is a write to this client's PostgREST: not a read, and under its URL. */
private fun SupabaseClient.isPostgrestWrite(request: HttpRequestBuilder): Boolean {
    if (request.method == HttpMethod.Get || request.method == HttpMethod.Head) return false
    val postgrest = pluginManager.getPluginOrNull(Postgrest) ?: return false
    val base = postgrest.resolveUrl().trimEnd('/')
    val url = request.url.buildString()
    return url == base || url.startsWith("$base/") || url.startsWith("$base?")
}
