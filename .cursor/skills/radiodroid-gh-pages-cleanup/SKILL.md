---
name: radiodroid-gh-pages-cleanup
description: Squashes GitHub Pages branch (gh-pages) to a single orphan commit for RadioDroid — optional maintenance when deploy history is large. Use when the user wants to clean gh-pages, reduce clone size, or reset Pages history (Option A).
---

# RadioDroid — squash `gh-pages` (Option A)

`mkdocs gh-deploy` appends commits to **`gh-pages`**. Over time that branch accumulates many “site deploy” commits. The live site is always the **latest** commit; old commits are optional history. This skill describes **Option A**: replace **`gh-pages`** with a **single fresh commit** containing the current built site (orphan branch + force push).

## When to use

- **`git fetch` / clone** feels slow and you want a smaller **`gh-pages`** history.
- You want **`gh-pages`** to be **one commit** for clarity (easier to inspect in GitHub UI).
- **Not required** for correctness — skip if the repo is fine.

## When **not** to use

- Right before a critical demo without a follow-up deploy path (force push replaces remote history; brief risk if something else referenced old SHAs — rare).
- If you are unsure you have a working **`main`** + MkDocs setup to rebuild the site.

## Prerequisites

- **Python + MkDocs** deps as in **`.cursor/skills/radiodroid-userguide-mkdocs/SKILL.md`** (`requirements-docs.txt`, Playwright for PDF if you build PDF too).
- **`userguide.md`** on **`main`** is the source; hook copies it to `docs/index.md` on build.
- You can **force-push** to **`origin gh-pages`** (`contents: write` / repo admin).

## Safety checks

1. Confirm **GitHub Pages** source is **`gh-pages`** branch (Settings → Pages).
2. Run **`mkdocs build`** from repo root and spot-check **`site/index.html`** (or `mkdocs serve` locally).
3. Prefer doing this from a **clean** working tree on **`main`** (`git status` clean).

## Procedure (Linux / macOS / Git Bash)

From the **repository root** (where `mkdocs.yml` and `userguide.md` live):

```bash
set -euo pipefail
cd /path/to/RadioDroid
python -m mkdocs build
test -f site/index.html

cd site
git init -b gh-pages
git add .
git commit -m "docs: publish site (squashed gh-pages)"
git remote add origin "$(git -C .. remote get-url origin)"
git push -f origin gh-pages
cd ..
```

Remove the nested **`site/.git`** after push (or delete **`site/`** and run **`mkdocs build`** again) so your working tree stays normal:

```bash
rm -rf site/.git
```

## Procedure (PowerShell, repo root)

`cd` to the **RadioDroid repo root** first (folder containing `mkdocs.yml`).

```powershell
python -m mkdocs build
if (-not (Test-Path "site\index.html")) { throw "mkdocs build failed or site/ missing" }

$repoUrl = git remote get-url origin
$parent = Get-Location
$tmp = New-Item -ItemType Directory -Path ([System.IO.Path]::GetTempPath()) -Name ("rd-gh-" + [guid]::NewGuid().ToString("n"))
Set-Location $tmp
New-Item -ItemType Directory -Name publish | Out-Null
Copy-Item -Path (Join-Path $parent "site\*") -Destination "publish" -Recurse -Force
Set-Location publish
git init -b gh-pages
git add .
git commit -m "docs: publish site (squashed gh-pages)"
git remote add origin $repoUrl
git push -f origin gh-pages
Set-Location $parent
Remove-Item -Recurse -Force $tmp
```

If **`site/`** now contains a **`.git`** folder, delete **`site/.git`** (and optionally `site/`) so your next `mkdocs build` is clean:

```powershell
Remove-Item -Recurse -Force site\.git -ErrorAction SilentlyContinue
```

## After cleanup

- Wait **1–2 minutes** for GitHub Pages to rebuild from the new tip.
- The next **Update User Guide** workflow run will again add commits on top of this squashed base — that’s normal. Repeat this skill **occasionally** if history grows again.
- **Do not merge** `gh-pages` into `main` or vice versa.

## Related

- **MkDocs deploy (canonical):** `.cursor/skills/radiodroid-userguide-mkdocs/SKILL.md`
- **Releases + `main` as source of truth:** `.cursor/skills/radiodroid-release/SKILL.md`
