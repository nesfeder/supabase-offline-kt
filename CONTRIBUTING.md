# Contributing

Thank you for helping. Instructions for building, testing and releasing the library itself will
follow; for now, this file covers the documentation site and how dependency updates are handled.

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
  `CHANGELOG.md`, and the sample schema in `demo/supabase/schema.sql`. Edit those files, not the
  pages that include them. Links inside them must be absolute URLs, because they are resolved
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
dependencies move with it. For these, Dependabot proposes patch releases only. A new minor or
major version is adopted by hand, in a pull request that moves the coupled versions together.

| Dependency | Why it is held |
| --- | --- |
| supabase-kt | The library compiles supabase-kt's inline functions into its own artifact and matches exhaustively on its filter operators, so a minor release can change its behavior or break the build. |
| Ktor, kotlinx.coroutines, kotlinx.serialization | They are `api` dependencies, kept at the versions supabase-kt is built against. A newer version here is a newer version for every consumer. |
| Kotlin | The version the library is built with limits the Kotlin versions its consumers can use, and it has to work with the pinned supabase-kt. |
| Android Gradle plugin | It has to stay inside the Kotlin Gradle plugin's supported range. |
| Room, androidx.sqlite | Together they define the on-device database and the bundled SQLite, and Room is built against a particular androidx.sqlite. |
| SKIE | It supports exact Kotlin versions only, so Dependabot proposes no version updates for it; it moves with Kotlin. |

Patch releases of the held dependencies arrive in three groups: `upstream-pin`, `toolchain` and
`persistence`. The Dependabot triage workflow gives each such pull request the matching label and
a checklist of what a passing build does not prove. It never approves or merges. Two cases need
care:

- A Kotlin `x.y.20` release is a tooling release, not a bug fix, although semver reads it as a
  patch. It arrives in the `toolchain` group like any other patch.
- Security updates are not held. Dependabot proposes a fix even when it needs a new minor or
  major version, and the triage workflow says so on the pull request.

Other Gradle dependencies arrive as grouped minor and patch updates, and a new major version as a
pull request of its own. GitHub Actions arrive as one group, and so does the documentation
toolchain, which holds MkDocs below 2: MkDocs 2 removes the plugin and theme systems Material for
MkDocs depends on.

### Security alerts

GitHub cannot read a Gradle build by itself. The Dependency submission workflow resolves the build
on every push to `main` that changes it and sends the resulting dependency graph to GitHub;
Dependabot alerts and security updates for Gradle dependencies are based on that graph. An alert
on a transitive dependency comes without a pull request: update the dependency that brings it in,
or add a dependency constraint.
