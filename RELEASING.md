# Releasing

How a maintainer releases supabase-offline-kt, step by step. What a release publishes, and what a
user can rely on, is on the site's
[Releasing](https://nesfeder.github.io/supabase-offline-kt/project/releasing/) page.

A release is one version number, `X.Y.Z`, published on every channel at once:

- **Maven Central**: `io.github.nesfeder.supabase-offline:supabase-offline-kt` and
  `io.github.nesfeder.supabase-offline:supabase-offline-kt-testing`, each a Kotlin Multiplatform
  artifact for Android, `iosArm64` and `iosSimulatorArm64`, signed, with sources and an HTML API
  reference.
- **Swift Package Manager**: the package `https://github.com/nesfeder/supabase-offline-swift`, with
  the products `SupabaseOffline` and `SupabaseOfflineTesting`: one commit per release, tagged
  `X.Y.Z`, holding `Package.swift`, `Sources/`, `README.md` and `LICENSE` and nothing else. Its
  manifest names the framework underneath, `SupabaseOfflineCore.xcframework.zip`, by URL and
  checksum, as an asset of this repository's GitHub Release.
- **GitHub**: the tag `X.Y.Z` (no `v`) and a GitHub Release whose notes are the changelog's section,
  followed by GitHub's list of the pull requests merged since the last release, grouped by label as
  `.github/release.yml` says, holding the Swift package's framework.
- **The documentation site**: `https://nesfeder.github.io/supabase-offline-kt/`, built from the
  release's tag, with the Kotlin API reference under `/api/kotlin/` and the Swift one under
  `/swift/`.

The version is `VERSION_NAME` in the root `gradle.properties`. Between releases it ends in
`-SNAPSHOT`; the pull request that prepares a release removes the suffix. Nothing else holds a
version: `swift/Package.swift` keeps `0.0.0` and a zero checksum, and the release renders the
published manifest with the version and the framework's checksum (`scripts/swift-release.py`).

The Swift package's source is `swift/` in this repository; the package's repository only ever
receives what a release publishes. What the published tree holds, and what the release refuses to
publish, is in `scripts/swift-release.py`'s description of `check-tree`: no tests, no
`swift/PARITY.md`, no local-framework switch, no test target, and no Kotlin, KMP, Konan or kfun
outside the code of `Sources/`.

## Before the first release

These are done once, by the repository's owner, in the Central Portal and the repository's
settings. The workflows never create or change any of them.

- [x] The namespace `io.github.nesfeder` is verified in the Central Portal.
- [x] A Central Portal user token exists. It has an expiry date: renew it before then and update
  the two secrets below.
- [x] A signing key exists, is published to the keyservers Central checks, and is backed up.
- [x] The environment `release` exists, restricted to `main`, with the secrets
  `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` (the user token), `GPG_KEY_CONTENTS` (the
  ASCII-armored secret key) and `SIGNING_PASSWORD` (its passphrase).
- [x] The repository `nesfeder/supabase-offline-swift` exists, empty, with one deploy key that has
  write access; its private half is the `release` environment's secret `SWIFT_PACKAGE_DEPLOY_KEY`.
  Only the step that pushes the Swift package reads it (see [Release](#3-release)).
- [x] GitHub Pages: **Settings → Pages → Source** is **GitHub Actions**, and the `github-pages`
  environment allows deployments from `main` only, where both release workflows and Docs republish
  run. The release's `docs` job publishes through it, and so does
  [Docs republish](#republish-the-site). A Pages site is public even while the repository is private.
- [ ] To publish snapshots, see [Snapshots](#snapshots).

### Going public

Before the first release whose Swift package anyone else should resolve, both repositories become
public: SwiftPM fetches the package and downloads the framework without signing in, so a package or
a release asset in a private repository cannot be resolved. The steps, in the order
`scripts/go-public.sh` gives:

- [ ] Remove every self-hosted runner from this repository (**Settings → Actions → Runners**): on
  a public repository, a fork's pull request can edit a workflow to run on one.
  `gh api repos/nesfeder/supabase-offline-kt/actions/runners --jq .total_count` prints `0`.
- [ ] Make both repositories public: `nesfeder/supabase-offline-swift`, then this one
  (**Settings → General → Danger Zone → Change visibility**).
- [ ] Run `scripts/go-public.sh`, which lists each setting GitHub offers only on a public
  repository with its value and its target, then `scripts/go-public.sh --apply`, which sets them
  and reads them back. It refuses while the repository is private or a runner is registered.
- [ ] Add the owner as the required reviewer of `release` (GitHub does not offer it on a private
  repository). From then on every release, and every resume, waits for that approval. Decide
  [snapshots](#snapshots) at the same time.

## 1. Prepare the release

In a pull request, like any other change:

1. In `gradle.properties`, set `VERSION_NAME` to the version, without `-SNAPSHOT`. The version must
   be large enough for what changed since the last release; see the compatibility policy
   (`docs/project/compatibility.md`).
2. In `CHANGELOG.md`, rename `## Unreleased` to `## [X.Y.Z] - YYYY-MM-DD` (the planned release
   date) and delete its empty headings. Leave no entry under an `## Unreleased` heading: the
   release checks that everything is in its section.
3. In `docs/project/compatibility.md`, rename the `Unreleased` row of the supabase-kt table to
   `X.Y.Z`. It must name the supabase-kt version the catalog pins.
4. Move every install snippet to `X.Y.Z` with `python3 scripts/release.py pin-snippets X.Y.Z`: the
   Maven coordinates, the catalog entry and the Swift package's `.upToNextMinor(from:)` in
   `README.md`, `swift/README.md`, the Swift package's documentation catalog and the site's pages.
   The release refuses a snippet left at the last release (step 5), since the site it builds from
   the tag, the Swift package's README and its API reference at that tag would all show it. Between
   releases the snippets name the version the samples take, and CI's docs job fails a pull request
   whose snippets name anything but that version or the release being prepared
   (`python3 scripts/release.py check-snippets`).
5. Commit, then run the release's own checks locally. Both read committed files only:

   ```bash
   python3 scripts/release.py check-version
   python3 scripts/api-compat.py check-release
   ```

   The first checks the version, the changelog, the compatibility row and the install snippets, and
   that no tag `X.Y.Z` exists (run `git fetch --tags` first). The second rates everything since the
   last release tag and fails when the version is too small for it.
6. Merge the pull request once `All Green` passes. Merge nothing else until the release is out: the
   release publishes `main` as it is when the workflow starts.

## 2. Rehearse: a dry run

Before the first release, and after any change to how the library or the Swift package is
published (the build logic, the publishing plugin, the release workflows, `scripts/release.py` or
`scripts/swift-release.py`), run a dry run. It builds and checks everything a release does, uploads
a real deployment to the Central Portal without releasing it, so Central's own validation of the
signatures, POMs and files runs, and keeps the Swift package's framework and tree as the run's
artifacts. Nothing is tagged, nothing is pushed, no GitHub Release is created, and nothing becomes
public.

1. **Actions → Release → Run workflow**, branch `main`, with **Dry run** ticked (the default).
2. The `verify` job runs the checks listed under [Release](#3-release), and then the `framework`
   job builds and tests the Swift package's framework on GitHub's macOS runner. When both pass,
   approve the `release` environment if GitHub asks.
3. The `publish` job stages and checks the Swift package, uploads the deployment and stops. Its
   summary names the deployment. (`draft` is skipped on a dry run.)
4. In the Central Portal, **Publishing → Deployments**, wait for **VALIDATED** and look through the
   files: for each of the two modules, four artifacts (the module itself, `-android`,
   `-iosarm64`, `-iossimulatorarm64`), each with its POM, sources jar, documentation jar,
   signatures and checksums.
5. Click **Drop**. A dropped deployment leaves nothing behind, and the version can still be
   released.
6. In the run's artifacts, `swift-framework` is the zip a release would attach (the `framework`
   job's summary gives its size and checksum), and `swift-package-tree` is exactly the tree the
   Swift package's repository would receive, with its rendered `Package.swift`.

**What a dry run costs.** Nothing: the repository is public, and GitHub's standard hosted
runners, macOS included, are free for a public repository. What it takes is time. The `framework`
job runs on GitHub's macOS runner from nothing (no cache is restored in a release): Gradle, the
Kotlin/Native toolchain, both iOS builds of the library and the bridge, two release links, then the
Swift package's build and tests on a simulator. It takes about 20 minutes of macOS, as it did in
the 0.1.0 release, beside about 20 Linux minutes; a real release takes the same plus Central's wait.

## 3. Release

1. **Actions → Release → Run workflow**, branch `main`, with **Dry run** cleared.
2. **verify** (Linux, about 12 minutes, no secrets, no side effects):
   - `scripts/release.py check-version`, as in step 1.5;
   - `scripts/api-compat.py check-release`, from a checkout with all history and tags;
   - `check` of both modules, less the iOS simulator suites, which need macOS and ran in the
     release commit's own CI;
   - `scripts/check-published-artifacts.py`, which publishes both modules to the build directory
     and checks every file Maven Central would receive.

   Its summary names the commit being released, the run's own commit. Keep that SHA for a resume.
   The later jobs check out that same commit themselves: nothing `verify` produces (it runs the
   build and the tests) reaches the job that signs or the job that tags.
3. **framework** (GitHub's macOS runner only, never a self-hosted one; no secret, no environment,
   read-only): builds the release XCFramework `SupabaseOfflineCore` once, from the run's commit, on
   Apple's SQLite, with the privacy manifest in each slice
   (`scripts/swift-release.py build`); checks its Objective-C header against the record
   (`scripts/check-bridge-header.py`) and its required-reason APIs against
   `supabase-offline-swift-bridge/privacy/required-reason-apis.txt`; zips it once with `ditto`
   (`swift-release.py zip`) and computes its checksum; runs the Swift package's tests on a
   simulator against the zip's own contents, with every warning an error, and the check that the
   package's public API names nothing of the framework (`scripts/test-swift-package.sh`); stages
   the tree the package's repository will hold and has SwiftPM read its manifest. Its summary gives
   the zip's size and checksum, and the zip is the run's artifact `swift-framework`. Nothing after
   this job builds the framework again.
4. **draft** (Linux, `contents: write` and nothing else, before the approval): creates the draft
   GitHub Release `X.Y.Z` at the released commit, with the changelog's section and the generated
   list of pull requests as its notes, holding the zip. A draft is not public and creates no tag;
   it keeps the zip where Release resume can take it, so a version's framework is never built
   twice. Run again, it does nothing when the draft holds exactly this zip, and it refuses a
   published release, a draft from another commit, or a draft holding another build.
5. Approve the `release` environment when GitHub asks.
6. **publish**, in this order:
   1. stages the Swift package's tree from the released commit's `swift/` and the zip, computing
      the checksum again, and checks it: if the tree cannot be published, the job stops here,
      before anything is public;
   2. uploads the signed artifacts, releases the deployment, and waits until Central says
      **PUBLISHED**, up to 45 minutes. This is the step that cannot be undone, so it goes first.
      It runs with no Gradle daemon and the Kotlin compiler in-process, so no process it started
      is still running when the next step writes the deploy key;
   3. pushes the Swift package to `nesfeder/supabase-offline-swift`: a commit on top of its `main`
      by `github-actions[bot]`, "Release X.Y.Z", whose tree is exactly the staged one, and the
      lightweight tag `X.Y.Z` on it, in one atomic push that never forces. This step alone reads
      `SWIFT_PACKAGE_DEPLOY_KEY`: it writes the key to a file under `RUNNER_TEMP` with mode 600,
      connects with host-key checking on against GitHub's host keys pinned in
      `scripts/swift-release.py`, and the next step deletes the file whatever happened. If the tag
      already holds exactly this tree it does nothing; if it holds anything else it stops. `main`
      moves only when `X.Y.Z` is the highest version there.
7. **finish** pushes this repository's tag `X.Y.Z` at the released commit, publishes the draft
   GitHub Release for that tag (from here on the framework's URL, which the Swift package's
   manifest names, can be downloaded), and pushes the post-release branch,
   `release/X.Y.Z-next-snapshot`, committed as `github-actions[bot]`. Its summary has the link that
   opens that branch's pull request: GitHub Actions may not open pull requests in this repository
   ("Allow GitHub Actions to create and approve pull requests" is off, and stays off), so you open
   it.
8. **docs** builds the documentation site from the tag `X.Y.Z` and deploys it to GitHub Pages
   (`.github/workflows/docs-publish.yml`). It holds the Pages and OIDC permissions only, and no
   release secret. The site's API reference page (`/api/`) leads to the two references. The Kotlin
   one under `/api/kotlin/` is built by that workflow itself, with Dokka, from the commit it checked
   out (`./gradlew :dokkaGenerate`, written to `build/dokka/html`), here the tag. The Swift one
   under `/swift/` is the one the framework job built with DocC against the framework it tested
   (`scripts/build-swift-docs.sh`), handed over as the run's `swift-reference` artifact; the docs
   job only places it, with a page at `/swift/` itself that opens the package's page.

So the order in which things become public is: Maven Central, then the Swift package's tag, then
this repository's tag and its GitHub Release with the framework, then the site. Between the Swift
package's tag and the GitHub Release, the package exists but its framework's URL does not answer
yet; finish closes that gap a minute later, or a resume does.

**The framework is not code-signed.** Apple requires a signature only from the SDKs on
its list of commonly used third-party SDKs, which this is not. Signing needs a certificate in the
`framework` job, which would give a secret to the job that runs the build and add a second
approval. A signature can be added in a later release: Xcode accepts a framework that gains a
signature, but refuses one that loses it or changes signer, so once signed, every later release
must be signed by the same identity.

## 4. After the release

1. Check that both artifacts resolve from
   `https://repo1.maven.org/maven2/io/github/nesfeder/supabase-offline/` at `X.Y.Z`. The
   Central Portal's search can take a few more hours.
2. Check the GitHub Release: its notes, that it is not a draft, and that it holds
   `SupabaseOfflineCore.xcframework.zip` with the checksum the `framework` job's summary gave.
3. Check the Swift package resolves: in a new Xcode project (an iOS app), **File → Add Package
   Dependencies…**, enter `https://github.com/nesfeder/supabase-offline-swift`, choose **Exact
   Version** `X.Y.Z`, add the `SupabaseOffline` product, `import SupabaseOffline`, and build for a
   simulator and for **Any iOS Device**. Xcode downloads the framework from the GitHub Release and
   checks its checksum; a checksum or URL error here means the manifest and the asset disagree
   (see below). The package's repository shows the commit "Release X.Y.Z" with the tag `X.Y.Z`.
4. Check the documentation site shows the release (`https://nesfeder.github.io/supabase-offline-kt/`).
   To publish a later change to the documentation before the next release, see
   [Republish the site](#republish-the-site).
5. Open the post-release pull request with the link in `finish`'s summary; its title and
   description are filled in, and CI starts by itself because you opened it. It sets
   `VERSION_NAME` to the next minor's `-SNAPSHOT`, adds an empty `## Unreleased` section to the
   changelog and an `Unreleased` row to the compatibility table, and empties
   `compat/overrides.txt`. It also moves the sample apps to `X.Y.Z`, since they take the published
   library, not this checkout: `supabase-offline` in `gradle/libs.versions.toml` for the Android
   sample, and for the iOS sample the project's exact version of `supabase-offline-swift` with its
   pin, the tag's commit, in the project's `Package.resolved`. It would move the install snippets
   to `X.Y.Z` too, as a safety net: the release refused any left behind (step 1.4), so it finds
   none to move. CI builds both samples on the checkout, so check them as a reader gets them:
   `./gradlew :samples:android:assembleDebug` without `-PsupabaseOffline.local=true`, and
   `samples/ios/OfflineTasks.xcodeproj` (the project, not the workspace) resolving the package at
   `X.Y.Z`. Review it and merge it.
6. If the next release is a patch, set that version in the pull request that prepares it.

## Republish the site

A release publishes the site from its tag. To publish a change to the documentation before the next
release, run **Docs republish** (`.github/workflows/docs-republish.yml`). It builds the site from
`main`, the Kotlin API reference under `/api/kotlin/` included, and places under `/swift/` the
Swift API reference a Release run built; **Docs publish**, run by hand, builds the site and the
Kotlin reference from the branch it runs on, has no Swift reference and leaves `/swift/` out.

1. Find the newest release's run: **Actions → Release**, the newest run whose `finish` job ran (a
   dry run skips it), and take the number at the end of its URL (`…/actions/runs/<id>`). For 0.2.0
   it is `39426991583`. Use the newest release's: an older run would put an older version's Swift
   API reference on the site.
2. **Actions → Docs republish → Run workflow**, branch `main`, with that number as
   `release_run_id`.

Before it downloads anything, the `fetch` job reads the run with its own token, which can only read
Actions data, and stops with an error naming each check that failed. The run must be this
repository's, of the Release workflow (`release.yml`), started by hand on `main` from this
repository's own commit (a fork's pull request can upload an artifact of the same name from a run of
its own), successful, and a real release, not a dry run: its `finish` job ran. The job then copies
that run's `swift-reference` artifact into its own run, and its summary names the commit the
reference was built from. The `publish` job calls `docs-publish.yml` as the release's `docs` job
does, with the Pages and OIDC permissions only and no secret, but with no ref, so the site is built
from `main` as it was when the run started.

The Kotlin API reference is `main`'s, as the pages are: a change to a KDoc comment on `main` reaches
`/api/kotlin/` with the republish. The Swift API reference is the release's, not `main`'s: a change
to the Swift package's documentation reaches `/swift/` with the next release. A Release run keeps its `swift-reference`
artifact for 90 days; after that, a republish fails at the download, and `/swift/` comes back with
the next release.

## When a release stops halfway

Look at the run's summary, and at the deployment in the Central Portal.

| Where it stopped | What is public | What it leaves behind | What to do |
| --- | --- | --- | --- |
| `verify` | Nothing | Nothing | Fix the cause in a pull request, merge, and run Release again. |
| `framework` | Nothing | Nothing (the job's logs) | Fix the cause in a pull request, merge, and run Release again. |
| `draft` | Nothing | Possibly a draft release `X.Y.Z` | Re-run the failed job (it takes the same zip, and does nothing when the draft holds it). If it refuses a draft holding another build or from another commit, delete that draft (**Releases → the draft → Delete**) and re-run. |
| Approval rejected, or `publish` before Central, or deployment **FAILED** or none | Nothing | The draft release `X.Y.Z`, holding this run's framework; possibly a failed deployment | For a passing cause (a timeout, an outage), re-run the failed jobs of the same run, which keeps its framework and its draft. Otherwise drop a failed deployment, **delete the draft release**, fix the cause, and run Release again: a new run builds its own framework, and its `draft` job refuses the old draft. |
| `publish`, deployment **PUBLISHING** or **PUBLISHED** (the wait timed out, or the runner stopped) | The Maven artifacts, once PUBLISHED | The draft release | Wait for PUBLISHED, then run **Release resume** with the commit SHA from `verify`'s summary. |
| `publish`, after PUBLISHED (the Swift package's push failed) | The Maven artifacts | The draft release; the Swift package's repository unchanged (the push is atomic) | Fix the cause (the deploy key, the repository), then run **Release resume** with the SHA. |
| `finish` | The Maven artifacts, the Swift package and its tag, and whatever `finish` did before it stopped | The draft release, if `finish` stopped before publishing it | Run **Release resume** with the SHA. |
| `docs` | Everything but the new site | Nothing | Re-run the failed `docs` job, which builds the tag; or run **Release resume** with the SHA, which publishes the site at the tag too. |

**Release resume** (**Actions → Release resume → Run workflow**, on `main`) takes that SHA. It
checks that the commit is on `main`, that its `VERSION_NAME` is the release version with its
changelog section, that no tag of that version points anywhere else, and that Maven Central serves
every artifact of it. Then, in the order a release makes things public:

1. it downloads the framework from the GitHub Release `X.Y.Z` (the draft, or the release if
   `finish` had already published it), checking that the release was staged from that SHA;
2. it stages the Swift package from that SHA's `swift/` and `LICENSE`, read with `git archive` as
   data, and that zip, computing the checksum again, and pushes it with the deploy key exactly as
   `publish` does; a tag that already holds this tree is left alone;
3. it pushes this repository's tag, publishes the draft GitHub Release, and pushes the
   post-release branch, each only if missing; its summary has the pull request's link;
4. it publishes the documentation site from the tag.

A resume has no macOS job, so the site it publishes has no Swift API reference: `/swift/` is left
out, and the docs job's summary says so. A publish of the site started by hand with **Docs publish**
leaves it out in the same way. [Docs republish](#republish-the-site) puts it back from a release's
run that succeeded; a resumed release's run did not, so after a resume `/swift/` comes back with the
next release.

It runs `main`'s scripts, never the input commit's, and never runs Gradle, builds the framework or
publishes to Maven Central: once Central has published a version, nothing may be rebuilt under that
number, because the rebuilt files would differ from the published ones, and the Swift package's
checksum pins the framework's bytes. It waits for the same approval as a release once the
environment has a reviewer; until then, as for a release, starting it is the approval. Of the
environment's secrets it reads the deploy key alone, in the step that pushes the Swift package.

If the draft release is gone (deleted by hand after Central published), the resume refuses: the
Swift package's checksum must be the one of the framework that was built. Recreate the draft from
the Release run's `swift-framework` artifact (kept 90 days), with
`python3 scripts/swift-release.py draft-release --zip <the zip> --version X.Y.Z --sha <SHA>` run
with a token that can write this repository's releases, then run the resume. If the artifact has
expired too and the Swift package's tag does not exist yet, the version cannot be finished on
Swift: skip it there, as below.

**Never run Release again for a version Central has published.** Central refuses it anyway, and a
second build of the framework would not match the first. Never move or delete a tag that has been
pushed, in this repository or in the Swift package's.

## A defect found after a release

Nothing published can be withdrawn: Central keeps every version, and Swift Package Manager keeps
the tags people have pinned (`Package.resolved` records the tag's commit). Add a warning to that
GitHub Release's notes, and release `X.Y.Z+1` with the fix.

**Every channel carries the same versions.** If a version is published on one channel and cannot
be published on another, skip that number everywhere and release the next one; never publish
different contents under one number.

## Snapshots

A snapshot is `main` at a `-SNAPSHOT` version, on Central's snapshot repository
(`https://central.sonatype.com/repository/maven-snapshots/`), kept for about 90 days. The `snapshot`
job in `ci.yml` publishes one after the Android host tests pass on each push to `main`, when both
of these hold:

- the repository variable `PUBLISH_SNAPSHOTS` is `on` (it does not exist yet, so the job is skipped);
- `VERSION_NAME` ends in `-SNAPSHOT` (a release version is published by Release alone).

Two steps for the owner turn it on:

1. In the Central Portal, **Publishing → Namespaces**, open `io.github.nesfeder` and choose
   **Enable SNAPSHOTs**.
2. Create the repository variable `PUBLISH_SNAPSHOTS` with the value `on`.

The job uses the secrets of the `release` environment, which has no required reviewer while the
repository is private. When the owner adds that reviewer, every push to `main` would wait for an
approval. The planned way out is an environment `snapshot`, restricted to `main`, with the same four
secrets and no reviewer, to which the `snapshot` job moves: its `environment:` line in `ci.yml`, and
its entry in the release rule of `.github/scripts/check-workflows.py`, change in one pull request.

**What that costs the approval.** A `snapshot` environment with no reviewer gives every push to
`main` the Portal user token and the signing key, with no approval. The token can publish and
release any version in the namespace, so a change merged to `main` that edits `ci.yml` could use
it to release, and the release approval would then guard against mistakes in a run, not against
what is merged. The alternatives: leave snapshots off (the default, since `PUBLISH_SNAPSHOTS` does
not exist), or give the `snapshot` environment a reviewer too and approve each snapshot. This is
the owner's decision at the public flip; the environment and the variable are the owner's to
create.

There are no Swift package snapshots.

## What a run costs

Each job starts from nothing: no cache is restored in a publishing workflow, so the build runs
only what the released commit holds. None of it is billed: the repository is public, and GitHub's
standard hosted runners, Linux and macOS alike, cost nothing for a public repository.

| Run | Linux minutes | macOS minutes |
| --- | --- | --- |
| Dry run | about 20: `verify` about 12, `publish` about 7 | about 20 (`framework`) |
| Release | about 27 plus Central's wait for PUBLISHED, usually 5 to 30; `docs` about 2 | 20 (`framework`, measured in the 0.1.0 release) |
| Release resume | about 3, with `docs` | none |
| Docs republish | about 3 | none |
| Snapshot (a push to `main`) | about 7, beside CI's own run | none |
