## RadioDroid v4.3.0

### Highlights

- **Bluetooth LE reconnect** — When the radio or adapter drops the link, the app now tears down the GATT session and LocalSocket bridge cleanly, shows a **connection lost** message, and avoids crashes or stuck state from stale callbacks. Each BLE session uses a **unique** abstract socket name so a quick reconnect no longer hits **address already in use**.
- **USB serial (e.g. FTDI) reconnect** — Same **unique socket names** and **close-before-open** behavior for the USB OTG path, fixing **port in use** / open failures after disconnect or rapid reconnect.

### Install

Download **`app-release.apk`** and install on Android 7.0+. See also **`userguide.md`** (Markdown) or the PDF **User Guide** asset when present. The guide is also at **[jnyer27.github.io/RadioDroid](https://jnyer27.github.io/RadioDroid/)**.

### Full context

See [README](https://github.com/jnyer27/RadioDroid/blob/main/README.md) on the repository for features and architecture.
