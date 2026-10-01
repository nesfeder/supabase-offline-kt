# Contributing

Thank you for helping. Instructions for building and testing the library itself will follow; for
now, this file covers reporting a problem, labels, formatting, changing the public API, what is
published, releasing, test coverage, the documentation site, how dependency updates are handled,
and continuous integration: the `full-ci` label, and what to do when CI fails for a reason outside
the change.

## Reporting a problem

Open an issue from one of the
[forms](https://github.com/nesfeder/supabase-offline-kt/issues/new/choose): a bug report asks for
the versions, the platform, whether it happens online, offline or on reconnect, and a minimal
reproduction; a feature request asks for the problem you want solved. Questions go to
[Discussions](https://github.com/nesfeder/supabase-offline-kt/discussions), which open when the
repository goes public.

**A security vulnerability is never reported in an issue.** Report it privately, as
[SECURITY.md](https://github.com/nesfeder/supabase-offline-kt/blob/main/SECURITY.md) describes.

Everyone taking part in issues, discussions and pull requests follows the
[code of conduct](https://github.com/nesfeder/supabase-offline-kt/blob/main/CODE_OF_CONDUCT.md).

## Labels

The maintainer who merges a pull request labels it from the type in its title, so that a release's
generated notes (`.github/release.yml`) put it in the right group:

| Title type | Label |
| --- | --- |
| `feat` | `enhancement` |
| `fix` | `bug` |
| `docs` | `documentation` |
| `ci` | `ci` |

A pull request that moves a held dependency by hand (see "Held dependencies") also gets
`upstream-pin`, `toolchain` or `persistence`, the labels the Dependabot triage workflow gives
Dependabot's updates of the same dependencies. Any other type needs no label and is listed under
"Other changes". `dependencies`, `gradle`, `github-actions` and `docs-toolchain` are Dependabot's
own, and no person applies them. `full-ci` is described under "Continuous integration".

## Formatting

Kotlin sources and Gradle scripts are formatted by [kempt](https://github.com/ZacSweers/kempt).
It runs [ktfmt](https://github.com/facebook/ktfmt) in its `kotlinlang` style (the Kotlin coding
conventions: 4-space indent, 100 columns) and puts the licence header from
`config/license-header.txt` at the top of every `.kt` and `.kts` file. It also strips trailing
whitespace from Kotlin, Markdown and `.gitignore`, and ends each of those files with a single
newline. Its settings are in `.kempt.toml`. The Formatting job in CI runs `kempt check` on every
pull request.

Install kempt 0.3.1, the version CI runs: from its
[v0.3.1 release](https://github.com/ZacSweers/kempt/releases/tag/v0.3.1), checking the archive
against the `.sha256` file published beside it, or with
`cargo install kempt-fmt --version 0.3.1 --locked`. Homebrew
(`brew install ZacSweers/tap/kempt-fmt`, a third-party tap) installs the newest release instead.
kempt runs ktfmt on the JVM, so it needs JDK 17 or later on your `PATH`, and it downloads the
ktfmt jar the first time it runs. Then, from the repository root:

```bash
kempt format    # format every file git tracks
kempt check     # read-only; what CI runs
```

`kempt format` sees only files git tracks, so `git add` a new file before formatting it. If the
check fails in CI, run `kempt format`, commit the result, and push.

**Write nothing above a file's `package` line, or above a script's first `import` or `plugins`
line.** kempt puts the licence header there and deletes whatever it replaces, without a warning:
in a `.kt` file, everything above the first `package` or `@file:` line; in a `.kts` script,
everything above the first `@file:`, `import`, `plugins`, `buildscript`, `dependencies`,
`pluginManagement` or `dependencyResolutionManagement` line. That includes a comment, or a
statement such as `rootProject.name`.

Copy the header exactly as kempt writes it. `kempt check` looks only for its
`SPDX-License-Identifier` line, so a wrong copyright holder or year, or no copyright line at all,
still passes the check.

The commit that first formatted the whole tree is listed in `.git-blame-ignore-revs`. To have
`git blame` skip it locally, run `git config blame.ignoreRevsFile .git-blame-ignore-revs` once.

### Updating ktfmt

The ktfmt version is `ktfmt` in `gradle/libs.versions.toml`, where Dependabot sees it; its
updates arrive on their own, in the `formatter` group. A new ktfmt can lay code out differently,
and CI runs only the ktfmt jar whose checksum it holds, so an update is three changes in one pull
request:

1. The version in `gradle/libs.versions.toml`.
2. `KTFMT_VERSION` and `KTFMT_SHA256` in the Formatting job of `.github/workflows/ci.yml`.
3. The result of `kempt format` on that branch, in a commit of its own.

## Changing the public API

The library promises what its
[compatibility policy](https://nesfeder.github.io/supabase-offline-kt/project/compatibility/)
describes: before 1.0, a patch release changes nothing in the contract. These steps keep every pull
request honest about what it changes, and CI checks each one.

### 1. Update the API records

Each published module commits a record of its public API, which the Kotlin Gradle plugin writes:

| File | What it covers |
| --- | --- |
| `<module>/api/android/<module>.api` | The Android target, as JVM signatures |
| `<module>/api/<module>.klib.api` | The `iosArm64` and `iosSimulatorArm64` targets, as Kotlin signatures |

`./gradlew check` runs `checkKotlinAbi`, which fails when the code's public API no longer matches
the records. After changing the API, write the records again and commit them with the change:

```bash
./gradlew updateKotlinAbi
git add supabase-offline-kt/api supabase-offline-kt-testing/api
```

Run `updateKotlinAbi` on its own, not in the same Gradle command as `checkKotlinAbi` or `check`:
Gradle refuses to run the two together, because one writes the files the other reads.

Then read the diff of `api/` as a reviewer will. Every line added is a new promise; every line
removed or changed breaks someone.

The records hold every public declaration's types, but not its parameter names or their order.
Renaming a public parameter breaks every caller that passes it by name, and swapping two
parameters of the same type changes what a call does, yet neither changes a record. A reviewer
checks every parameter name and order in the diff of the sources.

### 2. Say what kind of change it is

CI rates each pull request as none, patch, additive or breaking, from the committed records, the
Room schemas, the tests that pin stored formats and the version catalog. To see the rating before
you push, commit and run:

```bash
python3 scripts/api-compat.py classify --base "$(git merge-base origin/main HEAD)"
```

Compare with the merge base, not with `origin/main` itself: on a branch behind `main`, whatever
`main` added since would read as removed.

Then add a line under `## Unreleased` in `CHANGELOG.md`, under the heading the rating needs:

| Rating | Heading |
| --- | --- |
| breaking | `### Breaking`, saying what a user has to do |
| a stored format, a supabase-kt minor, or a raised minimum version | `### Breaking` or `### Changed` |
| additive | `### Added` or `### Changed` |
| none or patch | nothing is required; `### Fixed` for a fix |

Until the first release is tagged, `## Unreleased` describes that release as a whole, so any new or
edited line in it satisfies the check; from the first release on, the rating decides the heading.
`python3 scripts/api-compat.py check-pr --base "$(git merge-base origin/main HEAD)"` runs the same
check CI does.

The script cannot see everything. A person reviews:

- **Parameter names and their order**, which neither record shows (see step 1).
- **Documented behaviour**, such as a default, the order of replay, or what is kept after a
  sign-out. If your change alters it, put it under `### Breaking` yourself: the release check
  treats a Breaking line as breaking whatever the records say.
- **The body of an inline function**, which a caller keeps until it recompiles; anything a
  published inline body calls must stay.
- **A deprecation level raised to error, or a new opt-in requirement** on existing API: neither
  record shows annotations.

The script errs towards breaking. If it calls a change breaking that is not, copy its finding into
`compat/overrides.txt`, followed by two spaces, `# ` and the reason. The reviewer judges the reason.
An override stays until the release after it empties the file, so the release check sees it too; a
line your pull request adds must match one of its findings. A rename inside a test that pins a
stored format (a `persistedContract` file in `compat/policy.json`) rates `format`, even when only
identifiers change. Before the first release, the pull request's line under `## Unreleased` covers
it. After it, do not override that finding until the classifier ties an override to the file's
contents: an override names only the file, so it would also pass a later edit there that does
change a stored format.

The rules the script applies are in `compat/policy.json`. A pull request is rated under the stricter
of its own rules and its base's: a change that loosens them applies from the next pull request on,
one that tightens them at once, and either is reported for review. The release check compares with
the last release, so a rule that loosens takes effect there only in the release after it: once
1.0 has shipped, a type closed for implementation in one pull request and given a new member in a
later one of the same release is still rated breaking when that release is made. Close the type in
one release and add to it in the next.

### 3. What is never allowed

- **Editing an exported Room schema** (`supabase-offline-kt/schemas/`) once a release has shipped
  it. A shipped schema is what devices have on disk. A schema change is then a new version with a
  migration and a migration test, and it waits for a minor release. Until the first release, the
  one schema it will ship, version 1, may change; the script rates that a stored-format change.
- **Changing a stored name or format without saying so.** The tests that pin what is on the device
  (the reserved rows, the persisted names and JSON keys, the stored mutation formats and their
  replay, the file-cache layout, and where the database and the cached files live) are listed in
  `compat/policy.json`. Changing one of them, or loosening it, is reported, and needs a changelog
  line that says what happens to data already on a device. A new pinning test belongs in that list.
- **Hiding API from the records.** Only declarations gated by an error-level opt-in marker listed in
  `compat/abi-exclusions.txt` are left out. `scripts/check-api-visibility.sh` fails when a marker is
  missing from that file or the file names anything else. A public declaration in an internal
  package also has to be listed in `compat/gated-declarations.txt`, so it shows in the pull
  request; regenerate that file with
  `bash scripts/check-api-visibility.sh --list-gated > compat/gated-declarations.txt`.

## What is published

Every published module applies the `supabase-offline.published` convention plugin, which sets up
its Maven Central publication: the POM from the `POM_*` properties of `gradle.properties` (a
module's own `gradle.properties` can override `POM_NAME` and `POM_DESCRIPTION`), a sources jar, an
HTML reference of the public packages in the documentation jar, and signing.

CI publishes both modules to a repository inside the build directory and checks what Maven Central
would receive, with `scripts/check-published-artifacts.py`: every POM field Central requires, the
sources and documentation jars, the checksums, no absolute path of the machine that built them, and
the files, dependencies, klib names and Kotlin module files each artifact declares, against the
record `compat/published-artifacts.txt`. Run it locally the same way:

```bash
python3 scripts/check-published-artifacts.py
```

It fails when anything in the record changed. A dependency line there is what every consumer's
build resolves, so a change to it is a change to the library even when no source changed: a
dependency added or moved, a new target, a renamed module. If your change means to alter what is
published, rewrite the record with `python3 scripts/check-published-artifacts.py --update` and
commit it, and the reviewer reads its diff. A Dependabot update of a dependency the library
publishes (supabase-kt, Kotlin, Room, androidx.sqlite) needs the same, pushed to its branch.

## Releasing

Releases are published from GitHub Actions by the Release workflow, which a maintainer starts by
hand; nothing is published from a laptop. The steps, what the owner does in the Central Portal and
in the repository settings, and what to do when a release stops halfway are in
[RELEASING.md](https://github.com/nesfeder/supabase-offline-kt/blob/main/RELEASING.md). A pull
request prepares a release by setting `VERSION_NAME` without `-SNAPSHOT` and naming the changelog
section and the compatibility row after the version; the workflow refuses anything else.

## Coverage

CI measures test coverage with [Kover](https://github.com/Kotlin/kotlinx-kover) and sends the core
module's report to [Codecov](https://about.codecov.io/). The figure counts the common and Android
code as the Android host tests run it on the JVM; the iOS-only Kotlin, and whatever only the iOS
simulator tests exercise, cannot be in it, because Kotlin/Native has no coverage tooling.

To see the report for each file and line, run the following, then open
`supabase-offline-kt/build/reports/kover/html/index.html`:

```bash
./gradlew :supabase-offline-kt:koverHtmlReport
```

It runs the Android host tests first unless they are up to date.

`./gradlew check` also runs `koverVerify`, whose floor is set far below the real figure. It is
there to catch a measurement that has broken, as when a Kover release once reported no coverage at
all; an ordinary fall in coverage does not reach it.

Only generated classes are left out of the report (Room's output and the compiler's bridges for
interface default methods), in `build-logic`'s `supabase-offline.published` plugin; the test
doubles that ship in the artifacts are measured. Codecov's figure reads lower than Kover's,
because Codecov counts a line where only some branches ran as partly covered, not covered.

## Documentation site

The site is built with [MkDocs](https://www.mkdocs.org/) and
[Material for MkDocs](https://squidfunk.github.io/mkdocs-material/). Its pages are in `docs/`,
its configuration and navigation are in `mkdocs.yml`, and the exact toolchain versions are pinned
in `.github/workflows/docs-requirements.txt`.

### Build it locally

From the repository root, with Python 3.11 or later. On macOS the system `python3` is too old
for the pinned packages, so name a newer interpreter explicitly:

```bash
python3.11 -m venv .venv-docs
.venv-docs/bin/pip install -r .github/workflows/docs-requirements.txt
.venv-docs/bin/mkdocs build --strict
```

The site is written to `site/`. `--strict` turns every warning into a failure: a broken link or
anchor, a page missing from the navigation, a navigation entry with no page, or a missing
include. Pull requests that change the documentation run the same build, followed by a link
check.

To preview while you edit, run `.venv-docs/bin/mkdocs serve` and open
`http://127.0.0.1:8000/supabase-offline-kt/`. It rebuilds when you save; stop it with Ctrl+C.

Material for MkDocs prints a notice about MkDocs 2.0 on every run. Set
`NO_MKDOCS_2_WARNING=true` to hide it.

### Writing pages

- Every page must be listed under `nav` in `mkdocs.yml`.
- Some pages are pulled in from elsewhere in the repository when the site is built: this file,
  `CHANGELOG.md`, and the sample schema in
  `samples/supabase/migrations/20260930000000_offline_tasks.sql`. Edit those files, not the pages
  that include them. Links inside them must be absolute URLs, because they are resolved
  relative to the page that includes them.
- A page that has not been written yet opens with a "Not written yet" note, and a page written
  for the engine before 0.1.0 opens with a "Written for an earlier engine" note. Both are plain
  Markdown blockquotes, so they show on GitHub as well as on the site. Remove the note when the
  page is rewritten.

## Dependency updates

Dependabot proposes updates on Mondays: the Gradle build (the version catalog, the build scripts
and the wrapper) and GitHub Actions every week, the documentation toolchain every month. Updates
arrive grouped, once a release has been public for a week (supabase-kt patches as soon as they
are out). Nothing is merged automatically; each update is reviewed like any other pull request.
The configuration is
[`.github/dependabot.yml`](https://github.com/nesfeder/supabase-offline-kt/blob/main/.github/dependabot.yml).

### Held dependencies

The library is built and tested against one supabase-kt minor release at a time, and a few other
dependencies move with it. For these, Dependabot proposes patch releases only, and for the
pass-through set below, none at all. A new version is adopted by hand, in a pull request that
moves the coupled versions together.

| Dependency | Why it is held |
| --- | --- |
| supabase-kt | The library compiles supabase-kt's inline functions into its own artifact and matches exhaustively on its filter operators, so a minor release can change its behavior or break the build. |
| Ktor, okio, kotlinx.coroutines, kotlinx.serialization | The pass-through set, below. |
| Kotlin | The version the library is built with limits the Kotlin versions its consumers can use, and it has to work with the pinned supabase-kt. |
| Android Gradle plugin | It has to stay inside the Kotlin Gradle plugin's supported range. |
| Room, androidx.sqlite | Together they define the on-device database and the bundled SQLite, and Room is built against a particular androidx.sqlite. |
| SKIE | It supports exact Kotlin versions only, so Dependabot proposes no version updates for it; it moves with Kotlin. |

**The pass-through set.** Ktor, okio, kotlinx.coroutines and kotlinx.serialization are `api`
dependencies: every app that uses the library gets the versions this build names. The library
keeps each at the version the pinned supabase-kt release was built with, so an app gets the
combination supabase-kt was tested with, and a newer one here would be a newer one for every
consumer. Dependabot proposes no version update for them, patch releases included. They move by
hand, together with supabase-kt, in the pull request that moves the pin, and the owner decides
that pull request. A security update still arrives, because the rule that holds them covers
version updates only: Dependabot opens it as a security pull request, and the triage workflow
labels it `upstream-pin` and marks it as a security update. The owner decides it too: take the
fix alone, or move the supabase-kt pin instead.

Patch releases of the other held dependencies arrive in three groups: `upstream-pin`
(supabase-kt), `toolchain` and `persistence`. The Dependabot triage workflow gives each such pull
request the matching label and a checklist of what a passing build does not prove. It never
approves or merges. Two cases need care:

- A Kotlin `x.y.20` release is a tooling release, not a bug fix, although semver reads it as a
  patch. It arrives in the `toolchain` group like any other patch.
- Security updates are not held. Dependabot proposes a fix even when it needs a new minor or
  major version, or moves a pass-through dependency, and the triage workflow says so on the pull
  request.

A newer supabase-kt minor never arrives as a Dependabot version update. Instead, the
[Upstream canary](https://github.com/nesfeder/supabase-offline-kt/blob/main/.github/workflows/upstream-canary.yml)
workflow checks Maven Central every Monday. When the newest release is a newer minor or major, it
builds the library and runs its Android host tests against it, in its own checkout only, and
reports the result in one issue, which the pull request that moves the pin closes. Closing that
issue without moving the pin brings a new one the next Monday: to hold a minor back, leave the
issue open and say why in a comment. The `upstream-pin` label marks everything that would move
supabase-kt or the pass-through set: that issue, and Dependabot's pull requests for them.

Other Gradle dependencies arrive as grouped minor and patch updates, and a new major version as a
pull request of its own. ktfmt, the formatter, arrives alone in the `formatter` group, because
its update needs more than the version change: see "Updating ktfmt" under Formatting. GitHub
Actions arrive as one group, and so does the documentation toolchain, which holds MkDocs below 2:
MkDocs 2 removes the plugin and theme systems Material for MkDocs depends on.

### Security alerts

GitHub cannot read a Gradle build by itself. The Dependency submission workflow resolves the build
on every push to `main` that changes it and sends the resulting dependency graph to GitHub;
Dependabot alerts and security updates for Gradle dependencies are based on that graph. An alert
on a transitive dependency comes without a pull request: update the dependency that brings it in,
or add a dependency constraint.

Each package in the graph is labelled. It is `runtime` when a published module's shipping
classpath resolves it (the Android runtime classpath or an iOS target's klibs), and `development`
otherwise: Gradle plugins, build tools and test libraries. GitHub tags an alert on a development
package "Development". An alert without that tag concerns what the library ships, so read those
first (`scope:runtime` in the alerts filter). A new module whose output ships joins the project
pattern in the workflow's `resolve` step.

A development alert on a library that a Gradle plugin or one of its tools brings in, when no
release of the plugin fixes it yet, is closed with a **build-tool floor**: a `buildToolFloor-*`
library in `gradle/libs.versions.toml` at the patched version, with a comment naming the advisory
and the plugin release that will make it unnecessary. The build applies every floor as a
dependency constraint, which only ever raises a version, to the classpaths those plugins and tools
resolve: the root build script's, build-logic's, and each module's lint and Swift export tools,
the Kotlin Gradle plugin's publishing validation and Dokka's generator (the
`supabase-offline.build-tool-floors` convention plugin, which every role plugin applies).
Nothing that ships is affected. `python3 scripts/check-build-tool-floors.py` resolves those
classpaths, for every module Gradle lists, and fails when a floor does not hold, or when nothing
asks for less than a floor any more, which means a plugin release has made it unnecessary: remove
it then. A pass means the floors hold, not that they are patched versions: the check knows the
floors, not the advisories, so a floor lowered to a vulnerable version still passes it. The
floor's comment is what records the patched version, and the dependency graph's alerts catch a
vulnerable one. CI runs the check with the Android host tests. Dependabot proposes no version
update for a floor: a floor is the patched version the advisory names, not a tracker of the
latest release, and a version past the advisory can run a plugin on a library it was never built
with. A floor moves by hand when a new advisory names a higher version, or through a Dependabot
security update, which the `ignore` rules in `.github/dependabot.yml` do not block.

## Continuous integration

Every pull request runs `.github/workflows/ci.yml`. Its last job, **All Green**, is the one check
a pull request needs. Most failures are the change's own. This section covers two kinds that are
not.

### When a run fails on a download

A job can fail because something it downloads is briefly unavailable. Each of these is covered:

- **The formatter.** The Formatting job keeps kempt's release archive and the ktfmt jar in the
  Actions cache, keyed by their pinned SHA-256 digests, and downloads them only when the cache
  has no entry for those digests. A failed download is tried three more times, after 10, 30 and
  60 seconds, and each attempt is given 60 seconds. So the job fails only when all four attempts
  do: a `429 Too Many Requests` from Maven Central, a `5xx`, a server that stops answering, or a
  network error. One file gives up after at most 340 seconds, and the job's 15-minute timeout
  leaves room for both files to use every retry.
- **An action, before the first step.** The runner downloads every action a job uses in its "Set up
  job" step, on GitHub's runners and on a self-hosted one alike. That download is the runner's own,
  and nothing in the workflow controls it. Each attempt has 100 seconds, and the runner makes three
  attempts, 10 to 30 seconds apart, with a warning for each one that fails. After the third, the
  job fails with "Failed to download archive … after 3 attempts". That is at most about six minutes
  for each action; only if several of a job's actions stall at once can it reach the job's
  timeout, which is then a download failure too. A job whose only error is "The request was
  canceled due to the configured HttpClient.Timeout of 100 seconds elapsing" was cancelled while it
  waited, so the runner never retried: re-run it.
- **Other downloads in a step:** `setup-java`'s JDK, the Gradle wrapper's distribution, the build's
  dependencies and the documentation toolchain, when one of them fails with the same kinds of
  error.

What to do: wait ten minutes, then re-run the failed jobs once ("Re-run failed jobs" on the run's
page). If the same download fails again, stop and find out why: the file may have moved, or the
service may be down.

**Never re-run a failure that says a checksum did not match.** A file that arrives but does not
match its pinned SHA-256 is not a transient failure: either the file changed where it is published,
or something replaced it in transit. The job stops at once for that reason, without retrying and
without caching the file. Find out which it is before anything runs again. If a new release really
did replace the file, update the version and its digest together in `ci.yml`.

If the step that failed its checksum ran after "Restore the formatter downloads" said
`Cache restored`, the bad file came from the Actions cache, and a re-run restores it again. Delete
that entry first (the repository's Actions → Caches page, or `gh cache delete <key>` with the key
the restore step printed), then find out how it got there: the job saves the files only after both
have passed their checksums.

### Dependabot's pull requests and the `full-ci` label, while the repository is private

While the repository is private, CI holds the iOS tests on every run of a Dependabot pull request,
and its check reads `All Green (iOS tests held)`, which does not satisfy the required `All Green`.
To run them, a maintainer reads Dependabot's push and then adds the `full-ci` label: that labeled
event, and only that one, runs the whole graph, the iOS tests included. The label counts only when a
person adds it. When Dependabot pushes again, the check reads held again: read the new push, then
remove the label and add it again. Adding any other label runs nothing. Once the repository is
public, the iOS tests run on every pull request and the label is not needed.

### The self-hosted runner, while the repository is private

While the repository is private, the iOS tests may run on a self-hosted Mac instead of GitHub's
macOS runner (the rule is at the top of `ci.yml`). The job gives the build a home and directories of
its own, and its last two steps remove everything the job made and then compare the runner
account's home directory with a record taken before the first step. What that comparison records,
and what it cannot see, is in
[the snapshot's README](https://github.com/nesfeder/supabase-offline-kt/blob/main/.github/actions/home-snapshot/README.md).

**The comparison fails the job.** It lists each changed path, but it cannot tell whether the job
changed it or something else running as the same user did. If the path is not one the job writes,
re-run the job; if the same path comes back, it belongs in the snapshot's exclusion list, with its
reason.

One kind of red comes from the runners themselves, and is read, not excluded: a red that names
only a runner's directory with its new `bin.*`, `externals.*` or `update.finished` is the runner
updating itself, which its `_diag` log records. Re-run the job. (The two marker files a runner's
service rewrites daily, `.runner_migrated` and `.credentials_migrated`, are excluded: a job cannot
reconfigure a runner.)

**A job killed before its last two steps** (the Mac restarted or slept, or the runner service
stopped) cleans up nothing itself. What the next job on the runner does about it:

| Left behind | Removed by |
| --- | --- |
| The job's home, Gradle User Home, Kotlin/Native and temporary directories | The runner, which empties its temporary folder when the next job starts |
| Processes still running from those directories | The runner's orphan-process clean-up, and the next job's clean-up step |
| The JDK `setup-java` installed | The next job's clean-up step |
| The job's simulator, its logs in `~/Library/Logs/CoreSimulator`, and CoreSimulator's folders for it in the per-user temporary directory | The next job to start at least an hour later, which deletes simulators the CI jobs made that are older than that |
| Any change to the home directory | Nothing: the next job's record step lists what changed since the killed job's record, as a warning that names that run |

A maintainer then does two things by hand:

- Read that warning, and undo anything in it that the killed job wrote. It also lists what anything
  else on the machine changed in between, so compare it with what the job writes.
- Look in `~/Library/Logs/DiagnosticReports` for a crash report from a step that crashed, such as
  `xcodebuild`. The snapshot does not reach that deep, and nothing removes it.
