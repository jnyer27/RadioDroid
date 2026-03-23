---
name: radiodroid-chirp-submodule
description: >-
  Keeps RadioDroid’s vendored CHIRP tree coherent and explains the Python driver
  path vs Kotlin helpers. Use when editing CHIRP drivers under
  AndroidRadioDroid/app/src/main/python/chirp, debugging BLE/EEPROM channel
  decode (nicFW H3, DTCS, tones), merging kk7ds/chirp or jnyer27/chirp into the
  vendored copy, or when the user mentions CHIRP drift, fork sync, or submodule
  (legacy).
---

# RadioDroid — vendored CHIRP

## Golden rules

1. **CHIRP is part of the RadioDroid repo** — Path `AndroidRadioDroid/app/src/main/python/chirp` is **normal tracked files**, not a submodule. There is **no** separate “bump submodule pointer” step for releases.

2. **BLE “Load from radio” uses the Python CHIRP driver path**, not Kotlin `ToneCodec`. EEPROM layout and DTCS/CTCSS mapping for nicFW H3 live in **`chirp/chirp/drivers/tidradio_h3_nicfw25.py`**.

3. **Optional fork **[**jnyer27/chirp**](https://github.com/jnyer27/chirp)** — Use it to share patches or to merge **kk7ds/chirp** before copying into the vendored tree. **Source of truth for the app** is still the copy inside RadioDroid.

## After clone

```bash
git clone https://github.com/jnyer27/RadioDroid.git
```

No `submodule update`.

## When changing drivers

- [ ] Edit under `AndroidRadioDroid/app/src/main/python/chirp/chirp/drivers/…`.
- [ ] From `AndroidRadioDroid/app/src/main/python`, run targeted tests, e.g.  
  `python chirp/tests/unit/test_tidradio_h3_nicfw25.py`.
- [ ] Commit on **RadioDroid** `main` (or your branch) with the app if needed.

## Upstream / fork sync (manual)

```bash
# Example: fetch upstream kk7ds in a separate clone of the fork, merge, resolve,
# then integrate into RadioDroid’s vendored tree (merge, cherry-pick, or copy).
```

Document the merge in the commit message (e.g. “Merge CHIRP upstream through 20xx-xx”).

## Related

- [docs/CHIRP_SUBMODULE.md](docs/CHIRP_SUBMODULE.md) — vendored layout and fork (filename legacy).
- Release checklist: skill **`radiodroid-release`**
