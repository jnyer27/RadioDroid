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
    tmode  = getattr(mem, "tmode",  "") or ""
    duplex = getattr(mem, "duplex", "") or ""
    freq   = getattr(mem, "freq",   0)  or 0
    offset = getattr(mem, "offset", 0)  or 0
    return {
        "number":       mem.number,
        "name":         getattr(mem, "name", "") or "",
        "freq":         freq,
        "tx_freq":      freq + (offset if duplex == "+" else -offset if duplex == "-" else 0),
        "duplex":       duplex,
        "offset":       offset,
        "power":        str(getattr(mem, "power", "1") or "1"),
        "mode":         getattr(mem, "mode", "FM") or "FM",
        "tx_tone_mode": tmode if tmode in ("Tone", "TSQL", "DTCS") else "",
        "tx_tone_val":  (getattr(mem, "rtone", 0.0) if tmode in ("Tone", "TSQL")
                         else getattr(mem, "dtcs", 0) if tmode == "DTCS" else 0.0),
        "rx_tone_mode": ("TSQL" if tmode == "TSQL" else
                         "DTCS" if tmode == "DTCS" else ""),
        "rx_tone_val":  (getattr(mem, "ctone", 0.0) if tmode == "TSQL"
                         else getattr(mem, "dtcs", 0) if tmode == "DTCS" else 0.0),
        "empty":        getattr(mem, "empty", False),
        "skip":         getattr(mem, "skip",  "") or "",
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


def upload(vendor: str, model: str, port: str, baudrate: int, channels: list):
    """Upload channel list back to the radio."""
    _ensure_drivers()
    from chirp import chirp_common
    from serial_shim import AndroidSerial

    radio_cls  = _find_radio_cls(vendor, model)
    radio      = radio_cls(None)
    radio.pipe = AndroidSerial(port, baudrate=baudrate, timeout=0.5)
    try:
        radio.sync_in()   # Read first to preserve settings/cal blocks
        for ch in channels:
            if ch.get("empty"):
                continue
            mem        = chirp_common.Memory()
            mem.number = ch["number"]
            mem.name   = ch.get("name", "")
            mem.freq   = ch.get("freq", 0)
            mem.duplex = ch.get("duplex", "")
            mem.offset = ch.get("offset", 0)
            mem.mode   = ch.get("mode", "FM")
            radio.set_memory(mem)
        radio.sync_out()
    finally:
        radio.pipe.close()
