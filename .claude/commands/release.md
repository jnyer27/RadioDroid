# RadioDroid release process

Follow this checklist so **markdown notes**, **GitHub Release titles**, and **git tags** stay consistent with v2.1.0 and later.

## Conventions (do not drift)

| Artifact | Format | Example |
|----------|--------|---------|
| **Git tag** | `v` + semver | `v2.3.0` |
| **GitHub Release title** | **Semver only** (no `RadioDroid` prefix) | `v2.3.0` |
| **Release notes file** | Repo root: `release_notes_vX.Y.Z.md` | `release_notes_v2.3.0.md` |
| **First heading in that file** | `## RadioDroid vX.Y.Z` | `## RadioDroid v2.3.0` |
| **Annotated tag message** | Short product line | `RadioDroid v2.3.0 — [one-line summary]` |

**Why:** Older releases (e.g. v2.1.0) use the short title `vX.Y.Z`. A title like `RadioDroid v2.2.0` does not match — always use `--title "vX.Y.Z"` (semver only).

## Release notes file template

Create `release_notes_vX.Y.Z.md` at the **repository root**:

```markdown
## RadioDroid vX.Y.Z

### Highlights
- **Feature name** — One line; bold the feature label.
- **Documentation** — If docs/README/user guide changed, say what.

### Install
Download **`app-release.apk`** and install on Android 7.0+. See also **`userguide.md`** (Markdown) or the PDF **User Guide** asset attached to this release.

### Full context
See [README](https://github.com/jnyer27/RadioDroid/blob/main/README.md) on the repository for features and architecture.
```

Keep **Highlights / Install / Full context** section names stable.

## Version bump

In `AndroidRadioDroid/app/build.gradle.kts`:

- Increment **`versionCode`** by 1.
- Set **`versionName`** to semver string `X.Y.Z` (no `v` prefix in Gradle).

## Docs and README

1. **`README.md`** — Add a **What's new in vX.Y.Z** block at the top of the changelog; move the previous version under **Earlier:**. Update any line citing the latest APK if present.
2. **`userguide.md`** and **`docs/UserGuide.md`** — Update the **Current release:** line to `**Current release: vX.Y.Z**` where **`vX.Y.Z`** matches **`versionName`** in Gradle (`2.5.0` → `v2.5.0`). **Commit this in the same push to `main` as the Gradle bump *before* `gh release create`**, or the **Update User Guide** workflow (triggered by the release) will deploy stale text to GitHub Pages. CI fails the release workflow if they mismatch.

## Git workflow

1. Commit all release changes directly on `main` (project norm).
2. Create **annotated** tag:
   ```bash
   git tag -a vX.Y.Z -m "RadioDroid vX.Y.Z — [one-line summary]"
   ```
3. Push:
   ```bash
   git push origin main
   git push origin vX.Y.Z
   ```

## Signed APK

1. Ensure `AndroidRadioDroid/app/keystore.properties` exists locally (gitignored). `storeFile` must point to a real keystore.
2. From `AndroidRadioDroid/`: `./gradlew assembleRelease`
3. Artifact: `AndroidRadioDroid/app/build/outputs/apk/release/app-release.apk`

## GitHub Release (gh CLI)

**Title = semver only; no label on the APK; use `--notes-file`:**

```bash
gh release create vX.Y.Z \
  --title "vX.Y.Z" \
  --notes-file release_notes_vX.Y.Z.md \
  --latest \
  AndroidRadioDroid/app/build/outputs/apk/release/app-release.apk
```

Do **not** add a `#label` suffix to the APK path — prior releases have no label on `app-release.apk`.

**Re-upload APK only** (same tag):
```bash
gh release upload vX.Y.Z AndroidRadioDroid/app/build/outputs/apk/release/app-release.apk --clobber
```

**Fix an existing release title:**
```bash
gh release edit vX.Y.Z --title "vX.Y.Z"
```

## After the release

The **Update User Guide** workflow runs automatically on **`release: [published]`** (and on pushes to watched doc paths). It builds from **`main`**; **`userguide.md` must already match `versionName`** or the release-triggered run **fails** the new verify step.

Manual re-run after fixing docs:
```bash
gh workflow run "Update User Guide" --ref main
```

See `.claude/commands/userguide.md` for full MkDocs/PDF details.

## Quick checklist

- [ ] `release_notes_vX.Y.Z.md` with `## RadioDroid vX.Y.Z` and **Highlights / Install / Full context**
- [ ] `versionCode` / `versionName` bumped in `app/build.gradle.kts`
- [ ] README `What's new` block added; previous version moved to `Earlier:`
- [ ] `**Current release: vX.Y.Z**` updated in both `userguide.md` and `docs/UserGuide.md`
- [ ] All changes committed on `main`
- [ ] Annotated tag `vX.Y.Z` created and pushed
- [ ] `./gradlew assembleRelease` succeeds with signing (no `app-release-unsigned.apk`)
- [ ] `gh release create` with `--title "vX.Y.Z"` (no `RadioDroid` prefix), `--latest`, APK attached without a label
- [ ] User guide workflow triggered (or confirmed auto-triggered)
