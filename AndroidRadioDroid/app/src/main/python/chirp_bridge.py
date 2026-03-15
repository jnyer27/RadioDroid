"""
chirp_bridge.py — Python side of the RadioDroid <-> CHIRP interface.

Called from Kotlin via Chaquopy:
  get_radio_list()                          -> list of {vendor, model, baud_rate}
  download(vendor, model, port, baudrate)   -> list of channel dicts
  upload(vendor, model, port, baudrate, channels) -> None
"""

import sys
import os

# Ensure our python/ dir is on sys.path
_here = os.path.dirname(os.path.abspath(__file__))
if _here not in sys.path:
    sys.path.insert(0, _here)

# The CHIRP submodule is at python/chirp/ — its package is python/chirp/chirp/
# so we add the submodule root to sys.path so "import chirp" resolves correctly.
_chirp_src = os.path.join(_here, "chirp")
if _chirp_src not in sys.path:
    sys.path.insert(0, _chirp_src)

from serial_shim import AndroidSerial


# ── Driver loading ─────────────────────────────────────────────────────────────

_drivers_loaded = False

def _ensure_drivers():
    global _drivers_loaded
    if _drivers_loaded:
        return
    import importlib
    import pkgutil
    try:
        import chirp.drivers as _drv_pkg
        for _finder, _name, _ispkg in pkgutil.iter_modules(_drv_pkg.__path__):
            try:
                importlib.import_module(f"chirp.drivers.{_name}")
            except Exception:
                pass  # Skip drivers with missing optional deps
    except Exception as e:
        pass  # Chirp not available
    _drivers_loaded = True


# ── Public API ─────────────────────────────────────────────────────────────────

def get_radio_list():
    """Return [{vendor, model, baud_rate}] for every registered CHIRP driver."""
    _ensure_drivers()
    try:
        from chirp import directory
        result = []
        for cls in directory.DET_RADIOS:
            try:
                result.append({
                    "vendor":    cls.VENDOR,
                    "model":     cls.MODEL,
                    "baud_rate": getattr(cls, "BAUD_RATE", 9600),
                })
            except AttributeError:
                pass
        return result
    except Exception as e:
        return [{"error": str(e)}]


def _memory_to_dict(mem) -> dict:
    tmode  = getattr(mem, "tmode", "") or ""
    duplex = getattr(mem, "duplex", "") or ""
    freq   = getattr(mem, "freq", 0) or 0
    offset = getattr(mem, "offset", 0) or 0
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
        "skip":         getattr(mem, "skip", "") or "",
    }


def download(vendor: str, model: str, port: str, baudrate: int) -> list:
    """Instantiate the CHIRP driver, open the socket port, sync_in(), return channels."""
    _ensure_drivers()
    from chirp import directory

    radio_cls = directory.get_radio_by_name(f"{vendor} {model}")
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
    from chirp import directory, chirp_common

    radio_cls = directory.get_radio_by_name(f"{vendor} {model}")
    radio     = radio_cls(None)
    radio.pipe = AndroidSerial(port, baudrate=baudrate, timeout=0.5)
    try:
        radio.sync_in()  # Read first to preserve settings/cal blocks
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
