// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.nesfeder.supabase.offline.internal.db.MutationRow

/** Why a queued upload is refused when the client has no Storage plugin to send it. */
internal const val STORAGE_NOT_INSTALLED_REASON: String =
    "The queued upload cannot be sent: the Storage plugin is not installed"

/**
 * How the replay sends a queued Storage upload: the Storage side of a pass, which the client gives
 * its replayer. A replayer built without one refuses every queued upload it meets.
 */
internal interface QueuedUploadSends {

    /**
     * How to send the queued upload [upload] now, or why it cannot be sent: its body unreadable,
     * its file gone from the device, or no Storage plugin installed.
     *
     * @param earlierSendUnconfirmed whether an earlier send of it may have reached Storage.
     */
    suspend fun prepare(upload: MutationRow, earlierSendUnconfirmed: Boolean): UploadSend

    /**
     * Called once the sent upload [upload] has left the queue, under the send lock: its file goes
     * before another pass can find the queue without it.
     */
    suspend fun sent(upload: MutationRow)
}

/** A queued upload ready to send, or refused before any request. */
internal sealed interface UploadSend {

    /**
     * Ready: [send] sends it, under the pass's owner check, and says whether Storage already held
     * it (an earlier send whose answer was lost had landed). It throws the request's failure.
     */
    class Ready(val send: suspend () -> Boolean) : UploadSend

    /**
     * Refused before any request.
     *
     * @property reason what the log says, after the upload's description.
     * @property message what the rejected entry says.
     */
    class Refused(val reason: String, val message: String) : UploadSend
}
