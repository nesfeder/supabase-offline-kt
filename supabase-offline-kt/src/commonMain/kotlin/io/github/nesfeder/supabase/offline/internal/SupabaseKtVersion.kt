// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal

import io.github.jan.supabase.SupabaseClient
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/** The header supabase-kt names itself and its version in, on every request it sends. */
private const val CLIENT_INFO_HEADER = "X-Client-Info"

/**
 * Logs one warning when the supabase-kt this app resolved is not of the minor this library was
 * built and tested against; a patch difference is no warning, and neither is a version supabase-kt
 * does not report.
 *
 * @param tested the version tested against: [TESTED_SUPABASE_KT_VERSION], from the catalog.
 */
internal fun SupabaseClient.warnUnlessTestedSupabaseKt(
    engineLogger: EngineLogger,
    tested: String = TESTED_SUPABASE_KT_VERSION,
) {
    readSupabaseKtVersion { running ->
        val warning = untestedSupabaseKtWarning(running, tested) ?: return@readSupabaseKtVersion
        engineLogger.warning { warning }
    }
}

/**
 * Hands [onVersion] the version supabase-kt reports for itself in its default `X-Client-Info`
 * header, built by its own code from its own build constant, so it is the version that runs. The
 * header is built without suspending, so [onVersion] runs before this returns; `null` when
 * supabase-kt sets no such header or no longer has the call.
 */
internal fun SupabaseClient.readSupabaseKtVersion(onVersion: (String?) -> Unit) {
    val read: suspend () -> String? = {
        httpClient.getDefaultHeaders()[CLIENT_INFO_HEADER]?.let(::supabaseKtVersionIn)
    }
    // A failure here, a linkage error included, is a version that cannot be told.
    read.startCoroutine(
        Continuation(EmptyCoroutineContext) { result -> onVersion(result.getOrNull()) }
    )
}

/** The version in an `X-Client-Info` value such as `supabase-kt/3.8.0; platform=Android`. */
internal fun supabaseKtVersionIn(clientInfo: String): String? =
    Regex("""(?:^|[;\s])supabase-kt/([^;\s]+)""").find(clientInfo)?.groupValues?.get(1)

/**
 * The warning for an app running supabase-kt [running] against a library tested with [tested], or
 * `null` when they share a major and minor version or [running] is unknown.
 */
internal fun untestedSupabaseKtWarning(running: String?, tested: String): String? {
    if (running == null || minorOf(running) == minorOf(tested)) return null
    return "This app uses supabase-kt $running, and this release of supabase-offline-kt was built " +
        "and tested against supabase-kt ${minorOf(tested)} ($tested). Another minor release can " +
        "fail at run time; use supabase-kt ${minorOf(tested)}.x"
}

/** The `major.minor` of a version such as `3.8.0` or `3.9.0-beta1`. */
private fun minorOf(version: String): String = version.split('.', '-').take(2).joinToString(".")
