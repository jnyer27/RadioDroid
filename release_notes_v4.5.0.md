## RadioDroid v4.5.0

### Highlights

- **TSQL / CTCSS** — Channels in **TSQL** mode no longer show **None** for tones in the list or editor; the app treats **TSQL** like **Tone** for Hz display and spinner mapping (download, EEPROM/backup, CSV).
- **NICFW TD-H3** — After **split_tone_decode**, CHIRP leaves **rtone** at the default **88.5 Hz** while the real tone is in **ctone**; the bridge now surfaces **ctone** for transmit in that case so **TX matches RX** when both subtones are the same.
- **Other radios** — **Cross** Tone→Tone memories where TX CTCSS is still the CHIRP default but RX is programmed are normalized for display; saving maps **Tone+Tone** back to **TSQL** when the driver supports it.

### Install

Download **`app-release.apk`** and install on Android 7.0+. See also **`userguide.md`** (Markdown) or the PDF **User Guide** asset when present. The guide is also at **[jnyer27.github.io/RadioDroid](https://jnyer27.github.io/RadioDroid/)**.

### Full context

See [README](https://github.com/jnyer27/RadioDroid/blob/main/README.md) on the repository for features and architecture.
