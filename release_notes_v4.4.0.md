## RadioDroid v4.4.0

### Highlights

- **RepeaterBook (CHIRP-style)** — Search and import repeaters from the RepeaterBook API; proximity and filters similar to desktop CHIRP.
- **GMRS tones** — GMRS detail pages that hide PL/TSQ behind login can still get tones via authenticated **export.php** (`stype=gmrs`) when your API token is configured; DCS strings map correctly for import.
- **CHIRP import** — Preview and import path polish (preview chips, driver power handling).
- **Bluetooth LE** — If Bluetooth is **off**, the app prompts to **turn it on** before scanning; clearer than an empty device list.
- **Quality** — Large set of Android Lint-driven fixes (i18n for dynamic strings, dead resources removed, RecyclerView update patterns, minSdk-correct manifest/GATT setup); **usb-serial** dependency moved into the Gradle version catalog.

### Install

Download **`app-release.apk`** and install on Android 7.0+. See also **`userguide.md`** (Markdown) or the PDF **User Guide** asset when present. The guide is also at **[jnyer27.github.io/RadioDroid](https://jnyer27.github.io/RadioDroid/)**.

### Full context

See [README](https://github.com/jnyer27/RadioDroid/blob/main/README.md) on the repository for features and architecture.
