---
name: radiodroid-userguide-mkdocs
description: Refreshes the RadioDroid MkDocs site (GitHub Pages), PDF export, and latest-release PDF asset; documents schematic ASCII-art UI diagrams in the user guide. Use when updating userguide.md or docs, adding UI wireframe blocks, forcing a docs redeploy, or refreshing the online user guide or user-guide.pdf.
---

> **Claude Code users:** see `.claude/commands/userguide.md` — kept in sync with this skill file.

# RadioDroid user guide — MkDocs, Pages, and PDF

## Source of truth

- **Canonical:** repo root **`userguide.md`**. The MkDocs hook (`docs/hooks.py`) copies it to **`docs/index.md`** on every build (that file is gitignored—edit **`userguide.md`** only).
- Keep **`docs/UserGuide.md`** aligned with **`userguide.md`** when both exist (same project convention as the Android release skill). After editing **`userguide.md`**, copy it: `cp userguide.md docs/UserGuide.md` (or equivalent on Windows).

## Schematic UI blocks (not screenshots)

The guide uses **ASCII art in fenced code blocks** so diagrams are human-readable on GitHub, on the MkDocs site, and in plain-text editors — without requiring CSS or screenshots.

**Do not use HTML `<div class="ui-mock …">` blocks.** The old CSS-based wireframes (`docs/stylesheets/ui-mock.css`) are no longer used for schematics and caused raw/unstyled HTML to appear when viewing the file directly on GitHub (issue #3).

### Schematic format

Each schematic follows this pattern:

````markdown
### Schematic: <name>

```
┌──────────────────────────────────────────┐
│ ← Screen title                           │
├──────────────────────────────────────────┤
│  Field label  │ Value / [pill]           │
│  Field label  │ Value / [pill]           │
├──────────────────────────────────────────┤
│ [Cancel]                      [Done]     │
└──────────────────────────────────────────┘
```

*Schematic. Brief description of what is shown.*
````

**Common conventions:**
- `←` for back arrow, `⋮` for overflow menu icon
- `[Button]` for action buttons, `[Pill]` for dropdown/chip values
- `▒` on a button label = disabled state
- `▓▓▓░░░` for an indeterminate progress bar
- `▼ Section title` for collapsible groups
- Use `├──┬──┤` / `├──┴──┤` for column separators within a table layout
- Caption line is plain italic Markdown: `*Schematic. …*`

## What gets published

| Output | Where |
|--------|--------|
| Static site | **GitHub Pages** → `https://jnyer27.github.io/RadioDroid/` |
| PDF | **`site/user-guide.pdf`** during build; CI uploads a copy to the **latest GitHub Release** as `radiodroid-<tag>-userguide.pdf` (display name **User Guide (PDF)**) |

## Preferred: force full refresh via Actions (matches production)

Use this when you want the **online site**, **PDF build**, and **release PDF asset** updated without relying on path-filtered pushes.

From the repo root (requires **`gh`** authenticated for `jnyer27/RadioDroid`):

```bash
gh workflow run "Update User Guide" --ref main
gh run list --workflow "Update User Guide" --limit 1
gh run watch <RUN_ID> --exit-status
```

Workflow file: **`.github/workflows/update-userguide.yml`**. It runs **`mkdocs build`**, then **`mkdocs gh-deploy --force`**, then uploads the PDF to **all published releases** (backfill). It also appends MkDocs/PDF links to each release's notes once (idempotent marker: `radiodroid-docs:userguide`).

## Automatic runs (no manual dispatch)

The workflow runs automatically on:

1. **`release: [published]`** — a new GitHub Release is published. PDF is attached to that release only.
2. **Push to `main`** when any of these change:
   - `userguide.md`
   - `docs/**`
   - `mkdocs.yml`
   - `requirements-docs.txt`
   - `.github/workflows/update-userguide.yml`

   On a push run, the PDF is **backfilled onto all published releases**, not just the latest.

If you only change files outside those paths (e.g. Android app only), the site does **not** rebuild until you dispatch manually or touch a watched path.

## Local preview or dry-run build

```bash
pip install -r requirements-docs.txt
python -m playwright install --with-deps chromium   # Linux/macOS; on Windows often: python -m playwright install chromium
python -m mkdocs serve    # preview at http://127.0.0.1:8000
# or
python -m mkdocs build    # writes site/ and site/user-guide.pdf
```

Local **`mkdocs gh-deploy`** is possible but needs git credentials and a clean **`gh-pages`** push; prefer **Actions** for the canonical deploy.

## After an app release

When **`userguide.md`** / **`docs/UserGuide.md`** include the new **Current release:** line, merge to **`main`** and either wait for the path-triggered workflow or run **`gh workflow run "Update User Guide"`** so Pages and the **latest** release’s PDF stay in sync.

## Coordination with Android releases

For version bumps, APK, and GitHub Release titles, follow **`.cursor/skills/radiodroid-release/SKILL.md`**. After doc edits for that release, apply **this** skill to refresh **Pages + PDF**.
