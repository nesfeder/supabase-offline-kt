// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data

import kotlinx.coroutines.flow.StateFlow

/**
 * Who the app is signed in as: every row and every photo path carries this user's id. The app signs
 * in anonymously, so a user is a device until Start over makes a new one.
 */
interface UserSession {
    /** The signed-in user's id, or null while there is no session yet. */
    val userId: StateFlow<String?>

    /**
     * Signs out and signs in again as a new anonymous user, returning once the new user's session
     * is in [userId].
     */
    suspend fun signInAsNewUser()
}
