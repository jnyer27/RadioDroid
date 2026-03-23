## RadioDroid v4.2.0

### Highlights

- **Channel list — Select all from search** — Opening the main screen **search** bar now shows **Select all** immediately (not only after you type). With an empty query it selects every **non-empty** channel; with a filter it selects all **visible matches** (same bulk actions as before: move, export, etc.).
- **Busy Lock / bool extras** — **`chirp_bridge`** correctly applies **`Memory.extra`** boolean fields when JSON carries the strings **`"True"`** / **`"False"`** (Python **`bool("False")`** was wrongly true), fixing **Busy Lock** and any other bool radio-specific field on save/upload.
- **Vendored CHIRP** — The Python CHIRP tree is **part of this repository** (no Git submodule). One clone and one release cover the app and drivers; optional workflow for the **[jnyer27/chirp](https://github.com/jnyer27/chirp)** fork is documented in [docs/CHIRP_SUBMODULE.md](docs/CHIRP_SUBMODULE.md).

### Install

Download **`app-release.apk`** and install on Android 7.0+. See also **`userguide.md`** (Markdown) or the PDF **User Guide** asset when present.

### Full context

See [README](https://github.com/jnyer27/RadioDroid/blob/main/README.md) on the repository for features and architecture.
