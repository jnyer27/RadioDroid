# Dynamic driver-based UI (radio settings & channel extras)

This document describes how RadioDroid builds **radio-wide settings** and **per-channel driver-specific parameters** from the active CHIRP driver instead of hardcoding them.

## Concepts

- **Global parameters**: Standard CHIRP memory fields (frequency, name, mode, duplex, power, tones, etc.) — shown first in the channel list and editor; may be mapped (e.g. mode/bandwidth display).
- **Radio settings**: Driver-defined global settings (e.g. backlight, timeouts, beep) from CHIRP’s `get_settings()` / `set_settings()`. Only shown when `RadioFeatures.has_settings` is true.
- **Channel extras**: Driver-defined per-channel fields in `Memory.extra` (e.g. “Groups Slot 1–4” for nicFW). Shown in the “Radio-specific” section (below/right of global params) in the channel list and editor.

## 1. Radio settings screen (dynamic)

- **Python** (`chirp_bridge`):
  - `get_radio_settings(vendor, model, port, baudrate)` — open port, `sync_in()`, call `radio.get_settings()`, serialize the `RadioSettingGroup` / `RadioSetting` tree to JSON (group/setting name, type, current value, options for list types), return JSON string.
  - `set_radio_settings(vendor, model, port, baudrate, settings_json)` — `sync_in()`, deserialize JSON onto the tree returned by `get_settings()`, call `radio.set_settings(tree)`, then `sync_out()`.
- **Android**: “Radio settings” menu entry (when `has_settings`). Activity loads settings JSON and builds the form dynamically (sections = groups; rows = settings by type: int, bool, list, string). On Save, collect values into JSON and call `set_radio_settings`.

Settings are only available when the radio is connected (we need a live image for `get_settings()` / `set_settings()`).

### Full-EEPROM (clone) radios (e.g. TIDRADIO TD-H3 nicFW)

Many drivers (including **tidradio_h3_nicfw25**) use a **full EEPROM image**: `sync_in()` downloads the entire memory (e.g. 8 KB), and `sync_out()` uploads it. `get_settings()` and `set_settings()` read/write the in-memory image; they do not talk to the radio by themselves. So:

- **get_radio_settings** already does a **full download** (`sync_in()`) before calling `get_settings()`.
- **set_radio_settings** does **full download** (`sync_in()`), applies your changes in memory, then **full upload** (`sync_out()`).

No separate "full EEPROM" path is required; the same flow works. If Radio Settings fails on a full-EEPROM radio (e.g. TD-H3 over BLE), common causes are:

- **Timeout**: Full clone over BLE can be slow. The bridge uses a longer serial timeout (5 s) for the settings path; if it still fails, try "Load from radio" first to confirm the connection works.
- **Driver not loaded**: If the driver was added as a custom file, ensure it is loaded and the correct model is selected.
- **Protocol/connection**: BLE may need the radio to be idle; close other apps that might be using the radio.

## 2. Channel extras (dynamic)

- **Python**:
  - In `_memory_to_dict(mem)`: add `"extra": { item.get_name(): str(item.value) for item in getattr(mem, "extra", []) }`.
  - In `upload()`: for each channel, `mem = radio.get_memory(number)` (template with correct `extra` structure), fill standard fields from `ch`, then for each key in `ch.get("extra", {})` set the corresponding `mem.extra` item by name; call `radio.set_memory(mem)`.
- **Kotlin**:
  - `Channel` gets `extra: Map<String, String>` (or `extraParams: Map<String, String>`). `Channel.fromPyObject` reads `obj.get("extra")` and fills the map. Upload JSON includes an `"extra"` object per channel.
  - **Schema**: No driver API for “list of extra param names”. We derive it from the first channel that has non-empty `extra` (all channels share the same structure per driver). Store `EepromHolder.extraParamNames: List<String>` after download.
- **UI**:
  - **Channel list**: “Radio-specific” row shows `extra` as `key: value` lines (multi-column reflow). Ordering follows `channelExtraSchema`, then any other keys alphabetically.
  - **Channel editor**: “Radio-specific settings” section is driven by `channelExtraSchema` from the bridge (list/bool/int/string controls).
  - **Main screen (selection mode)**: bulk-edit one `Memory.extra` field across selected channels using the same schema (replaces legacy fixed “four group slots”).

## 3. Backward compatibility

- Radios without `has_settings`: no “Radio settings” menu; no change.
- Channels without `extra`: `extra` is empty map; “Radio-specific” section visibility is driven by schema/extra keys from the driver when available.
- Per-channel group membership and other driver fields live only in `Channel.extra` (no separate `group1`–`group4` on `Channel`).

## Implementation order

1. Bridge: add `extra` to `_memory_to_dict` and handle `extra` in `upload`.
2. Kotlin: `Channel.extra`, fromPyObject, upload JSON; derive `extraParamNames` after download.
3. Channel list + editor: show dynamic extra in Radio-specific section; bulk extra edit on main screen from schema.
4. Bridge: `get_radio_settings` / `set_radio_settings` with tree serialization.
5. Android: Radio Settings activity (dynamic form from JSON).
