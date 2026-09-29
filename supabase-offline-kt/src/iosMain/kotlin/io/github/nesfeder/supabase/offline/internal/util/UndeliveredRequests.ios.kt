// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.util

/**
 * The `NSURLErrorDomain` codes that mean the request never left the device: host not found, could
 * not connect, name lookup failed, no internet connection, roaming off, cellular data not allowed.
 * The last three describe the network path, and iOS can also raise them for a request already under
 * way when the path drops; they are kept, since without them nearly every offline INSERT would
 * count as possibly delivered.
 */
private val NEVER_SENT_URL_ERROR_CODES: Set<Int> = setOf(-1003, -1004, -1006, -1009, -1018, -1020)

/**
 * The code of a top-level `NSURLErrorDomain` error, at the start of the message the Darwin engine
 * writes (`Exception in http request: ` and the `NSError`'s description, which is not localised). A
 * code nested under another error, or quoted in any other message, does not match.
 */
private val URL_ERROR_CODE =
    Regex("""^Exception in http request: Error Domain=NSURLErrorDomain Code=(-?\d+)""")

/**
 * The Darwin engine reports a failure as an exception carrying the `NSError` in its message; its
 * type is not visible without that engine as a dependency, so the error code is read from the text.
 * A message from another engine does not match.
 */
internal actual fun isConnectFailureOnThisPlatform(failure: Throwable): Boolean {
    val code =
        failure.message
            ?.let { message -> URL_ERROR_CODE.find(message) }
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    return code in NEVER_SENT_URL_ERROR_CODES
}
