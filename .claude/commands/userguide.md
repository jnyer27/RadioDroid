# RadioDroid user guide — MkDocs, Pages, and PDF

## Source of truth

- **Canonical source:** repo root **`userguide.md`**. The MkDocs hook (`docs/hooks.py`) copies it to `docs/index.md` on every build (that file is gitignored — edit `userguide.md` only).
- Keep **`docs/UserGuide.md`** in sync with `userguide.md` whenever both exist. After editing `userguide.md`:
  ```bash
  cp userguide.md docs/UserGuide.md
  ```

## Schematic UI blocks (not screenshots)

The guide uses **ASCII art in fenced code blocks** — human-readable on GitHub, in MkDocs, and in plain-text editors. Do **not** use HTML `<div class="ui-mock …">` blocks (causes raw unstyled HTML on GitHub — issue #3).

### Schematic format

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

**Conventions:**
- `←` back arrow, `⋮` overflow menu
- `[Button]` action buttons, `[Pill]` dropdown/chip values
- `▒` on a label = disabled state
- `▓▓▓░░░` indeterminate progress bar
- `▼ Section title` collapsible group
- `├──┬──┤` / `├──┴──┤` column separators

## What gets published

| Output | Where |
|--------|--------|
| Static site | GitHub Pages → `https://jnyer27.github.io/RadioDroid/` |
| PDF | `site/user-guide.pdf` during build; CI uploads to each GitHub Release as `radiodroid-<tag>-userguide.pdf` (label **User Guide (PDF)**) |

## Trigger a full refresh via Actions (preferred)

```bash
gh workflow run "Update User Guide" --ref main
gh run list --workflow "Update User Guide" --limit 1
gh run watch <RUN_ID> --exit-status
```

Workflow: **`.github/workflows/update-userguide.yml`** — runs `mkdocs build`, `mkdocs gh-deploy --force`, uploads PDF to all published releases (backfill), appends MkDocs/PDF links to release notes (idempotent marker: `radiodroid-docs:userguide`).

## Automatic triggers (no manual dispatch needed)

1. **`release: [published]`** — new GitHub Release published; PDF attached to that release.
2. **Push to `main`** when any of these paths change:
   - `userguide.md`
   - `docs/**`
   - `mkdocs.yml`
   - `requirements-docs.txt`
   - `.github/workflows/update-userguide.yml`

If only app code changed (no docs paths), dispatch manually or touch a watched path.

## Local preview

```bash
pip install -r requirements-docs.txt
python -m playwright install chromium
python -m mkdocs serve      # preview at http://127.0.0.1:8000
# or
python -m mkdocs build      # writes site/ and site/user-guide.pdf
```

Prefer Actions for the canonical deploy; local `mkdocs gh-deploy` requires git credentials.

## Coordination with releases

For version bumps, APK, and GitHub Release titles follow `.claude/commands/release.md`. After doc edits, apply this command to refresh Pages + PDF.
