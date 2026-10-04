// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.testing

import io.github.nesfeder.offlinetasks.data.UserSession
import kotlinx.coroutines.flow.MutableStateFlow

/** A [UserSession] a test drives: signed in as [userId], and each new user takes the next id. */
class FakeUserSession(userId: String? = FIRST_USER_ID) : UserSession {

    override val userId: MutableStateFlow<String?> = MutableStateFlow(userId)

    /** How many times the app asked for a new user. */
    var newUserCount: Int = 0
        private set

    override suspend fun signInAsNewUser() {
        newUserCount++
        userId.value = "00000000-0000-4000-8000-00000000000${newUserCount + 1}"
    }

    companion object {
        /** The first user's id, a lower-case UUID as Supabase writes one. */
        const val FIRST_USER_ID: String = "00000000-0000-4000-8000-000000000001"
    }
}
