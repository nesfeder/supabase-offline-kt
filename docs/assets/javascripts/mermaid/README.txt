Mermaid, vendored for the documentation site
============================================

mermaid.min.js is Mermaid 11.17.2, unmodified: dist/mermaid.min.js from the npm package.

  Source:        https://registry.npmjs.org/mermaid/-/mermaid-11.17.2.tgz
  npm integrity: sha512-V6K3C8EBdEsPFZXSKMJe6ppQOENxuHARr9GvHX4hh47lAbhMRD9qf4oEK7LoaRQxULMa80/qt5gHO73aCleBBg==
  SHA-256:       see mermaid.min.js.sha256 (checked by .github/workflows/docs.yml)
  License:       MIT, see LICENSE in this folder

mkdocs.yml loads it through extra_javascript. Material for MkDocs uses a global `mermaid` when
one is defined and only otherwise fetches mermaid@11 from unpkg.com, so this copy keeps every
page free of third-party script requests.

Stay on the 11.x line that Material for MkDocs 9.7 was built against. To update: download the
new tarball, check it against the integrity value npm publishes for it, replace mermaid.min.js
and LICENSE, regenerate mermaid.min.js.sha256 (shasum -a 256 mermaid.min.js), and update this
file.
