# RadioDroid User Guide

RadioDroid is an Android app that programs amateur and GMRS radios using the same Python drivers as the [CHIRP](https://chirp.app) desktop application. No PC is required: connect via USB OTG or Bluetooth LE and edit channels directly on your phone or tablet.

**Current release: v2.4.0** — See [GitHub Releases](https://github.com/jnyer27/RadioDroid/releases) for APK downloads and release notes. This guide is also published at **[jnyer27.github.io/RadioDroid](https://jnyer27.github.io/RadioDroid/)**.

---

## Table of contents

1. [Requirements](#requirements)
2. [Connecting to a radio](#connecting-to-a-radio)
3. [Bluetooth LE adapters (v2.1+)](#bluetooth-le-adapters-v21)
4. [Main screen](#main-screen)
5. [Downloading and uploading](#downloading-and-uploading)
6. [Editing channels](#editing-channels)
7. [Radio settings](#radio-settings)
8. [CHIRP CSV import and export](#chirp-csv-import-and-export)
9. [Customize main screen](#customize-main-screen)
10. [Supported radios](#supported-radios)

---

!!! note "Schematic layouts in this guide"
    Some sections include **boxed diagrams** that resemble the phone UI (toolbar, lists, dialogs). They are **not screenshots**—wording, colors, and spacing are simplified. Your theme and Android version may differ slightly.

## Requirements

- **Android 7.0 or later** (API 24+)
- **USB OTG cable** (for wired programming) **or** a **Bluetooth LE–capable** Android device and a BLE-to-serial adapter (for wireless)
- A **CHIRP-compatible radio** — if it works with CHIRP on a PC, it should work with RadioDroid

---

## Connecting to a radio

### USB OTG

1. Connect the programming cable to the radio.
2. Connect the other end to your Android device using a USB OTG adapter.
3. Open RadioDroid; the app will detect the USB serial device.
4. Tap the **⋮** menu → **Select Radio Model…** and choose your radio’s vendor and model.
5. Tap **Connect** (or **Load from radio** / **Download**) to open the port and read the radio.

### Bluetooth LE

1. Pair your BLE-to-serial adapter with your Android device in system Bluetooth settings (if your phone requires it).
2. In RadioDroid, tap **Connect** and choose **Connect via BLE**, then select your adapter from the scan list.
3. Select your radio model (⋮ → **Select Radio Model…** if needed).
4. Tap **Load from radio** to download the channel list.

### Schematic: BLE device list

```
┌─────────────────────────────────────────┐
│ Nearby devices                          │
├─────────────────────────────────────────┤
│ ← Connect via BLE                       │
├─────────────────────────────────────────┤
│  HM-10-5C12                             │
│  UART adapter · RSSI -58                │
├─────────────────────────────────────────┤
│  NUS-Prog                               │
│  Nordic UART · Paired                   │
└─────────────────────────────────────────┘
```

*Schematic. Device names vary; tap a row to select that adapter.*

After a successful download, the main screen shows the channel list and you can edit and upload.

---

## Bluetooth LE adapters (v2.1+)

**v2.1** improves support for common low-cost BLE-to-serial dongles used with Baofeng, TIDRADIO, and similar radios.

- **UART services supported** — The app looks for a serial/UART-style GATT service in this order: **HM-10 / TI-style** (`FFE0`), **Nordic UART**, **Microchip / ISSC**, **NICFW / TD-H3** (`FF00`). The first service present on the adapter is used; TX/RX characteristics are picked automatically (notify/indicate for data from radio, write for data to radio).
- **Scan filtering** — BLE scan only shows devices that **advertise** one of those UART services. That reduces clutter but means: if your dongle does **not** list the UART UUID in its advertisement packet, it may **not** appear in the list even though it would work after a direct connect (future app versions may add an optional “show all BLE devices” mode).
- **MTU and chunk size** — Writes default to **20-byte** chunks (safe for all adapters). The app then negotiates a **moderate** MTU; if negotiation fails or never completes, operation continues at 20 bytes so flaky adapters are less likely to disconnect.

If connection fails, try another USB cable path, ensure the dongle is powered and not paired exclusively to another app, and confirm the radio model is correct in **Select Radio Model…**.

---

## Main screen

- **Toolbar**
  - **Connect / Disconnect** — open or close the connection to the radio.
  - **Load from radio** — download channel memory from the radio (clone/sync in).
  - **Save to radio** — upload the current channel list back to the radio (clone/sync out). You’ll get a confirmation before overwriting the radio memory.
- **Channel list** — scrollable list of channels (e.g. 1–198). Each row shows:
  - Channel number, RX frequency, name, power, mode, duplex, and (if enabled) two extra slots you choose in **Customize main screen**.
  - For radios that support them: **Radio-specific** fields (e.g. groups, bandwidth) under the main row.
- **⋮ Menu**
  - **Search Channels** — show a search bar to filter by name, group, or frequency.
  - **Import CHIRP CSV from File…** — load a `.csv` exported from desktop CHIRP.
  - **Import CHIRP CSV from Clipboard…** — paste and import CSV text.
  - **Select Radio Model…** — pick vendor and model for the connected radio.
  - **Customize main screen** — choose which two values appear below Power on each channel row.
  - **Radio settings…** — open the driver’s global settings (backlight, beeps, etc.); only shown when the radio supports it and a memory image is loaded.
  - **Save EEPROM dump…** — save the current in-memory image to a file (for backup or inspection).
  - **Export CHIRP CSV (selected slots)…** — export selected channels as a CHIRP CSV to share or use in desktop CHIRP.

### Schematic: main list

```
┌─────────────────────────────────────────┐
│ Connected · USB                         │
├─────────────────────────────────────────┤
│ RadioDroid                           ⋮  │
├─────────────────────────────────────────┤
│ [Disconnect]  [Load from radio]         │
│               [Save to radio]           │
├────┬────────────────────────────────────┤
│  1 │ 462.5625 MHz · FM · High           │
│    │ GMRS 1 · Simplex                   │
├────┼────────────────────────────────────┤
│  2 │ 462.5875 MHz · NFM · Low           │
│    │ GMRS 2 · + 5 MHz                   │
│    │ Bandwidth · Wide · Group A         │
└────┴────────────────────────────────────┘
```

*Schematic. App bar, connection actions, and sample channel rows (extra line when the driver exposes radio-specific fields).*

Import, export, and radio settings are enabled only when channels are loaded (and, for radio settings, when the radio has settings support and is connected or has a clone image).

---

## Downloading and uploading

- **Download (Load from radio)**  
  Reads the full channel memory from the radio using the CHIRP driver’s sync/clone logic. **v2.2+:** An **indeterminate** progress bar and **rotating status text** show that work is in progress (the app does not receive a real percent/byte count from the CHIRP bridge). When it finishes, the channel list is filled and you can edit and save to file or upload later.

- **Upload (Save to radio)**  
  Writes the current in-memory channel list back to the radio. The app asks for confirmation because this overwrites the radio’s memory. Upload uses the same driver as download (e.g. full clone for many handhelds). **v2.2+:** The same indeterminate bar and rotating messages appear during upload.

### Schematic: transfer in progress

```
┌─────────────────────────────────────────┐
│ RadioDroid                              │
├─────────────────────────────────────────┤
│ [Load from radio ▒]  [Save to radio ▒]  │
├─────────────────────────────────────────┤
│ ▓▓▓▓▓▓▓▓░░░░░░░░░░░░░░░░░░░░░░░░░░░    │
│ Reading radio memory…                   │
│                                         │
│ (v2.2+: status text rotates; no         │
│  numeric percent from radio bridge)     │
└─────────────────────────────────────────┘
```

*Schematic. Indeterminate bar and status line during **Load from radio** or **Save to radio**.*

For **clone-mode** radios (e.g. many Baofeng, TID Radio, Retevis), the app keeps a full EEPROM image in memory after download. **Radio settings** and channel edits apply to that image; **Save to radio** sends the whole image back.

---

## Editing channels

Tap a channel in the list to open the **channel editor**.

### Schematic: channel editor

```
┌─────────────────────────────────────────────┐
│ ← Channel 1                                 │
├──────────────┬──────────────────────────────┤
│ RX frequency │ 462.5625                     │
│ Duplex       │ [Simplex]                    │
│ Name         │ GMRS 1                       │
│ Power        │ [High]                       │
│ Mode         │ [FM]                         │
├──────────────┴──────────────────────────────┤
│ [Cancel]                         [Done]     │
└─────────────────────────────────────────────┘
```

*Schematic. Typical fields; your radio may show more options (tones, radio-specific rows, etc.).*

- **RX Frequency** — receive frequency in MHz.
- **Duplex / Offset** — Simplex, **+** (positive offset), **−** (negative offset), or **Split** (separate TX frequency). Offset is in kHz or MHz as appropriate.
- **Channel name** — short label (length limit depends on the driver, often 8–16 characters).
- **Power** — transmit power (e.g. High, Low, or driver-specific levels).
- **Mode** — e.g. FM, NFM, AM, USB (driver-dependent).
- **TX tone / RX tone** — CTCSS or DCS encode/decode; choose from the tone list or “Off”.
- **Busy lock** — optional; may be disabled when duplex is not Simplex (driver-dependent).
- **Radio-specific settings** — extra parameters from the CHIRP driver (e.g. bandwidth, group slots). Shown as spinners or switches when the driver defines options; otherwise as text fields.

Tap **Done** to save the channel and return to the list, or **Cancel** to discard changes.

---

## Radio settings

**⋮** → **Radio settings…** opens a dynamic form built from the CHIRP driver’s **global** settings (e.g. backlight, timeouts, beeps, display options).

- The list is grouped by the driver’s setting groups; expand or collapse sections as needed.
- Use the **search** field at the top to filter by setting name or value.
- Change values, then tap **Update settings** to write them to the radio (or to the in-memory image for clone radios; then use **Save to radio** to upload).

### Schematic: radio settings

```
┌─────────────────────────────────────────────┐
│ ← Radio settings                            │
├─────────────────────────────────────────────┤
│  Search settings                            │
│  [ Filter by name or value… ]               │
├─────────────────────────────────────────────┤
│  ▼ Display & sound                          │
│     Backlight  │ [On]                       │
│     Beeps      │ [Key only]                 │
├─────────────────────────────────────────────┤
│          [Update settings]                  │
└─────────────────────────────────────────────┘
```

*Schematic. Group titles and fields come from the CHIRP driver; layout varies by model.*

Radio settings are available only when:
- The radio driver supports settings, and  
- The app has a connection to the radio **or** a clone image already loaded (for clone radios, settings are applied to the in-memory image).

---

## CHIRP CSV import and export

- **Import**
  - **From file:** ⋮ → **Import CHIRP CSV from File…** → choose a `.csv` exported from desktop CHIRP. Channels are merged into the current list by slot.
  - **From clipboard:** Copy CSV text (e.g. from CHIRP or email), then ⋮ → **Import CHIRP CSV from Clipboard…**. Same merge behavior as file import.

- **Export**
  - Select one or more channels (long-press or use the selection UI), then use the export action (e.g. from the menu or toolbar). Name the file and share or save. Format is CHIRP CSV so you can open it in CHIRP on a PC or share with others.

---

## Customize main screen

**⋮** → **Customize main screen**

Choose **Slot 1** and **Slot 2** — the two values that appear below the power level on each channel row on the main list (e.g. Duplex, Mode, or other driver-defined fields). This only changes the display; it does not alter channel data.

---

## Supported radios

RadioDroid ships with the same set of drivers as CHIRP. **Any radio that works with CHIRP on a PC should work with RadioDroid** over USB OTG or (with a BLE adapter) over Bluetooth LE.

Examples of supported families (this is not a full list):

- **Baofeng** — UV-5R, BF-F8HP, UV-82, UV-K5, and others  
- **TID Radio** — Pick the entry that matches your firmware: **TD-H3** is the stock CHIRP layout; **TD-H3 nicFW 2.5** is the separate driver for **nicFW V2.5** codeplugs (different EEPROM layout). Using the wrong one will misread channels or show blanks. Other TID models (e.g. TD-H6, TD-H8, TD-M11) have their own entries.  
- **Duplicate driver (sideloaded `.py`)** — If you used **Load .py Driver** for a module that is **also bundled** (e.g. `tidradio_h3_nicfw25.py`), the app may try to load it again on startup and hit a **duplicate driver** warning—the built-in copy is the one you should use. Delete the extra copy from `files/custom_drivers/` (e.g. with adb) or clear app data to drop sideloaded drivers; the FAB is only needed for drivers **not** in the build.  
- **Retevis** — RA25, RT85, and others  
- **Yaesu** — FT-60, FT-65, and others  
- **Kenwood** — TH-D74, and others  
- **BTECH** — UV-50X3, and others  

Select **Select Radio Model…** from the menu to see the full list for your build. For driver-specific behavior (e.g. radio-specific settings or channel extras), the app builds the UI from the driver; see the [Dynamic driver-based UI](https://github.com/jnyer27/RadioDroid/blob/main/docs/DYNAMIC_DRIVER_UI.md) doc in the repository for technical details.

---

## Tips

- **First time:** Connect the radio, select the exact model, then **Load from radio**. After the list is loaded, you can disconnect and still edit; reconnect when you want to **Save to radio** or open **Radio settings**.
- **Clone radios:** For radios that use a full EEPROM clone, **Radio settings** and channel edits apply to the in-memory image. Use **Save to radio** to write everything back in one go.
- **Search:** Use **Search Channels** to quickly find channels by name, group, or frequency.
- **Backup:** Use **Save EEPROM dump…** to keep a copy of the current image before making big changes.

---

*RadioDroid uses [CHIRP](https://github.com/kk7ds/chirp) drivers and [Chaquopy](https://chaquo.com/chaquopy/) to run them on Android.*
