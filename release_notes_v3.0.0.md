## RadioDroid v3.0.0

### Highlights

- **Major: channel extras only** — Legacy **Group 1–4** fields are removed from the universal channel model; radio-specific options live in **Memory.extra** only (schema-driven spinners/switches in the channel editor and main list). **Bulk edit** can update one extra field across selected channels using the driver schema.
- **Busy lock / BCL** — Shown only when the driver exposes it as a **Memory.extra** field (no duplicate universal “Busy Lock” row). Help text trimmed accordingly.
- **Clone backup import** — When a JSON backup includes **`eeprom_base64`**, per-channel **`extra`** from the file is merged into the decoded channel list and the in-memory EEPROM is **re-synced** through the driver so extras (e.g. RT85 **b_lock**) round-trip with the image.
- **Clone mmap sync (no vendor driver edits)** — `chirp_bridge` rebuilds **Memory.extra** templates via the driver’s existing **`_get_memory`** when a slot would otherwise have no RadioSetting list, so export / Save-to-radio can encode JSON extras without patching upstream CHIRP drivers.
- **Export Raw EEPROM** — Uses the same **progress bar and rotating status messages** as **Export Radio Backup** while the clone image is synced.

### Install

Download **`app-release.apk`** and install on Android 7.0+.

### User guide

- **Online (MkDocs):** https://jnyer27.github.io/RadioDroid/
- **PDF:** file **`radiodroid-v3.0.0-userguide.pdf`** in Assets (uploaded by the *Update User Guide* workflow), or live at https://jnyer27.github.io/RadioDroid/user-guide.pdf
- **Source:** [`userguide.md`](https://github.com/jnyer27/RadioDroid/blob/main/userguide.md) in the repo

### Full context

[README](https://github.com/jnyer27/RadioDroid/blob/main/README.md) — features and architecture.

<!-- radiodroid-docs:userguide -->
