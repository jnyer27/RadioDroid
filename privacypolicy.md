# RadioDroid Privacy Policy

<p align="center">
  <img src="docs/assets/radiodroid-logo.png" alt="RadioDroid Chirp Programmer logo" width="220" />
</p>

**Effective date:** March 22, 2026  

**App:** RadioDroid — Android CHIRP-compatible radio programmer (`com.radiodroid.app`)  

**Open-source repository:** [github.com/jnyer27/RadioDroid](https://github.com/jnyer27/RadioDroid)

This policy describes how RadioDroid handles information based on **how the app is built and behaves today**. RadioDroid is **local-first**: it runs CHIRP radio drivers on your device (via Chaquopy) and talks to your radio over **USB OTG**, **Bluetooth Low Energy**, or **Bluetooth serial** when you choose to connect. It does **not** require a user account and does **not** include third-party analytics or advertising SDKs in the official open-source build.

---

## Summary

| Topic | Practice |
|--------|----------|
| **Servers operated by the app** | The app does **not** send your channel lists, EEPROM images, or radio settings to RadioDroid developers. There is **no `INTERNET` permission** in the official manifest for routine operation. |
| **Analytics / crash reporting** | **Not embedded** in the project as shipped from this repository (no Firebase, Crashlytics, or similar in the app module). |
| **Where your data lives** | Channel data, clone images, backups, and preferences stay **on the device** unless **you** export, share, or back them up (e.g. CSV, JSON, file save, Android share sheet). |
| **Radio connections** | Data is exchanged **between your phone/tablet and your radio** (or BLE adapter) over USB/BT — not routed through RadioDroid’s servers (there are none). |

If you use an **unofficial or modified build**, privacy practices may differ; verify with whoever provided the APK.

---

## Information the app processes (on your device)

RadioDroid may **store and display** on-device:

- **Channel memories** — frequencies, names, tones, modes, power, and driver-specific fields (`Memory.extra`) as supported by the selected CHIRP driver.
- **Radio EEPROM / clone images** — for “clone-mode” radios, a binary image may be held in memory for programming and backup flows.
- **Radio settings** — values exposed by the driver’s settings UI (e.g. squelch, display options), when applicable.
- **Preferences** — e.g. last selected radio model/vendor/baud (via Android `SharedPreferences`) so the app can restore your last choice.
- **Optional files you create** — CHIRP CSV exports, JSON backups, EEPROM dumps — only where **you** choose to save or share them.

Processing is performed **locally** to operate the app (edit channels, program the radio, import/export files you select).

---

## Bluetooth and USB

- **Bluetooth (BLE and classic serial):** Used to discover/connect to adapters and transfer programming data to the radio. Pairing, scan, and connection behavior follow **Android’s Bluetooth APIs** and the permissions you grant (see the app manifest: Bluetooth scan/connect; on older APIs, location may be required for BLE scan — **maxSdkVersion 30** for legacy location permissions).
- **USB OTG:** Used to communicate with USB-serial radios or cables attached to the device. Optional auto-launch when a matching USB device is attached is configured in the manifest; you can still connect from within the app.

**Identifiers:** Bluetooth device **names and MAC addresses** may appear in the UI when you scan or connect. They are used **locally** for connection; they are **not** uploaded to RadioDroid by the official app.

---

## Network / internet

The **official RadioDroid app** from this repository does **not** declare general `INTERNET` permission for core programming features. Networking is **not** used for:

- Syncing channels to a cloud,
- Logging in,
- Telemetry to the developer.

**When you might use the internet anyway (outside the app’s control):**

- **Downloading the APK** or reading documentation from **GitHub** or **GitHub Pages** (`jnyer27.github.io/RadioDroid`) uses your browser or GitHub’s infrastructure — that is between you and those services.
- **Sharing a file** (CSV, backup, etc.) via another app may upload content to a destination **you** choose (email, cloud drive, etc.).

Bundled **CHIRP** Python code includes utilities (e.g. URL fetch helpers) used in **desktop CHIRP**; RadioDroid’s Android bridge does not turn the app into a general-purpose network client for programming. Features that would require network access are not part of the documented Android UX.

---

## Custom drivers

Advanced users may **load a custom CHIRP driver** from device storage. That code runs **inside the app process** with the same trust model as bundled drivers. **Only load drivers from sources you trust** — malicious code could, in principle, access data visible to the app. The official project does not review third-party drivers you sideload.

---

## Android backup

The application manifest sets **`android:allowBackup="true"`**. Whether app data is included in **Google/Android backup** depends on your **device backup settings** and OEM behavior. Sensitive radio programming data could be part of device backups if your system backs up app data.

---

## Children’s privacy

RadioDroid is a **technical tool for licensed radio operators and enthusiasts**. It is **not directed at children** under 13 (or the age required in your jurisdiction). The app does not knowingly collect personal information from children.

---

## Changes to this policy

Updates will be published in the **same repository** (`privacypolicy.md` and the online copy under **User guide → Privacy policy**). The **effective date** at the top will be revised when material changes are made.

---

## Open source

Source code is available under the project **LICENSE** in the repository. You can inspect permissions, dependencies, and data flows in the Android module and Python bridge.

---

## Contact

For privacy-related questions about the **open-source project**, open an issue on **[github.com/jnyer27/RadioDroid](https://github.com/jnyer27/RadioDroid/issues)**. This project is maintained by contributors; there is no dedicated commercial data controller or DPA process unless separately agreed.

---

*This document is provided for transparency and convenience; it is not legal advice.*
