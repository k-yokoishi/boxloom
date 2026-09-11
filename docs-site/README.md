# boxloom documentation site

The public boxloom documentation is built with Fumadocs and exported as a static Next.js site.

From the repository root:

```bash
mise run docs-install
mise run docs-dev
```

Build the static site with:

```bash
mise run docs-build
```

The output is written to `docs-site/out`. GitHub Pages builds with `DOCS_BASE_PATH=/boxloom`; local development runs at the domain root.
