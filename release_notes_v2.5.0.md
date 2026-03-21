## RadioDroid v2.5.0

### Highlights

- **Radio backup JSON — smaller files** — **Export Radio Backup** writes each setting as **`path` + `value` only** (no huge CHIRP `options` lists). Import already matched this shape; export now does too. Same portable backup workflow; files are much smaller on clone radios with large setting trees.
- **Backup / raw EEPROM filenames** — Default export names start with a filesystem-safe **`Vendor_Model`** prefix and a timestamp (e.g. `TIDRADIO_TD_H3_nicFW_2_5_radiodroid_backup_20260321_143022.json`).
- **Clone backup import — settings + image** — When a JSON backup includes **`eeprom_base64`**, RadioDroid loads the image **and** applies **`settings`** from the file into the mmap (same path as **Radio Settings → Save**) before decoding channels. Export/import round-trips no longer ignore JSON settings in favor of a stale image alone.
- **Export Radio Backup — reliability** — Progress text while the clone EEPROM is synced to the channel list; toasts if export can’t start; **`Throwable`** caught so hard failures surface in a message instead of failing silently. **NICFW H3 (TD-H3 nicFW 2.5)** — additional driver fixes so backup export doesn’t crash on bitmask / DCS edge cases (`tidradio_h3_nicfw25` in the bundled CHIRP tree).
- **Lint / Play hygiene** — BLE write status compared with **`BluetoothStatusCodes.SUCCESS`**; manifest adds **`ACCESS_COARSE_LOCATION`** alongside fine (pre-31) for lint **CoarseFineLocation**.

### Developers

- **CHIRP submodule** now points at the RadioDroid-maintained fork **`jnyer27/chirp`** (see [docs/CHIRP_SUBMODULE.md](docs/CHIRP_SUBMODULE.md)). Clone with **`git clone --recurse-submodules`**.

### Install

Download **`app-release.apk`** and install on Android 7.0+.

### User guide

- **Online (MkDocs):** https://jnyer27.github.io/RadioDroid/
- **PDF:** file **`radiodroid-v2.5.0-userguide.pdf`** in Assets (uploaded by the *Update User Guide* workflow), or live at https://jnyer27.github.io/RadioDroid/user-guide.pdf
- **Source:** [`userguide.md`](https://github.com/jnyer27/RadioDroid/blob/main/userguide.md) in the repo

### Full context

[README](https://github.com/jnyer27/RadioDroid/blob/main/README.md) — features and architecture.

<!-- radiodroid-docs:userguide -->
