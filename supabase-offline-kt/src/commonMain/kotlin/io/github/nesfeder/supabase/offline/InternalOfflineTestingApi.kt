package io.github.nesfeder.supabase.offline

/**
 * Marks the SDK's hooks for its own testing artifact, `supabase-offline-kt-testing`.
 *
 * A declaration carrying this annotation exists so that artifact can build an in-memory client
 * without the SDK making its storage layer public. It is not part of the supported API and can
 * change or disappear in any release. Depend on `supabase-offline-kt-testing` and call its
 * `buildInMemorySupabaseOfflineClient` instead.
 */
@RequiresOptIn(
    message = "This hook exists only for the supabase-offline-kt-testing artifact. Use " +
        "buildInMemorySupabaseOfflineClient from supabase-offline-kt-testing instead.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
public annotation class InternalOfflineTestingApi
