# RadioDroid

**Android CHIRP-compatible universal radio programmer**

RadioDroid brings the full [CHIRP](https://chirp.app) radio programming ecosystem to Android. It runs existing validated CHIRP Python drivers natively on the device via [Chaquopy](https://chaquo.com/chaquopy/), enabling USB OTG and Bluetooth LE connections to 170+ supported radios — no PC required.

## Features

- 📻 **170+ radios** via bundled CHIRP Python drivers (Baofeng, TID Radio, Yaesu, Kenwood, and more)
- 🔌 **USB OTG** — connect any CHIRP-supported radio via USB cable
- 📶 **Bluetooth LE** — wireless programming via BLE-to-serial adapters
- ✏️ **Full channel editing** — frequency, tone (CTCSS/DCS), power, mode, name
- 📋 **CHIRP CSV import/export** — share channels with desktop CHIRP or other users
- 🔍 **Channel search** — filter by name, group, or frequency

## Architecture

RadioDroid uses [Chaquopy](https://chaquo.com/chaquopy/) to run CPython 3.13 on Android. Existing CHIRP drivers run unmodified; a thin `serial_shim.py` bridges the CHIRP `serial.Serial` interface to Android's USB Host API (via `usbserial4a`) and BLE (via a `LocalSocket` relay).

```
UI (Kotlin) → ChirpBridge.kt → chirp_bridge.py → CHIRP driver → AndroidSerial → Radio
```

## Requirements

- Android 7.0+ (API 24)
- USB OTG cable **or** BLE-capable Android device

## Related

- [NICFW TD-H3 Channel Editor](https://github.com/jnyer27/NICFW-H3-25-CHIRP-ADAPTER) — TID H3-specific editor with nicFW 2.5 advanced features
- [CHIRP](https://github.com/kk7ds/chirp) — the open-source radio programming project this app builds upon
