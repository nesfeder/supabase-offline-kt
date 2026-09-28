# Contributing

Thank you for helping. Instructions for building, testing and releasing the library itself will
follow; for now, this file covers the documentation site.

## Documentation site

The site is built with [MkDocs](https://www.mkdocs.org/) and
[Material for MkDocs](https://squidfunk.github.io/mkdocs-material/). Its pages are in `docs/`,
its configuration and navigation are in `mkdocs.yml`, and the exact toolchain versions are pinned
in `.github/workflows/docs-requirements.txt`.

### Build it locally

From the repository root, with Python 3.11 or later:

```bash
python3 -m venv .venv-docs
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
- A page that has not been written yet includes `docs/.snippets/not-written-yet.md`. A page
  written for the engine before 0.1.0 includes `docs/.snippets/legacy-notice.md`. Remove the
  include when the page is rewritten.
