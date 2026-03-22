## RadioDroid v3.4.0

### Highlights

- **Google Play / release build quality** — Release builds use **R8** (code shrinking) and **resource shrinking** for a smaller download, with ProGuard rules tuned for **Chaquopy** and **USB serial**. **Native debug symbol level** (`SYMBOL_TABLE`) is set for Play crash reporting. Upload the **`mapping.txt`** from the same build to Play Console if prompted, so Java/Kotlin stack traces can be deobfuscated.
- **Custom driver trust reminder** — On **Select Radio Model**, tapping **Load .py Driver** shows a clear warning before the file picker: custom CHIRP driver files run **as part of RadioDroid** and can use the same capabilities as the app (including USB and Bluetooth programming). Only install drivers from **sources you trust**.
- **Documentation** — **Security review** (codebase-focused, not a penetration test) is included below for transparency on GitHub Releases.

### Security review

Focused review of RadioDroid based on the Android app sources and manifest (**not** a full penetration test). Preserved here for transparency when cutting releases.

#### What looks good

- **No `INTERNET` permission** in `app/src/main` — The default attack surface for “phone home” or drive-by network abuse from the app process is much smaller than for a typical networked app (Python stdlib still cannot use the network without that permission).
- **Least-exposure components** — Only the launcher activity is `exported="true"`; other activities and `FileProvider` are non-exported (`android:exported="false"`), which is appropriate.
- **File sharing** — Exports use `FileProvider` with `FLAG_GRANT_READ_URI_PERMISSION` and a narrow path config (`res/xml/file_provider_paths.xml` → `external-files-path`), not a wide-open filesystem root.
- **Secrets in git** — `keystore.properties` and keystores are `.gitignore`d; signing passwords are loaded from a local file, not from the repo.
- **Preferences** — `SharedPreferences` uses `MODE_PRIVATE` (e.g. `RadioSelectActivity`, `CustomDriverManager`, `MainDisplayPref`).
- **Custom drivers (filename handling)** — `CustomDriverManager.resolveFileName` strips risky characters from picker display names before writing under `filesDir/custom_drivers/`, which reduces trivial path/trickery issues (slashes become `_`, etc.).

#### Notable risks and tradeoffs

- **`android:allowBackup="true"`** — App data can be included in Android Backup / device transfer flows (subject to OEM and user settings). For an app that holds radio programming data, EEPROM dumps, and backups, consider `allowBackup="false"` or a `<full-backup-content>` / data extraction rules allowlist so only non-sensitive prefs are backed up.

- **User-supplied Python (by design)** — Importing a custom CHIRP driver `.py` loads and runs that code inside your app with your app UID and access to everything the app can touch (files, USB/BT APIs you expose to Python, etc.). That is expected for this product, but it is the largest trust decision: users should only install drivers from sources they trust. There is no sandbox stronger than “it’s your code running as RadioDroid.” **v3.4.0** adds an in-app warning before choosing a custom driver file.

- **Untrusted file formats (CSV, JSON backup, EEPROM images)** — Imports read full file/clipboard content into memory and parse with `org.json` and CSV logic. A huge or pathological payload is mainly an **availability / DoS** risk (memory, time), not classic RCE on the Kotlin side. Worth considering max size and user warnings for files from strangers.

- **Hardcoded `buildPython` path in repo (maintainers)** — An absolute path under `C:/Users/...` is environment-specific and, if committed, leaks a local username and breaks other machines. Prefer Chaquopy’s default discovery, or read a path from `local.properties` / env (and keep `local.properties` out of git — already ignored under `AndroidRadioDroid/`).

- **Permissions (Bluetooth + legacy location)** — BLE/USB programming inherently needs strong hardware access. Legacy location permissions for pre-Android 12 scan behavior are normal but widen privacy review and Play declarations. No bug found in the manifest wiring itself; it’s a product surface area.

- **Supply chain** — The app ships Chaquopy, usb-serial-for-android, and a large CHIRP tree. Keep dependencies and the CHIRP submodule updated; use Play/App integrity and dependency scanning in CI for defense in depth (not visible in app code alone).

- **Clipboard CSV import** — Same trust model as file import: whatever is pasted is processed as CHIRP CSV. Fine for usability; users should be aware.

#### Secrets and release hygiene

- **Release signing** — Passwords belong only in `keystore.properties` on build machines; never commit them.
- **R8 `mapping.txt`** — Treat as a sensitive build artifact (helps deobfuscate your app); store with access control; upload to Play for the matching version.

#### Bottom line

For a local programmer tool with **no network permission** and **tight component export**, the design is **reasonably conservative**. The main items to treat seriously are **`allowBackup` vs sensitivity of backups/dumps**, the intentional **“run user Python”** surface for custom drivers, and **hardening imports** (size limits, trust messaging). This write-up is **not** a substitute for a professional app pen test or Play’s pre-launch report, but it captures the highest-signal points from the codebase review.

### Install

Download **`app-release.apk`** (or use the Play/App Bundle build for **versionCode 14**) and install on Android 7.0+.

### User guide

- **Online (MkDocs):** https://jnyer27.github.io/RadioDroid/
- **PDF:** file **`radiodroid-v3.4.0-userguide.pdf`** in Assets (uploaded by the *Update User Guide* workflow after release), or live at https://jnyer27.github.io/RadioDroid/user-guide.pdf
- **Source:** [`userguide.md`](https://github.com/jnyer27/RadioDroid/blob/main/userguide.md) in the repo

### Full context

[README](https://github.com/jnyer27/RadioDroid/blob/main/README.md) — features and architecture.

<!-- radiodroid-docs:userguide -->
