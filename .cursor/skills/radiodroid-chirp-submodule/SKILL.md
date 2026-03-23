---
name: radiodroid-chirp-submodule
description: >-
  Keeps RadioDroid’s bundled CHIRP tree aligned with the git submodule pin and
  avoids duplicate driver logic. Use when changing CHIRP drivers under
  AndroidRadioDroid/app/src/main/python/chirp, debugging BLE/EEPROM channel
  decode (nicFW H3, DTCS, tones), after git pull/clone, when submodule status
  shows +/− or detached HEAD, or when the user mentions submodule drift, wrong
  driver version, or Python vs Kotlin tone mapping.
---

# RadioDroid — prevent CHIRP submodule drift

## Golden rules

1. **The commit recorded in the parent repo is authoritative.**  
   `AndroidRadioDroid/app/src/main/python/chirp` must match  
   `git ls-tree HEAD AndroidRadioDroid/app/src/main/python/chirp`.  
   A detached HEAD at a *different* SHA means local drift; symptoms include wrong DTCS display, stale `valid_bands`, or “fix already on main” not appearing in the tree.

2. **Driver fixes belong in the fork, then the pointer moves.**  
   Never leave long-lived uncommitted edits only inside the submodule checkout. Flow: commit → push **`jnyer27/chirp`** → `git add` submodule in RadioDroid → commit “Bump CHIRP submodule”. Details: [docs/CHIRP_SUBMODULE.md](docs/CHIRP_SUBMODULE.md).

3. **BLE “Load from radio” uses the Python CHIRP driver path**, not Kotlin `ToneCodec`.  
   If EEPROM layout or DTCS/CTCSS mapping changes, **`tidradio_h3_nicfw25.py` (and friends) are source of truth** for that path. Kotlin helpers must stay consistent or be documented as non-authoritative to avoid two mental models.

## After clone or pull

Run from **RadioDroid repo root**:

```bash
git submodule sync
git submodule update --init --recursive
```

Prefer **`git clone --recurse-submodules`** for new clones (see [README.md](README.md)).

## Verify before debugging “driver” issues

From repo root:

```bash
git submodule status AndroidRadioDroid/app/src/main/python/chirp
```

- A leading **`+`** means the submodule checkout is **ahead** of the pin (uncommitted or different commit checked out).
- A leading **`-`** means **behind** or not initialized — run `submodule update` above.

Optional: compare checked-out SHA to the pin:

```bash
git -C AndroidRadioDroid/app/src/main/python/chirp rev-parse HEAD
git ls-tree HEAD AndroidRadioDroid/app/src/main/python/chirp
```

The two SHAs should match on a clean `main` checkout.

## When the user (or task) changes CHIRP code

Checklist:

- [ ] Work inside `AndroidRadioDroid/app/src/main/python/chirp` on a branch of the **fork**, not only local edits.
- [ ] Run targeted tests from `AndroidRadioDroid/app/src/main/python`, e.g.  
  `python chirp/tests/unit/test_tidradio_h3_nicfw25.py` when touching `tidradio_h3_nicfw25.py`.
- [ ] Push the fork branch; in **RadioDroid** root, stage the submodule path and commit the pointer bump.
- [ ] Mention submodule bump in PR/release notes when user-visible behavior changes.

## CI hardening (optional project follow-up)

If the repo adds a workflow, fail when:

- `git submodule status` shows `+` after checkout, or  
- the submodule tree is dirty (`git status --porcelain` in `chirp/`).

This turns silent drift into a broken build.

## Related

- Submodule setup and bump procedure: [docs/CHIRP_SUBMODULE.md](docs/CHIRP_SUBMODULE.md)
- Release checklist (version/docs alignment): skill **`radiodroid-release`**
