# Signing out and switching users

What happens to cached data and queued writes when a user signs out or another user signs in.

> **Not written yet.** This page is a placeholder in the planned site. The headings below are its
> outline; the content will be written for 0.1.0, once the API it describes has settled.

## Why it matters

## What clearing the cache deletes

## Order of operations

## Switching accounts

Facts about the engine as it is, with the Auth plugin installed. The query cache and the Storage
file cache belong to one identity at a time: a signed-in user, or nobody signed in.

- A read never returns what was cached for another identity, and a response or download that
  started before the switch is never written into the new identity's cache.
- When the session settles into a different identity, both caches are dropped. The mutation queue
  is kept whole: each identity's writes wait for it.
- A cache from an earlier build, which recorded no owner, is adopted by the user signed in when the
  app starts; signed out, it is dropped, since it may be an earlier user's. Nobody signed in claims
  only an empty cache.
- Without the Auth plugin, or with the app's own token provider, there is one identity, nobody
  signed in, for the life of the install: the cache is served as it is and never dropped for a
  change of user, since nothing the engine can read tells one user from another. An app whose own
  token provider serves more than one user must call `clearAllCache()` itself when its user
  changes.

<small>`CacheBelongsToOneIdentityTest.a_signed_out_answer_is_never_served_to_the_user_who_signs_in_next`,
`CacheBelongsToOneIdentityTest.signing_in_over_another_user_drops_the_cache_and_keeps_every_queued_write`,
`CacheBelongsToOneIdentityTest.a_change_of_user_drops_the_file_cache_and_its_rows`,
`CacheBelongsToOneIdentityTest.a_restart_between_the_change_of_user_and_the_drop_never_serves_the_last_users_rows`,
`CacheBelongsToOneIdentityTest.an_upgraded_install_signed_in_adopts_what_is_cached`,
`CacheBelongsToOneIdentityTest.an_upgraded_install_signed_out_drops_what_is_cached`,
`CacheBelongsToOneIdentityTest.without_the_auth_plugin_the_cache_is_one_anonymous_identitys_for_good`,
`OfflineStoreCacheOwnerTest.a_response_or_write_that_began_before_a_drop_is_kept_out_of_the_cache_not_the_queue`</small>

`clearAllCache()` on sign-out is still how to delete a user's data from the device: the drop on a
change of identity keeps the mutation queue, which holds the user's unsent writes.

## Deleting an account

## Caveats
