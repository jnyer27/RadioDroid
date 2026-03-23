## RadioDroid v4.0.0

### Highlights

- **TD-H3 nicFW 2.5 — driver validation & mmap safety** — **`validate_memory`** enforces **NFM/NAM** vs **Radio Specific bandwidth** (no **Wide** with narrow modes). Python **`chirp_bridge`** runs the same checks in **`apply_channel_to_mmap`** before any EEPROM write; **`validate_channel_dict`** exposes errors/warnings to the app. **Channel editor** and **bulk bandwidth** edits block invalid combinations with clear dialogs.
- **DCS tone decode/encode** — Firmware stores **DTCS as an index** into **`ALL_DTCS_CODES`** (e.g. index **21** → **025**), not the CHIRP literal **21** (**021**). Decode and encode now match the radio so untouched **025N** channels round-trip correctly.
- **RX frequency validation (H3)** — **`valid_bands`** is a single **50–600 MHz** span aligned with nicFW Programmer hardware RX capability (finer RX-only sub-ranges left commented in the driver for future tightening). Fixes **airband** and other gaps that previously failed **“frequency out of supported ranges”** on save.
- **Customize main screen defaults** — New installs default to **Mode** then **Duplex** (better match for **NFM/NAM** without redundant bandwidth vs mode).

### Install

Download **`app-release.apk`** and install on Android 7.0+. See also **`userguide.md`** (Markdown) or the PDF **User Guide** asset when present.

### Full context

See [README](https://github.com/jnyer27/RadioDroid/blob/main/README.md) on the repository for features and architecture.
