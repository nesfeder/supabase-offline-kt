# Security policy

## Reporting a vulnerability

Report it privately, through GitHub's private vulnerability reporting:
[open a report](https://github.com/nesfeder/supabase-offline-kt/security/advisories/new) on this
repository's security advisories page. Only you and the maintainer can see it.

**Never report a vulnerability in an issue, a discussion or a pull request.** Those are public,
and they tell everyone how to use it before a fix exists.

A useful report says what an attacker could do, the library version and platform it affects,
and the steps or the code that show it.

## What happens next

- You get an acknowledgment within seven days.
- The fix ships in the next release of the latest minor line: a patch release when the release
  rules in CONTRIBUTING allow one (no change to the public API, a stored format, a dependency a
  consumer sees, or a minimum platform version), otherwise the next minor release. A GitHub
  security advisory describes the problem, says which versions it affects and names the version
  that fixes it. The advisory is published when the fix is released, not before.
- You are credited in the advisory if you want to be; say so in the report, with the name to
  use.

## Supported versions

Only the latest minor release gets security fixes. While the library is below 1.0, that means
the latest `0.x` release: when `0.3.0` is out, a fix ships as `0.3.1` (or as `0.4.0`, when the
release rules require a minor: a change to the public API, a stored format, a dependency a
consumer sees, or a minimum platform version), and `0.2.x` gets none.
Upgrade to the latest minor release to receive fixes.

## Scope

The library keeps data on the device: cached query results, queued writes and downloaded files.
A problem is in scope when it exposes that data to another app or to another signed-in user,
sends it somewhere it should not go, or writes files outside the library's own directory, as well
as anything else in this repository's code.

A vulnerability in supabase-kt, or in Supabase itself, belongs to those projects: report it to
them.
