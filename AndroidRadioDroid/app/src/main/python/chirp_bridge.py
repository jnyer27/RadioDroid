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

import sys
import os
import logging

LOG = logging.getLogger(__name__)

# ── sys.path setup ──────────────────────────────────────────────────────────────
# Chaquopy places python/ sources on sys.path, but the CHIRP submodule is a full
# repo (python/chirp/) whose package root is python/chirp/chirp/.  We add the
# repo root so that "import chirp" resolves to python/chirp/chirp/__init__.py.

_here = os.path.dirname(os.path.abspath(__file__))
if _here not in sys.path:
    sys.path.insert(0, _here)

_chirp_src = os.path.join(_here, "chirp")      # submodule repo root
if _chirp_src not in sys.path:
    sys.path.insert(0, _chirp_src)


# ── Driver loading ──────────────────────────────────────────────────────────────

_drivers_loaded = False


def _ensure_drivers():
    """Import every module in chirp/drivers/ so their @register decorators fire."""
    global _drivers_loaded
    if _drivers_loaded:
        return
    import importlib
    import pkgutil
    try:
        import chirp.drivers as _drv_pkg
        for _finder, _name, _ispkg in pkgutil.iter_modules(_drv_pkg.__path__):
            try:
                importlib.import_module("chirp.drivers." + _name)
            except Exception as e:
                LOG.debug("Skipping driver %s: %s", _name, e)
    except Exception as e:
        LOG.error("Failed to load CHIRP drivers: %s", e)
    _drivers_loaded = True


# ── Helper: find a registered class by vendor + model ──────────────────────────

def _find_radio_cls(vendor, model):
    """Return the CHIRP driver class for the given vendor/model pair."""
    from chirp import directory
    for cls in directory.DRV_TO_RADIO.values():
        if (getattr(cls, "VENDOR", "") == vendor
                and getattr(cls, "MODEL", "") == model):
            return cls
    raise Exception(
        "No registered CHIRP driver for %s %s — "
        "check that drivers are loaded and the name matches exactly." % (vendor, model)
    )


# ── Public API ──────────────────────────────────────────────────────────────────

def get_radio_list():
    """Return [{vendor, model, baud_rate}] for every driver in DRV_TO_RADIO."""
    _ensure_drivers()
    try:
        from chirp import directory
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
    except Exception as e:
        LOG.error("get_radio_list failed: %s", e)
        return []


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

    radio_cls = _find_radio_cls(vendor, model)
    radio     = radio_cls(None)
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
