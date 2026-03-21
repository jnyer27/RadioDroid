# Squash `gh-pages` (Option A)

Optional maintenance: replace **`gh-pages`** with a **single orphan commit** of the built MkDocs site. See **`.cursor/skills/radiodroid-gh-pages-cleanup/SKILL.md`** for full steps, warnings, and bash/PowerShell commands.

**Summary:** `python -m mkdocs build` → `cd site` → `git init -b gh-pages` → add all → commit → `git remote add origin <url>` → `git push -f origin gh-pages` → remove `site/.git`, rebuild `site/` if needed.

Related: `.claude/commands/userguide.md`, `.cursor/skills/radiodroid-userguide-mkdocs/SKILL.md`.
