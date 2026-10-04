// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testdoubles

/** What a supabase-kt 3.6 session store throws when nothing is stored, as its own stores do. */
internal fun noStoredSession(): Throwable = IllegalStateException("No session stored")
