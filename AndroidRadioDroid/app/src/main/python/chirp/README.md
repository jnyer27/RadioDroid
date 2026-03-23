# CHIRP (RadioDroid vendored tree)

This directory is a **vendored copy** of CHIRP used by **[RadioDroid](https://github.com/jnyer27/RadioDroid)** — an Android app that runs CHIRP’s Python radio drivers on-device (via [Chaquopy](https://chaquo.com/chaquopy/)) for USB OTG and Bluetooth LE programming.

## Purpose

- **Shipped with the app** — Sources live under `AndroidRadioDroid/app/src/main/python/chirp` in the RadioDroid repository (no submodule step).
- **RadioDroid–specific fixes** — Patches for Android (clone/mmap, settings hooks) or drivers validated mainly on RadioDroid may exist here relative to [upstream CHIRP](https://github.com/kk7ds/chirp).
- **Optional fork** — **[jnyer27/chirp](https://github.com/jnyer27/chirp)** can still be used to publish or merge driver work; the **canonical copy for the app** is this vendored tree.

For maintainer workflow (upstream merges, fork), see [RadioDroid’s CHIRP notes](https://github.com/jnyer27/RadioDroid/blob/main/docs/CHIRP_SUBMODULE.md).

## Contributing

- **Changes intended for all CHIRP users** — Prefer a PR against **[kk7ds/chirp](https://github.com/kk7ds/chirp)**.
- **RadioDroid-only driver work** — Edit here and commit in **RadioDroid**; optionally mirror to **jnyer27/chirp** for sharing.

---

*CHIRP is free open-source software. Upstream project: [chirpmyradio.com](https://www.chirpmyradio.com).*
