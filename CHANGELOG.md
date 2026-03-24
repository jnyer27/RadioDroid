# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Changed

- **CHIRP import preview** — **TX Power (All Imported Channels)** spinner uses **`RadioFeatures.validPowerLevels`** when the selected driver defines discrete levels (same as channel editor / bulk TX power), with fallback to the generic numeric list when empty. Preview rows show non-interactive **chips** for **TX** / **RX** tone (or **None**) and **duplex** (**Simplex**, offset, or **Split** with TX MHz).
- **RepeaterBook GMRS proximity** — After **gmrs/prox_result.php**, the app fetches **gmrs/details.php** per repeater to fill **PL** / **TSQ** (mirrors amateur **prox2** enrichment). **`parseGmrsHtml`** supports **`th`/`td`** and legacy **`td`/`td`** rows, ignores **Travel Tone**, rejects login-wall placeholder text in **`normalizeToneCell`**. Unit tests in **`app/src/test/.../repeaterbook/RepeaterBookDetailsTonesTest`**.

### Added

- **RepeaterBook search (Android)** — Main overflow menu **Search RepeaterBook…** (enabled when a channel list is loaded). CHIRP-style country, state/province, and service (Amateur / US GMRS); optional latitude, longitude, and distance for proximity; US amateur **Proximity 2.0**-style band/mode filters when searching by coordinates; GMRS HTML proximity where applicable. Results are multi-selectable; **Import selected** builds a CHIRP CSV and opens the existing **CHIRP import** flow into empty EEPROM slots.
- **RepeaterBook API configuration** — `REPEATERBOOK_APP_TOKEN`, `REPEATERBOOK_CONTACT_EMAIL`, and optional `REPEATERBOOK_APP_URL`, `REPEATERBOOK_AUTH_MODE`, `REPEATERBOOK_USER_AGENT` in `AndroidRadioDroid/local.properties` (wired into `BuildConfig`). See the [RepeaterBook API wiki](https://www.repeaterbook.com/wiki/doku.php?id=api).
- **Dependencies** — OkHttp and Jsoup for HTTPS and HTML proximity parsing.
- **Permissions** — `INTERNET`; fine/coarse **location** for optional “use my location” (no `maxSdkVersion` cap so it works on current Android).

### Notes

- Implementation is adapted from [AndroidNICFW_CH_EDITOR](https://github.com/jnyer27/NICFW-H3-25-CHIRP-ADAPTER/tree/main/AndroidNICFW_CH_EDITOR) (CHIRP-style RepeaterBook flow). Default HTTP **User-Agent** identifies the app as **RadioDroid**.
