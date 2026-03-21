## RadioDroid v2.4.0

### Highlights
- **Main channel list — radio-specific fields** — Driver extras and group summary now show in a **multi-column block** that reflows with screen width (narrow = stacked; wide = more columns). Items fill **top-to-bottom in each column**, then the next column (column-major order).
- **Channel editor — no duplicate Busy Lock** — When the driver already exposes Busy Lock under **Radio-specific settings** (e.g. nicFW H3 `busyLock` in Memory.extra), the legacy standalone Busy Lock row (with help) is hidden. Duplex rules still apply to the driver’s switch.

### Design
- Layout notes: [docs/design/channel_card_radio_specific_layout.md](docs/design/channel_card_radio_specific_layout.md).

### Install
Download **`app-release.apk`** and install on Android 7.0+. See also **`userguide.md`** (Markdown) or the PDF **User Guide** asset attached to this release.

### Full context
See [README](https://github.com/jnyer27/RadioDroid/blob/main/README.md) on the repository for features and architecture.
