## RadioDroid v2.3.0

### Highlights
- **Channel edits now persist to EEPROM** — fixed a `TypeError` in the nicFW H3 CHIRP driver (`set_memory`) where `MemoryMapBytes.__getitem__` returns `bytes` but was used directly in bitwise ops; channel edits now survive export and re-import.
- **Export Raw EEPROM re-enabled after JSON import** — `ChannelEditActivity` was silently overwriting the in-memory EEPROM with an empty array on every channel open; now only updates when non-empty bytes are provided.
- **Channel editor spinners/switches restored after JSON import** — clone-mode drivers (e.g. nicFW H3) need a loaded EEPROM to return the channel-extra schema (spinners, switches); the existing in-memory EEPROM is now preserved for schema introspection even when importing a JSON backup without embedded EEPROM bytes.
- **DCS tone encoding corrected** — fixed octal/binary mismatch in nicFW H3 `_decode_tone`/`_encode_tone`.

### Install
Download **`app-release.apk`** and install on Android 7.0+. See also **`userguide.md`** (Markdown) or the PDF **User Guide** asset attached to this release.

### Full context
See [README](https://github.com/jnyer27/RadioDroid/blob/main/README.md) on the repository for features and architecture.
