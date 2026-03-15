# RadioDroid User Guide

> **Work in progress** — RadioDroid is under active development.

## Overview

RadioDroid is an Android app that programs amateur and GMRS radios using the same Python drivers as the [CHIRP](https://chirp.app) desktop application.

## Supported Radios

RadioDroid bundles all CHIRP-supported drivers. Any radio that works with CHIRP on a PC should work with RadioDroid on Android via USB OTG.

Popular supported radios include:
- Baofeng UV-5R, BF-F8HP, UV-82, UV-K5
- TID Radio TD-H3 (nicFW)
- Yaesu FT-60, FT-65
- Kenwood TH-D74
- BTECH UV-50X3

## Connecting to a Radio

### USB OTG
1. Plug your programming cable into the radio.
2. Connect the other end to your Android device via a USB OTG adapter.
3. Launch RadioDroid — it will detect the device automatically.
4. Select your radio vendor and model.
5. Tap **Download** to read the radio memory.

### Bluetooth LE
1. Pair your BLE-to-serial adapter with your Android device.
2. In RadioDroid, select **Connect via BLE** and choose your adapter.
3. Select your radio vendor and model.
4. Tap **Download**.

## Editing Channels

After downloading, the channel list appears. Tap any channel to edit:
- **Frequency** — RX frequency in MHz
- **Duplex / Offset** — simplex, +/-, or split
- **Tone** — CTCSS or DCS encode/decode
- **Power** — transmit power level
- **Mode** — FM, AM, USB
- **Name** — up to 12 characters

## Uploading to Radio

Tap the **Upload** button in the toolbar. RadioDroid will write all channels back to the radio using the CHIRP driver's `sync_out()` method.

## CHIRP CSV Import / Export

- **Import**: tap ⋮ → Import CHIRP CSV to load a `.csv` file exported from desktop CHIRP.
- **Export**: multi-select channels → tap the export icon → name the file → share.
