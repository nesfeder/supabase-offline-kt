# Home directory snapshot

Used only by the iOS tests job, and only on the self-hosted runner. The job's first protected step
records the runner account's home directory, and its last step, after the clean-up, compares the
home directory with that record. Any difference fails the job. That is how the job proves it left
nothing behind on a machine that outlives it.

## What it records

Every entry in the first two levels of `~`, found with `find ~ -mindepth 1 -maxdepth 2`, without
following symbolic links. Then, one level further down, every entry directly inside a few
persistence points: `Library/LaunchAgents`, `.gradle/init.d`, `.config/git`, and each runner's
`bin*` and `externals*` folders (a folder that is a symbolic link is skipped; its target is listed
under its own name). For each entry it records:

- the path, relative to `~`;
- the size in bytes;
- the modification time;
- the status-change time (ctime);
- the mode.

It never records a file's contents. The status-change time moves whenever a file is written,
renamed, or has its mode, owner or extended attributes changed, and user space cannot set it back.
So it catches a rewrite whose modification time was restored (`cp -p`, `touch -r`, `rsync -t`, an
archive extraction), a `chmod`, a `chown` and an added extended attribute. The mode says which of
those a red line is: a line whose mode changed was a `chmod`.

Desktop, Documents, Downloads, Movies, Music, Pictures and `.Trash` are recorded by name only, never
listed or timed. macOS asks the user before a process reads inside them.

## What it does not watch

The exclusion list in `home-snapshot.sh` names each path that something other than the job writes
while the job runs. Each line gives its reason: what writes there, and why the job does not. The
list was settled from samples of the runner's home taken every ten minutes with no job running.
Everything else is watched, including:

- the rest of `~/Library`'s entries: `LaunchAgents` and each agent in it, `Application Support`,
  `Developer`, `Logs`, `Containers`;
- every runner directory's control files (`.env`, `.path`, `.runner`, `runsvc.sh`), the programs
  directly inside its `bin` and `externals`, and any new `~/actions-runner-*` directory;
- the Gradle User Home's entries (`init.d` and each script in it, `gradle.properties`, `caches`)
  and the Kotlin/Native directory's entries.

## What it cannot see

- **A file edited in place three or more levels down, outside the persistence points above.** A
  directory's times move only when an entry is added, removed or renamed in it. So a new file three
  levels down is caught, through its parent, but an existing one rewritten in place is not, except
  in the persistence points. Nor is a new file four levels down: inside `.konan`'s toolchains, a
  runner's `externals/node24/bin`, or another runner's `_work/_tool`.
- **Symbolic link targets.** A link is recorded as itself; what it points to is not followed.
- **Anything under an excluded path,** and anything inside the seven folders recorded by name only.
  `.claude*` matches any new top-level entry whose name starts that way, and every runner's
  `_work` hides that runner's tool cache.
- **A change that is undone before the comparison,** such as a file made and deleted directly in
  `~` (the home directory's own entry is not recorded). Inside a watched directory, the directory's
  times still move.
- **A change inside the same second as the record,** since times are whole seconds. The record is
  taken before the job's first real step, so the job cannot reach it.
- **Anything outside the home directory,** such as the per-user temporary directory. The clean-up
  step checks what the job puts there by name.
- **A job that means to hide a change.** The record lives in a folder any job on the runner can
  write, so a job that edits its own record, or runs its change after the comparison, passes. The
  snapshot catches accidents and tools that write where they should not; the review of what runs on
  the machine is what stops intent.

## When it fails

A red comparison lists each changed path with its values before and after. It cannot tell who made
the change: the job, or anything else running as the same user while the job ran. If the path is
not one the job writes, re-run the job. If the same path comes back, add it to the exclusion list
with its reason, in a pull request that shows the samples behind it.

**A runner's own writes.** The runners write two kinds of their own files, which are watched on
purpose:

- `.runner_migrated` and `.credentials_migrated`, rewritten (deleted, then written again) when
  GitHub refreshes the runner's settings. A red that names only a runner's directory and one of
  these is that refresh. It is rare, not per job: one landed inside a job's record-to-comparison
  window on 2026-09-29.
- `bin.*`, `externals.*` and `update.finished`, written when a runner updates itself, with its
  `bin` and `externals` links moved to the new folders.

The runner's `_diag` log records either one. Re-run the job, and do not exclude these files: the
listener runs from `.runner_migrated`, so it is the runner's live configuration.

## A job that never compared

The record is kept beside the runner's work folder, which the snapshot does not watch and the
runner does not empty, with the URL of the run attempt that made it. A job killed before its
comparison (a reboot, the service stopped) leaves it there. The next job's record step then prints
what changed since that record as a warning, naming the run that left it, and goes on with a record
of its own. The warning covers everything that changed in between, so read it for the path the
killed job might have left.

A comparison uses only its own job's record. If the record step failed, the comparison reports the
earlier record it finds as that earlier run's, not as this job's changes, and fails.

The script's own temporary files go in a folder it makes inside the job's temporary folder, and it
removes that folder on every exit: a failed comparison, an error and a cancellation included.
