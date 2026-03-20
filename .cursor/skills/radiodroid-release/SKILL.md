---
name: radiodroid-release
description: Standard RadioDroid Android release workflow—version bump, release_notes file format, README/user guide, git tag, signed APK, and GitHub Release via gh. Use when cutting a release, publishing to GitHub Releases, or when the user mentions version bumps, release notes, or tagging.
---

# RadioDroid release process

Follow this checklist so **markdown notes**, **GitHub Release titles**, and **git tags** stay consistent with **v2.1.0** and later.

## Conventions (do not drift)

| Artifact | Format | Example |
|----------|--------|---------|
| **Git tag** | `v` + semver | `v2.2.0` |
| **GitHub Release title** | **Semver only** (no `RadioDroid` prefix) | `v2.2.0` |
| **Release notes file** | Repo root: `release_notes_vX.Y.Z.md` | `release_notes_v2.2.0.md` |
| **First heading in that file** | `## RadioDroid vX.Y.Z` | `## RadioDroid v2.2.0` |
| **Annotated tag message** | Short product line | `RadioDroid v2.2.0 — [one-line summary]` |

**Why:** Older GitHub releases (e.g. v2.1.0) use the **short title** `vX.Y.Z`. A title like `RadioDroid v2.2.0` does not match that pattern—use **`gh release create ... --title "v2.2.0"`** (semver only).

## Release notes file template

Create or update `release_notes_vX.Y.Z.md` at the **repository root** using this structure (match `release_notes_v2.1.md`):

```markdown
## RadioDroid vX.Y.Z

### Highlights
- **Feature name** — One line; use bold for the feature label.
- **Documentation** — If docs/README/user guide changed, say what.

### Install
Download **`app-release.apk`** and install on Android 7.0+. See also **`userguide.md`** (Markdown) or the PDF **User Guide** asset when present.

### Full context
See [README](https://github.com/jnyer27/RadioDroid/blob/main/README.md) on the repository for features and architecture.
```

Optional: add a second link in **Install** to the online user guide, as in v2.2—keep **Highlights / Install / Full context** section names stable.

## Version bump

In `AndroidRadioDroid/app/build.gradle.kts`:

- Increment **`versionCode`** by 1.
- Set **`versionName`** to semver string **`X.Y.Z`** (no `v` prefix in Gradle).

## Docs and README

1. **`README.md`** — Add a **What’s new in vX.Y.Z** block at the top of the changelog area; move the previous version under **Earlier:**. Update the line that cites the latest release APK example if present.
2. **`docs/UserGuide.md`** and **`userguide.md`** — Update the **Current release** line near the top to **`vX.Y.Z`** (keep both files in sync if both exist).

## Git workflow

1. Commit all release changes on a feature branch (or directly on `main` if that’s the project norm).
2. Merge to **`main`** when ready.
3. Create **annotated** tag:  
   `git tag -a vX.Y.Z -m "RadioDroid vX.Y.Z — [summary]"`
4. Push: `git push origin main` and `git push origin vX.Y.Z`

## Signed APK

1. Ensure `AndroidRadioDroid/app/keystore.properties` exists locally (gitignored); `storeFile` must point to a real keystore.
2. From `AndroidRadioDroid/`: `./gradlew assembleRelease` (Windows: `gradlew assembleRelease`).
3. Artifact: `AndroidRadioDroid/app/build/outputs/apk/release/app-release.apk`

## GitHub Release (gh CLI)

Use **title = semver only**; body from the release notes file:

```bash
cd /path/to/RadioDroid
gh release create vX.Y.Z \
  --repo jnyer27/RadioDroid \
  --title "vX.Y.Z" \
  --notes-file release_notes_vX.Y.Z.md \
  AndroidRadioDroid/app/build/outputs/apk/release/app-release.apk \
  --latest
```

**Re-upload APK only** (same tag):

```bash
gh release upload vX.Y.Z AndroidRadioDroid/app/build/outputs/apk/release/app-release.apk \
  --repo jnyer27/RadioDroid --clobber
```

**Fix an existing release title** (e.g. if it was published as `RadioDroid v2.2.0`):

```bash
gh release edit vX.Y.Z --repo jnyer27/RadioDroid --title "vX.Y.Z"
```

## Quick checklist

- [ ] `release_notes_vX.Y.Z.md` with `## RadioDroid vX.Y.Z` and **Highlights / Install / Full context**
- [ ] `versionCode` / `versionName` in `app/build.gradle.kts`
- [ ] README + both user guides updated
- [ ] `main` + tag `vX.Y.Z` pushed
- [ ] `assembleRelease` succeeds
- [ ] `gh release create` with **`--title "vX.Y.Z"`** and APK attached
