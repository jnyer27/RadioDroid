## RadioDroid v3.3.0

### Highlights

- **NICFW TD-H3 2.5 (CHIRP driver)** — Narrow band is represented in **`Memory.mode`** as **`NFM`** / **`NAM`**, matching **`chirp_common.MODES`**, with correct EEPROM modulation + bandwidth round-trip. **`valid_modes`** includes those labels; **`Memory.extra` bandwidth** stays in sync with the firmware narrow flag.
- **CHIRP CSV import/export** — Header and columns follow bundled **`chirp_common.Memory.CSV_FORMAT`** (including **`RxDtcsCode`** and **`CrossMode`** before **`Mode`**). Frequencies use CHIRP-style formatting; **Mode** accepts the full **`MODES`** set (including **NFM** / **NAM**). **Cross** + **DTCS→DTCS** rows map to split DCS tones; power **`0`** re-imports as **N/T** for round-trip with our export.
- **CHIRP submodule** — Bundled fork updated to include the NICFW driver changes ([jnyer27/chirp](https://github.com/jnyer27/chirp)).

### Install

Download **`app-release.apk`** and install on Android 7.0+.

### User guide

- **Online (MkDocs):** https://jnyer27.github.io/RadioDroid/
- **PDF:** file **`radiodroid-v3.3.0-userguide.pdf`** in Assets (uploaded by the *Update User Guide* workflow after release), or live at https://jnyer27.github.io/RadioDroid/user-guide.pdf
- **Source:** [`userguide.md`](https://github.com/jnyer27/RadioDroid/blob/main/userguide.md) in the repo

### Full context

[README](https://github.com/jnyer27/RadioDroid/blob/main/README.md) — features and architecture.

<!-- radiodroid-docs:userguide -->
