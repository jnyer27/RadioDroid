# Vendored CHIRP (RadioDroid)

RadioDroid ships CHIRP as **ordinary source** under `AndroidRadioDroid/app/src/main/python/chirp` inside this repository (not a Git submodule). One clone and one release tag cover the app and drivers together.

## Lineage

- **Upstream:** [kk7ds/chirp](https://github.com/kk7ds/chirp) (GPLv2+).
- **RadioDroid fork (optional sharing / merges):** [jnyer27/chirp](https://github.com/jnyer27/chirp) — use it to publish driver fixes or to merge upstream CHIRP, then copy or cherry-pick into the vendored tree here.

## Cloning

```bash
git clone https://github.com/jnyer27/RadioDroid.git
```

No `submodule update` step.

## Changing drivers

1. Edit under `AndroidRadioDroid/app/src/main/python/chirp/chirp/drivers/…`.
2. From `AndroidRadioDroid/app/src/main/python`, run targeted tests, e.g.  
   `python chirp/tests/unit/test_tidradio_h3_nicfw25.py`.
3. Commit on **RadioDroid** with the rest of the app change.

To **share** a driver patch with the fork: push the same change to `jnyer27/chirp` (or open a PR there) so others can reuse it; the **source of truth** for the app remains this repo.

## Merging upstream CHIRP

Use your normal Git workflow against [kk7ds/chirp](https://github.com/kk7ds/chirp) or your fork, then integrate into `app/src/main/python/chirp` (merge, cherry-pick, or manual copy). Resolve conflicts, run driver tests, and commit in RadioDroid.

## License

CHIRP remains GPLv2+ as in the upstream project; the vendored tree inherits the same license for CHIRP code.
