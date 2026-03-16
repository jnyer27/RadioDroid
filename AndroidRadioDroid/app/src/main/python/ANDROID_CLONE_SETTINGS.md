# Clone-mode settings on Android (and similar runtimes)

On some runtimes (e.g. Android), after the user changes a setting and taps "Update settings", the UI tree is updated but the driver's `set_settings(ui)` may not see the new values—so the EEPROM struct is never updated and the change does not persist.

## Universal fix: override `apply_setting(self, name, value)`

- **Base class:** `chirp_common.CloneModeRadio` defines `apply_setting(self, name, value)` as a no-op.
- **Bridge:** After `set_settings(tree)`, the Android bridge calls `radio.apply_setting(name, value)` for each applied setting (from the saved JSON). Then it syncs the settings struct to the EEPROM buffer as usual.
- **Driver:** Override `apply_setting(self, name, value)` and implement the same logic you use in `set_settings(ui)` for each setting name: map `name` to the correct struct field and write `value` (with any type conversion your driver uses).

## Reference implementation

- **tidradio_h3_nicfw25.py** — uses `_apply_one_setting(name, val)` and exposes it via `apply_setting(name, val)`. Use it as the pattern for other clone drivers.

## For new drivers

If your clone driver has settings and you want them to persist on Android (and any runtime that uses this path), add an `apply_setting(self, name, value)` override that applies one setting by name/value to your memory struct. The template and base class docstrings point to this behavior.

---

## How to test

### 1. Unit tests (no device)

- **Base class / driver hook** — from the `chirp` package directory (e.g. `AndroidRadioDroid/app/src/main/python/chirp`):
  ```bash
  python -m pytest tests/unit/test_chirp_common.py::TestCloneModeRadioApplySetting -v
  ```
  This checks that `apply_setting` exists, is a no-op by default, and that overrides are invoked.

- **Bridge** — run from the app Python root (or run the script directly; it adds the app root to `sys.path`):
  ```bash
  cd AndroidRadioDroid/app/src/main/python
  python -m pytest chirp/tests/unit/test_apply_setting_bridge.py -v
  ```
  Or without pytest: `python chirp/tests/unit/test_apply_setting_bridge.py`
  This checks that `_apply_settings_via_driver` calls `apply_setting` (or `apply_setting_to_settings`) for each setting and handles missing path/value.

  **Note:** The chirp test suite requires pytest (see `chirp/test-requirements.txt`). The bridge test file can be run with plain `python` when executed from `app/src/main/python`.

### 2. CHIRP settings round-trip (desktop)

For any driver with settings, the existing test ensures get/set round-trip:
  ```bash
  cd AndroidRadioDroid/app/src/main/python/chirp
  python -m pytest tests/test_settings.py -v
  ```
  (Uses test images where available; some drivers are skipped without an image.)

### 3. Manual test on Android

1. Build and install the app; open a clone-mode radio (e.g. TID Radio H3 nicFW 2.5) from image or via clone.
2. Open **Radio settings**, change a setting (e.g. **LCD brightness** from 28 to 27), tap **Update settings**.
3. Leave the screen and re-open **Radio settings** (or re-load the image and open settings).
4. Confirm the value persisted (e.g. still 27). If the driver implements `apply_setting`, it will persist; otherwise it may revert on runtimes where `set_settings(tree)` does not update the struct.
