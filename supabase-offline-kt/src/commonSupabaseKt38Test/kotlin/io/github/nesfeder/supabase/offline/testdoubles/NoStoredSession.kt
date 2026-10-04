// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testdoubles

import io.github.jan.supabase.auth.exception.NoSessionFoundException

/** What a session store throws when nothing is stored, from supabase-kt 3.7 on. */
internal fun noStoredSession(): Throwable = NoSessionFoundException()
