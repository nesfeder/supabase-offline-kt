# Releasing

How a maintainer releases supabase-offline-kt, step by step. What a release publishes, and what a
user can rely on, is on the site's
[Releasing](https://nesfeder.github.io/supabase-offline-kt/project/releasing/) page.

A release is one version number, `X.Y.Z`, published on every channel at once:

- **Maven Central**: `io.github.nesfeder.supabase-offline:supabase-offline-kt` and
  `io.github.nesfeder.supabase-offline:supabase-offline-kt-testing`, each a Kotlin Multiplatform
  artifact for Android, `iosArm64` and `iosSimulatorArm64`, signed, with sources and an HTML API
  reference.
- **GitHub**: the tag `X.Y.Z` (no `v`) and a GitHub Release whose notes are the changelog's section,
  followed by GitHub's list of the pull requests merged since the last release, grouped by label as
  `.github/release.yml` says.
- **Swift Package Manager**: not set up yet. The same workflow will publish the Swift package when
  its distribution exists; until then a release publishes the two channels above.

The version is `VERSION_NAME` in the root `gradle.properties`. Between releases it ends in
`-SNAPSHOT`; the pull request that prepares a release removes the suffix. Nothing else holds a
version.

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
- [ ] When the repository becomes public, add the owner as the required reviewer of `release`
  (GitHub does not offer it on a private repository). From then on every release, and every
  resume, waits for that approval. Do the snapshot step below at the same time.
- [ ] To publish snapshots, see [Snapshots](#snapshots).

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
4. Commit, then run the release's own checks locally. Both read committed files only:

   ```bash
   python3 scripts/release.py check-version
   python3 scripts/api-compat.py check-release
   ```

   The first checks the version, the changelog and the compatibility row, and that no tag `X.Y.Z`
   exists (run `git fetch --tags` first). The second rates everything since the last release tag
   and fails when the version is too small for it.
5. Merge the pull request once `All Green` passes. Merge nothing else until the release is out: the
   release publishes `main` as it is when the workflow starts.

## 2. Rehearse: a dry run

Before the first release, and after any change to how the library is published (the build logic,
the publishing plugin, the release workflow), run a dry run. It uploads a real deployment to the
Central Portal without releasing it, so Central's own validation of the signatures, POMs and
files runs, and nothing becomes public.

1. **Actions → Release → Run workflow**, branch `main`, with **Dry run** ticked (the default).
2. The `verify` job runs the checks listed under [Release](#3-release). When it passes, approve
   the `release` environment if GitHub asks.
3. The `publish` job uploads the deployment and stops. Its summary names the deployment.
4. In the Central Portal, **Publishing → Deployments**, wait for **VALIDATED** and look through the
   files: for each of the two modules, four artifacts (the module itself, `-android`,
   `-iosarm64`, `-iossimulatorarm64`), each with its POM, sources jar, documentation jar,
   signatures and checksums.
5. Click **Drop**. A dropped deployment leaves nothing behind, and the version can still be
   released.

## 3. Release

1. **Actions → Release → Run workflow**, branch `main`, with **Dry run** cleared.
2. **verify** (about 12 minutes, no secrets, no side effects):
   - `scripts/release.py check-version`, as in step 1.4;
   - `scripts/api-compat.py check-release`, from a checkout with all history and tags;
   - `check` of both modules, less the iOS simulator suites, which need macOS and ran in the
     release commit's own CI;
   - `scripts/check-published-artifacts.py`, which publishes both modules to the build directory
     and checks every file Maven Central would receive.

   Its summary names the commit being released, the run's own commit. Keep that SHA for a resume.
   The later jobs check out that same commit themselves: nothing `verify` produces (it runs the
   build and the tests) reaches the job that signs or the job that tags.
3. Approve the `release` environment when GitHub asks.
4. **publish** uploads the signed artifacts, releases the deployment, and waits until Central
   says **PUBLISHED**, up to 45 minutes.
5. **finish** pushes the tag `X.Y.Z` at the released commit, creates the GitHub Release from the
   changelog's section and the list of merged pull requests, and pushes the post-release branch,
   `release/X.Y.Z-next-snapshot`, committed as `github-actions[bot]`. Its summary has the link that
   opens that branch's pull request: GitHub Actions may not open pull requests in this repository
   ("Allow GitHub Actions to create and approve pull requests" is off, and stays off), so you open
   it.

## 4. After the release

1. Check that both artifacts resolve from
   `https://repo1.maven.org/maven2/io/github/nesfeder/supabase-offline/` at `X.Y.Z`. The
   Central Portal's search can take a few more hours.
2. Check the GitHub Release: its notes, and that it is not a draft.
3. Open the post-release pull request with the link in `finish`'s summary; its title and
   description are filled in, and CI starts by itself because you opened it. It sets
   `VERSION_NAME` to the next minor's `-SNAPSHOT`, adds an empty `## Unreleased` section to the
   changelog and an `Unreleased` row to the compatibility table, and empties
   `compat/overrides.txt`. Review it and merge it.
4. If the next release is a patch, set that version in the pull request that prepares it.

## When a release stops halfway

Look at the run's summary, and at the deployment in the Central Portal.

| Where it stopped | What is public | What to do |
| --- | --- | --- |
| `verify` | Nothing | Fix the cause in a pull request, merge, and run Release again. |
| `publish`, deployment **FAILED** or none | Nothing | Drop a failed deployment, fix the cause, and run Release again. |
| `publish`, deployment **PUBLISHING** or **PUBLISHED** (the wait timed out, or the runner stopped) | The Maven artifacts, once PUBLISHED | Wait for PUBLISHED, then run **Release resume** with the commit SHA from `verify`'s summary. |
| `finish` | The Maven artifacts, and whatever `finish` did before it stopped | Run **Release resume** with the commit SHA from `verify`'s summary. |

**Release resume** (**Actions → Release resume → Run workflow**, on `main`) takes that SHA. It
checks that the commit is on `main`, that its `VERSION_NAME` is the release version with its
changelog section, that no tag of that version points anywhere else, and that Maven Central serves
every artifact of it; then it does whichever of the tag, the GitHub Release and the post-release
branch is missing, and its summary has the pull request's link. It waits for the same approval as a
release once the environment has a reviewer; until then, as for a release, starting it is the
approval. It never builds and never
publishes: once Central has published a version, nothing may be rebuilt under that number, because
the rebuilt files would differ from the published ones.

**Never run Release again for a version Central has published.** Central refuses it anyway, and a
second build of the Swift package would not match the first. Never move or delete a tag that has
been pushed.

## A defect found after a release

Nothing published can be withdrawn: Central keeps every version, and Swift Package Manager keeps
the tags people have pinned. Add a warning to that GitHub Release's notes, and release `X.Y.Z+1`
with the fix.

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
only what the released commit holds.

| Run | Linux minutes |
| --- | --- |
| Dry run | about 20: `verify` about 12, `publish` about 7 |
| Release | about 25 plus Central's wait for PUBLISHED, usually 5 to 30 |
| Release resume | about 1 |
| Snapshot (a push to `main`) | about 7, beside CI's own run |
