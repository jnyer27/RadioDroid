"""
chirp_bridge.py — Python side of the RadioDroid <-> CHIRP interface.

Written against CHIRP 0.4.x API:
  directory.DRV_TO_RADIO   dict: driver_id -> class   (was DET_RADIOS list)
  directory.get_radio(id)  lookup by id                (was get_radio_by_name)

Called from Kotlin via Chaquopy:
  get_radio_list()                          -> list of {vendor, model, baud_rate}
  download(vendor, model, port, baudrate)   -> list of channel dicts
  upload(vendor, model, port, baudrate, channels) -> None
"""

import os
import sys
import importlib
import logging

LOG = logging.getLogger(__name__)

# ── sys.path setup ──────────────────────────────────────────────────────────────
# The CHIRP submodule is at python/chirp/ (repo root).
# The actual Python package is at python/chirp/chirp/__init__.py.
# Adding the repo root makes "import chirp" resolve correctly.

_here = os.path.dirname(os.path.abspath(__file__))
if _here not in sys.path:
    sys.path.insert(0, _here)

_chirp_src = os.path.join(_here, "chirp")      # submodule repo root
if _chirp_src not in sys.path:
    sys.path.insert(0, _chirp_src)


# ── Driver loading ──────────────────────────────────────────────────────────────

_drivers_loaded = False


def _ensure_drivers():
    """
    Import every module in chirp/drivers/ so their @register decorators fire.

    Three strategies are tried in priority order:

    1. chirp_driver_list.DRIVER_MODULES  — PREFERRED on Android.
       A static list generated at Gradle build time by the generateChirpDriverList
       task.  Immune to Chaquopy's zip-backed AssetFinder; no filesystem I/O needed.

    2. os.listdir(__path__[0])  — fallback for dev/desktop where the list hasn't
       been generated yet (plain CPython, running from source).

    3. pkgutil.iter_modules()   — last-resort fallback; works on standard CPython
       installations.
    """
    global _drivers_loaded
    if _drivers_loaded:
        return
    _drivers_loaded = True

    try:
        import chirp.drivers as _drv_pkg  # noqa: F401 — side-effects matter
    except Exception as e:
        LOG.error("Cannot import chirp.drivers: %s", e)
        return

    from chirp import directory

    # ── Strategy 1: static build-time list (Android / Chaquopy) ─────────────
    try:
        from chirp_driver_list import DRIVER_MODULES
        LOG.debug("_ensure_drivers: using static list (%d modules)", len(DRIVER_MODULES))
        for name in DRIVER_MODULES:
            try:
                importlib.import_module("chirp.drivers." + name)
            except Exception as e:
                LOG.debug("Skipping driver %s: %s", name, e)
        if directory.DRV_TO_RADIO:
            LOG.debug("_ensure_drivers: %d radios registered via static list",
                      len(directory.DRV_TO_RADIO))
            return
    except ImportError:
        LOG.debug("chirp_driver_list not found — falling back to filesystem scan")

    # ── Strategy 2: os.listdir (desktop / source checkout) ──────────────────
    try:
        import chirp.drivers as _drv_pkg2
        drv_dir = _drv_pkg2.__path__[0]
        names = set()
        for fname in os.listdir(drv_dir):
            if fname.startswith("_"):
                continue
            if fname.endswith(".py"):
                names.add(fname[:-3])
            elif fname.endswith(".pyc"):
                names.add(fname[:-4])
        for name in sorted(names):
            try:
                importlib.import_module("chirp.drivers." + name)
            except Exception as e:
                LOG.debug("Skipping driver %s: %s", name, e)
        if directory.DRV_TO_RADIO:
            LOG.debug("_ensure_drivers: %d radios registered via os.listdir",
                      len(directory.DRV_TO_RADIO))
            return
    except Exception as e:
        LOG.debug("os.listdir strategy failed (%s)", e)

    # ── Strategy 3: pkgutil (last resort) ───────────────────────────────────
    try:
        import pkgutil
        import chirp.drivers as _drv_pkg3
        for _finder, _name, _ispkg in pkgutil.iter_modules(_drv_pkg3.__path__):
            try:
                importlib.import_module("chirp.drivers." + _name)
            except Exception as e:
                LOG.debug("Skipping driver %s: %s", _name, e)
        LOG.debug("_ensure_drivers: %d radios registered via pkgutil",
                  len(directory.DRV_TO_RADIO))
    except Exception as e:
        LOG.debug("pkgutil strategy failed: %s", e)


# ── Helper: find a registered class by vendor + model ──────────────────────────

def _find_radio_cls(vendor, model):
    """Return the CHIRP driver class for the given vendor/model pair."""
    from chirp import directory
    for cls in directory.DRV_TO_RADIO.values():
        if (getattr(cls, "VENDOR", "") == vendor
                and getattr(cls, "MODEL", "") == model):
            return cls
    raise Exception(
        "No registered CHIRP driver for '%s %s' — "
        "is the driver module loaded?" % (vendor, model)
    )


# ── Public API ──────────────────────────────────────────────────────────────────

def get_radio_list():
    """
    Return [{vendor, model, baud_rate}] for every driver in DRV_TO_RADIO.

    Exceptions propagate to Kotlin so the UI can display a meaningful error
    rather than silently showing an empty list.
    """
    _ensure_drivers()
    from chirp import directory

    if not directory.DRV_TO_RADIO:
        # Surface diagnostic info so the Kotlin error handler can show it
        raise RuntimeError(
            "CHIRP driver registry is empty after loading drivers. "
            "chirp.drivers.__path__ = %s" % str(
                getattr(
                    sys.modules.get("chirp.drivers"), "__path__", "<not imported>")
            )
        )

    result = []
    for cls in directory.DRV_TO_RADIO.values():
        try:
            vendor = getattr(cls, "VENDOR", None)
            model  = getattr(cls, "MODEL",  None)
            if not vendor or not model:
                continue
            result.append({
                "vendor":    vendor,
                "model":     model,
                "baud_rate": getattr(cls, "BAUD_RATE", 9600),
            })
        except Exception as e:
            LOG.debug("Skipping class %s: %s", cls, e)
    return result


def _memory_to_dict(mem) -> dict:
    tmode    = getattr(mem, "tmode",  "") or ""
    duplex   = getattr(mem, "duplex", "") or ""
    freq     = getattr(mem, "freq",   0)  or 0
    offset   = getattr(mem, "offset", 0)  or 0
    # dtcs_polarity is a two-char string e.g. "NN", "RN".
    # Split into per-side values so the app can display/edit them independently.
    dtcs_pol = getattr(mem, "dtcs_polarity", "NN") or "NN"
    tx_pol   = dtcs_pol[0:1] if dtcs_pol else "N"
    rx_pol   = dtcs_pol[1:2] if len(dtcs_pol) > 1 else "N"
    return {
        "number":            mem.number,
        "name":              getattr(mem, "name", "") or "",
        "freq":              freq,
        "tx_freq":           freq + (offset if duplex == "+" else -offset if duplex == "-" else 0),
        "duplex":            duplex,
        "offset":            offset,
        "power":             str(getattr(mem, "power", "1") or "1"),
        "mode":              getattr(mem, "mode", "FM") or "FM",
        "tx_tone_mode":      tmode if tmode in ("Tone", "TSQL", "DTCS") else "",
        "tx_tone_val":       (getattr(mem, "rtone", 0.0) if tmode in ("Tone", "TSQL")
                              else getattr(mem, "dtcs", 0) if tmode == "DTCS" else 0.0),
        "tx_tone_polarity":  tx_pol,
        "rx_tone_mode":      ("TSQL" if tmode == "TSQL" else
                              "DTCS" if tmode == "DTCS" else ""),
        "rx_tone_val":       (getattr(mem, "ctone", 0.0) if tmode == "TSQL"
                              else getattr(mem, "dtcs", 0) if tmode == "DTCS" else 0.0),
        "rx_tone_polarity":  rx_pol,
        "empty":             getattr(mem, "empty", False),
        "skip":              getattr(mem, "skip",  "") or "",
    }


def download(vendor: str, model: str, port: str, baudrate: int) -> list:
    """Instantiate the CHIRP driver, open the socket port, sync_in(), return channels."""
    _ensure_drivers()
    from serial_shim import AndroidSerial

    radio_cls  = _find_radio_cls(vendor, model)
    radio      = radio_cls(None)
    radio.pipe = AndroidSerial(port, baudrate=baudrate, timeout=0.5)

    try:
        radio.sync_in()
    finally:
        radio.pipe.close()

    features = radio.get_features()
    lo, hi   = features.memory_bounds
    channels = []
    for n in range(lo, hi + 1):
        try:
            mem = radio.get_memory(n)
            channels.append(_memory_to_dict(mem))
        except Exception:
            channels.append({"number": n, "empty": True})
    return channels


def load_custom_driver(path: str) -> list:
    """
    Dynamically load a custom CHIRP driver .py file from device internal storage.

    The file must use the standard CHIRP @directory.register decorator on its
    radio class(es).  The function imports the module, lets the decorator fire,
    then returns a list of {vendor, model, baud_rate} dicts for each newly
    registered class so the UI can append them to the radio list immediately.

    Args:
        path: Absolute path to the .py file on the device filesystem
              (e.g. /data/data/com.radiodroid.app/files/custom_drivers/myradio.py)

    Returns:
        List of {vendor, model, baud_rate} for newly registered radio classes.

    Raises:
        ValueError:  if the spec cannot be built (bad path / not a .py file)
        Exception:   any exception raised by the driver module itself on import
    """
    import importlib.util
    from chirp import directory

    # Ensure built-in drivers are loaded first so custom driver can import
    # chirp_common, etc. without triggering a circular load.
    _ensure_drivers()

    before = set(directory.DRV_TO_RADIO.keys())

    module_name = "_radiodroid_custom_" + os.path.splitext(os.path.basename(path))[0]
    spec = importlib.util.spec_from_file_location(module_name, path)
    if spec is None or spec.loader is None:
        raise ValueError("Cannot create module spec from path: %s" % path)

    mod = importlib.util.module_from_spec(spec)
    # Make chirp packages available inside the custom module's namespace
    import sys as _sys
    _sys.modules[module_name] = mod
    spec.loader.exec_module(mod)

    after = set(directory.DRV_TO_RADIO.keys())
    new_keys = after - before

    result = []
    for key in sorted(new_keys):
        cls = directory.DRV_TO_RADIO[key]
        vendor = getattr(cls, "VENDOR", None)
        model  = getattr(cls, "MODEL",  None)
        if vendor and model:
            result.append({
                "vendor":    vendor,
                "model":     model,
                "baud_rate": getattr(cls, "BAUD_RATE", 9600),
            })
    LOG.info("load_custom_driver: loaded %s — %d new radio(s) registered",
             os.path.basename(path), len(result))
    return result


def get_radio_features(vendor: str, model: str) -> str:
    """
    Return a JSON string describing everything the radio driver supports.

    Instantiates the driver class with pipe=None — no USB or serial connection
    is needed.  get_features() is a pure class-level introspection call.

    The JSON mirrors CHIRP's RadioFeatures fields so the Android UI can
    dynamically populate spinners, enforce name limits, and show/hide sections
    based on actual driver capabilities rather than hardcoded defaults.
    """
    import json as _json
    _ensure_drivers()
    radio_cls = _find_radio_cls(vendor, model)
    radio     = radio_cls(None)
    f         = radio.get_features()

    def _list(attr, default=None):
        val = getattr(f, attr, default or [])
        try:
            return list(val)
        except TypeError:
            return default or []

    def _bool(attr, default=True):
        return bool(getattr(f, attr, default))

    def _int(attr, default=0):
        return int(getattr(f, attr, default))

    bounds = getattr(f, "memory_bounds", (0, 199))

    return _json.dumps({
        "valid_modes":        [str(m) for m in _list("valid_modes",    ["FM"])],
        "valid_duplexes":     [str(d) for d in _list("valid_duplexes", ["", "+", "-"])],
        "valid_tmodes":       [str(t) for t in _list("valid_tmodes",   [])],
        "valid_skips":        [str(s) for s in _list("valid_skips",    ["", "S"])],
        "valid_power_levels": [str(p) for p in _list("valid_power_levels", [])],
        "valid_dtcs_codes":   [int(c) for c in _list("valid_dtcs_codes",   [])],
        "valid_dtcs_pols":    [str(p) for p in _list("valid_dtcs_pols",
                               ["NN", "NR", "RN", "RR"])],
        "valid_name_length":  _int("valid_name_length", 0),
        "valid_name_chars":   str(getattr(f, "valid_characters", "") or ""),
        "has_name":           _bool("has_name",        True),
        "has_ctone":          _bool("has_ctone",        True),
        "has_rx_dtcs":        _bool("has_rx_dtcs",      False),
        "has_settings":       _bool("has_settings",     False),
        "has_tuning_step":    _bool("has_tuning_step",  True),
        "can_odd_split":      _bool("can_odd_split",    False),
        "memory_bounds_lo":   int(bounds[0]),
        "memory_bounds_hi":   int(bounds[1]),
    })


def upload(vendor: str, model: str, port: str, baudrate: int, channels_json: str):
    """Upload channel list back to the radio.

    channels_json is a JSON-encoded list of channel dicts serialised by the
    Kotlin caller.  Passing a plain string instead of a Java collection avoids
    Chaquopy proxy issues: Java ArrayList / LinkedHashMap are not directly
    iterable in Python, causing TypeError when dict() or 'for k in ch' is used.
    json.loads() returns a native Python list of dicts with no proxy layer.
    """
    import json as _json
    _ensure_drivers()
    from chirp import chirp_common
    from serial_shim import AndroidSerial

    channels = _json.loads(str(channels_json))

    radio_cls  = _find_radio_cls(vendor, model)
    radio      = radio_cls(None)
    radio.pipe = AndroidSerial(port, baudrate=baudrate, timeout=0.5)
    try:
        radio.sync_in()   # Read first to preserve settings/cal blocks
        features = radio.get_features()

        for ch in channels:
            number = int(ch.get("number") or 0)

            # ── Empty slot: erase on radio so deleted channels aren't left behind ──
            if ch.get("empty"):
                try:
                    radio.erase_memory(number)
                except Exception:
                    # Some drivers don't implement erase_memory(); try set_memory
                    # with mem.empty = True as a fallback.
                    try:
                        _m = chirp_common.Memory(number)
                        _m.empty = True
                        radio.set_memory(_m)
                    except Exception:
                        pass  # driver doesn't support erasing — leave as-is
                continue

            mem        = chirp_common.Memory()
            mem.number = number
            mem.name   = ch.get("name", "") or ""
            mem.freq   = int(ch.get("freq")   or 0)
            mem.offset = int(ch.get("offset") or 0)

            # ── Duplex ───────────────────────────────────────────────────────────
            # Guard against duplex values the radio doesn't support (e.g. "split"
            # on radios that only allow "" / "+" / "-").
            duplex = ch.get("duplex", "") or ""
            valid_duplexes = getattr(features, "valid_duplexes", None)
            if valid_duplexes and duplex not in valid_duplexes:
                duplex = ""
            mem.duplex = duplex

            # ── Mode ─────────────────────────────────────────────────────────────
            # "Auto" and other exotic modes may not be in a radio's valid_modes.
            # Fall back to "FM" rather than letting set_memory() raise ValueError.
            mode = ch.get("mode", "FM") or "FM"
            valid_modes = getattr(features, "valid_modes", None)
            if valid_modes and mode not in valid_modes:
                mode = "FM"
            mem.mode = mode

            # ── Tone / CTCSS / DCS ───────────────────────────────────────────────
            # tx_tone_mode drives mem.tmode; values mirror CHIRP's own tmode strings.
            # Guard against tone modes the radio doesn't support.
            tmode = ch.get("tx_tone_mode", "") or ""
            valid_tmodes = getattr(features, "valid_tmodes", None)
            if valid_tmodes and tmode not in valid_tmodes:
                tmode = ""
            if tmode == "Tone":
                mem.tmode = "Tone"
                mem.rtone = float(ch.get("tx_tone_val") or 88.5)
            elif tmode == "TSQL":
                mem.tmode = "TSQL"
                mem.rtone = float(ch.get("tx_tone_val") or 88.5)
                mem.ctone = float(ch.get("rx_tone_val") or 88.5)
            elif tmode == "DTCS":
                mem.tmode = "DTCS"
                mem.dtcs  = int(ch.get("tx_tone_val") or 23)
                # Polarity is stored as two chars "NN"/"NR"/"RN"/"RR" in CHIRP.
                # tx_tone_polarity / rx_tone_polarity default to "N" (normal).
                tx_pol = (ch.get("tx_tone_polarity") or "N")[:1]
                rx_pol = (ch.get("rx_tone_polarity") or "N")[:1]
                mem.dtcs_polarity = tx_pol + rx_pol
            else:
                mem.tmode = ""

            # ── Power level ──────────────────────────────────────────────────────
            # CHIRP uses driver-specific PowerLevel objects.  Try to match the
            # stored string (e.g. "High", "Low", "5W") against the driver's list.
            # If no match, leave mem.power = None — the driver will use its default.
            power_str = ch.get("power", "") or ""
            if power_str and features.valid_power_levels:
                matched = next(
                    (p for p in features.valid_power_levels if str(p) == power_str),
                    None
                )
                if matched:
                    mem.power = matched

            # ── Scan skip ────────────────────────────────────────────────────────
            # "S" = skip, "P" = priority (radio-dependent), "" = never skip.
            # Guard against skip values the radio doesn't support.
            skip = ch.get("skip", "") or ""
            valid_skips = getattr(features, "valid_skips", None)
            if valid_skips and skip not in valid_skips:
                skip = ""
            mem.skip = skip

            radio.set_memory(mem)

        radio.sync_out()
    finally:
        radio.pipe.close()
