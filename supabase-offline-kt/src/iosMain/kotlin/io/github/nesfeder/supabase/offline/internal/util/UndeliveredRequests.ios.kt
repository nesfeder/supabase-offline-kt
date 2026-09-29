package io.github.nesfeder.supabase.offline.internal.util

/**
 * The `NSURLErrorDomain` codes that mean the request never left the device: host not found, could
 * not connect, name lookup failed, no internet connection, roaming off, cellular data not allowed.
 */
private val NEVER_SENT_URL_ERROR_CODES: Set<Int> = setOf(-1003, -1004, -1006, -1009, -1018, -1020)

/** The `NSURLErrorDomain` code in an `NSError` description, as the Darwin engine's exception message carries it. */
private val URL_ERROR_CODE = Regex("""NSURLErrorDomain Code=(-?\d+)""")

/**
 * The Darwin engine reports a failure as an exception carrying the `NSError` in its message; its
 * type is not visible without that engine as a dependency, so the error code is read from the text.
 */
internal actual fun isConnectFailureOnThisPlatform(failure: Throwable): Boolean {
    val code = failure.message?.let { message -> URL_ERROR_CODE.find(message) }?.groupValues?.get(1)?.toIntOrNull()
    return code in NEVER_SENT_URL_ERROR_CODES
}
