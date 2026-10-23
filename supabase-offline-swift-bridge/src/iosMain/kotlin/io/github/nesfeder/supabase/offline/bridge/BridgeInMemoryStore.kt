// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.bridge

import io.github.nesfeder.supabase.offline.testing.InMemoryStore

/**
 * The local data of in-memory clients, kept from one client to the next, for an app's test of what
 * happens after a restart; hand it to [BridgeTesting.openInMemory]. A client opened over it after
 * an earlier one closed finds what that one left: the queued writes, the rejected and conflicted
 * lists, the cached queries and files. One client holds it at a time: the open of a second while
 * another is open, or still closing, fails.
 */
public class BridgeInMemoryStore {

    /** The library's store, which the in-memory clients keep their data in. */
    internal val store: InMemoryStore = InMemoryStore()
}
