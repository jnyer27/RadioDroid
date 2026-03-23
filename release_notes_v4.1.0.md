## RadioDroid v4.1.0

### Highlights

- **Maintainers — CHIRP submodule drift** — Adds a **Cursor agent skill** (`.cursor/skills/radiodroid-chirp-submodule`) that documents the **fork → push → bump pointer** workflow, **`git submodule update`** after pull, and why **BLE channel decode** follows the **Python CHIRP driver**, not Kotlin helpers. Helps avoid stale checkout SHAs that mis-show tones or driver behavior.
- **Documentation** — **MkDocs** landing page **`docs/index.md`** **Current release** line updated to match the shipped app version (it had lagged behind).

### Install

Download **`app-release.apk`** and install on Android 7.0+. See also **`userguide.md`** (Markdown) or the PDF **User Guide** asset when present.

### Full context

See [README](https://github.com/jnyer27/RadioDroid/blob/main/README.md) on the repository for features and architecture.
