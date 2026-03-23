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
    tmode      = getattr(mem, "tmode",      "") or ""
    cross_mode = getattr(mem, "cross_mode", "") or ""
    duplex   = getattr(mem, "duplex", "") or ""
    freq     = getattr(mem, "freq",   0)  or 0
    offset   = getattr(mem, "offset", 0)  or 0
    # dtcs_polarity is a two-char string e.g. "NN", "RN".
    # Split into per-side values so the app can display/edit them independently.
    dtcs_pol = getattr(mem, "dtcs_polarity", "NN") or "NN"
    tx_pol   = dtcs_pol[0:1] if dtcs_pol else "N"
    rx_pol   = dtcs_pol[1:2] if len(dtcs_pol) > 1 else "N"

    # Resolve per-side tone mode/value, handling CHIRP's "Cross" tmode.
    # Drivers like nicFW use split_tone_decode() which sets tmode="Cross" whenever
    # TX and RX tones differ (e.g. DTCS->DTCS repeater with different codes).
    if tmode == "Cross":
        _tx_type, _, _rx_type = cross_mode.partition("->")
        tx_tone_mode = _tx_type if _tx_type in ("Tone", "DTCS") else ""
        rx_tone_mode = _rx_type if _rx_type in ("Tone", "DTCS") else ""
        tx_tone_val  = (getattr(mem, "rtone",   0.0) if _tx_type == "Tone"
                        else getattr(mem, "dtcs",    0)   if _tx_type == "DTCS" else 0.0)
        rx_tone_val  = (getattr(mem, "ctone",   0.0) if _rx_type == "Tone"
                        else getattr(mem, "rx_dtcs", 0)   if _rx_type == "DTCS" else 0.0)
    else:
        tx_tone_mode = tmode if tmode in ("Tone", "TSQL", "DTCS") else ""
        tx_tone_val  = (getattr(mem, "rtone", 0.0) if tmode in ("Tone", "TSQL")
                        else getattr(mem, "dtcs", 0) if tmode == "DTCS" else 0.0)
        rx_tone_mode = ("TSQL" if tmode == "TSQL" else
                        "DTCS" if tmode == "DTCS" else "")
        rx_tone_val  = (getattr(mem, "ctone", 0.0) if tmode == "TSQL"
                        else getattr(mem, "dtcs", 0) if tmode == "DTCS" else 0.0)

    out = {
        "number":            mem.number,
        "name":              getattr(mem, "name", "") or "",
        "freq":              freq,
        "tx_freq":           freq + (offset if duplex == "+" else -offset if duplex == "-" else 0),
        "duplex":            duplex,
        "offset":            offset,
        "power":             str(getattr(mem, "power", "1") or "1"),
        "mode":              getattr(mem, "mode", "FM") or "FM",
        "tx_tone_mode":      tx_tone_mode,
        "tx_tone_val":       tx_tone_val,
        "tx_tone_polarity":  tx_pol,
        "rx_tone_mode":      rx_tone_mode,
        "rx_tone_val":       rx_tone_val,
        "rx_tone_polarity":  rx_pol,
        "empty":             getattr(mem, "empty", False),
        "skip":              getattr(mem, "skip",  "") or "",
    }
    # Driver-specific per-channel params (Memory.extra)
    extra_list = getattr(mem, "extra", []) or []
    if extra_list:
        out["extra"] = {}
        for item in extra_list:
            try:
                name = item.get_name()
                val = getattr(item, "value", item)
                out["extra"][name] = str(val) if val is not None else ""
            except Exception:
                pass
    return out


def is_clone_mode_radio(vendor: str, model: str) -> bool:
    """Return True if the driver is a full-EEPROM clone-mode radio (e.g. TD-H3 nicFW)."""
    _ensure_drivers()
    from chirp import chirp_common
    radio_cls = _find_radio_cls(vendor, model)
    return issubclass(radio_cls, chirp_common.CloneModeRadio)


def download(vendor: str, model: str, port: str, baudrate: int) -> str:
    """
    Instantiate the CHIRP driver, sync_in(), return a JSON string with channels
    and for clone-mode radios the raw EEPROM (base64) so the app can work off
    a local copy.
    Returns JSON: {"channels": [...], "eeprom_base64": "<str or null>"}.
    """
    import base64
    import json as _json
    _ensure_drivers()
    from chirp import chirp_common
    from serial_shim import AndroidSerial

    radio_cls  = _find_radio_cls(vendor, model)
    radio      = radio_cls(None)
    timeout = 5.0 if issubclass(radio_cls, chirp_common.CloneModeRadio) else 0.5
    radio.pipe = AndroidSerial(port, baudrate=baudrate, timeout=timeout)

    # Capture clone EEPROM immediately after sync_in (before pipe close / get_memory loop)
    # so the raw dump is definitely available for Save EEPROM and Radio Settings from mmap.
    eeprom_b64 = None
    try:
        radio.sync_in()
        if isinstance(radio, chirp_common.CloneModeRadio):
            mmap = radio.get_mmap()
            if mmap is not None:
                try:
                    bc = getattr(mmap, "get_byte_compatible", None)
                    if callable(bc):
                        raw = bc().get_packed()
                    else:
                        raw = mmap.get_packed()
                    if raw and len(raw) > 0:
                        eeprom_b64 = base64.b64encode(bytes(raw)).decode()
                except Exception as e:
                    LOG.warning("clone eeprom capture failed: %s", e)
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

    return _json.dumps({"channels": channels, "eeprom_base64": eeprom_b64})


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


def _radio_setting_to_extra_entry(setting) -> dict:
    """
    Serialize a single RadioSetting (e.g. from Memory.extra) to a dict for JSON.
    Same type/value logic as _settings_tree_to_json; used for channel extra schema.
    """
    from chirp import settings as chirp_settings
    entry = {"name": setting.get_name() or ""}
    try:
        val = setting.value
        if not getattr(val, "get_mutable", lambda: True)():
            entry["readOnly"] = True
        try:
            current = val.get_value()
        except Exception:
            current = None
        cls_name = type(val).__name__
        # Prefer explicit option lists when available, regardless of concrete class name
        if hasattr(val, "get_options"):
            entry["type"] = "list"
            entry["value"] = str(current) if current is not None else ""
            try:
                entry["options"] = list(val.get_options())
            except Exception:
                entry["options"] = []
        elif "Integer" in cls_name:
            entry["type"] = "int"
            entry["value"] = int(current) if current is not None else 0
            if hasattr(val, "get_min"):
                entry["min"] = val.get_min()
            if hasattr(val, "get_max"):
                entry["max"] = val.get_max()
        elif "Float" in cls_name:
            entry["type"] = "float"
            entry["value"] = float(current) if current is not None else 0.0
            if hasattr(val, "get_min"):
                entry["min"] = val.get_min()
            if hasattr(val, "get_max"):
                entry["max"] = val.get_max()
        elif "Boolean" in cls_name:
            entry["type"] = "bool"
            entry["value"] = bool(current)
        elif "List" in cls_name or "Map" in cls_name:
            entry["type"] = "list"
            entry["value"] = str(current) if current is not None else ""
            entry["options"] = list(val.get_options()) if hasattr(val, "get_options") else []
        elif "String" in cls_name:
            entry["type"] = "string"
            entry["value"] = str(current) if current is not None else ""
            if hasattr(val, "minlength"):
                entry["minLength"] = getattr(val, "minlength", 0)
            if hasattr(val, "maxlength"):
                entry["maxLength"] = getattr(val, "maxlength", 255)
        else:
            entry["type"] = "string"
            entry["value"] = str(current) if current is not None else ""
    except Exception as e:
        LOG.debug("Skip extra setting %s: %s", entry.get("name", "?"), e)
        return None
    return entry


def get_channel_extra_schema(vendor: str, model: str, eeprom_base64: str = None) -> str:
    """
    Return a JSON array of channel-extra setting definitions for the given driver.

    For clone-mode radios, pass eeprom_base64 (from a prior download) so the
    driver can load the EEPROM and build mem.extra in get_memory(); without it,
    get_memory(lo) typically fails because _mmap/_memobj are never set.

    For non-clone radios, eeprom_base64 is ignored; the radio is instantiated with
    pipe=None and get_memory(lo) is attempted (may fail for some drivers).

    If the driver's Memory.extra is a RadioSettingGroup with RadioSetting leaves,
    each is serialized with name, type, value, options (for list), min/max, etc.

    Returns "[]" if get_memory fails or mem.extra is missing or empty.
    """
    import base64
    import json as _json
    from chirp import chirp_common
    from chirp import memmap

    _ensure_drivers()
    out = []
    try:
        radio_cls = _find_radio_cls(vendor, model)
        # Clone-mode: load EEPROM so get_memory() can build mem.extra from _memobj
        if eeprom_base64 and eeprom_base64.strip() and issubclass(radio_cls, chirp_common.CloneModeRadio):
            data = base64.b64decode(eeprom_base64)
            mmap = memmap.MemoryMapBytes(bytes(data))
            radio = radio_cls(mmap)
        else:
            radio = radio_cls(None)
        features = radio.get_features()
        lo, hi = getattr(features, "memory_bounds", (0, 199))
        # Try a few slots in case the first is empty (empty channels often have no mem.extra)
        from chirp import settings as chirp_settings
        extra = None
        for slot in range(lo, min(lo + 5, hi + 1)):
            mem = radio.get_memory(slot)
            extra = getattr(mem, "extra", None)
            if extra:
                try:
                    extra_list = list(extra)
                except Exception:
                    extra_list = []
                if not extra_list and hasattr(extra, "_element_order"):
                    try:
                        extra_list = [extra[n] for n in getattr(extra, "_element_order", [])]
                    except (KeyError, TypeError):
                        extra_list = []
                if any(isinstance(item, chirp_settings.RadioSetting) for item in extra_list):
                    break
            extra = None
        if not extra:
            return _json.dumps(out)
        try:
            extra_list = list(extra)
        except Exception:
            extra_list = []
        if not extra_list and hasattr(extra, "_element_order"):
            try:
                extra_list = [extra[n] for n in getattr(extra, "_element_order", [])]
            except (KeyError, TypeError):
                extra_list = []
        for item in extra_list:
            if isinstance(item, chirp_settings.RadioSetting):
                entry = _radio_setting_to_extra_entry(item)
                if entry is not None:
                    out.append(entry)
    except Exception as e:
        LOG.debug("get_channel_extra_schema failed for %s %s: %s", vendor, model, e)
    return _json.dumps(out)


def _settings_tree_to_json(settings_obj) -> list:
    """
    Walk the get_settings() tree and return a flat list of setting dicts for JSON.
    Each item: path, name, type, value, and type-specific fields (min, max, options).
    """
    from chirp import settings as chirp_settings

    out = []

    def walk_group(group, path_prefix: list):
        # RadioSetting is a leaf (contains a single value); serialize it and do not recurse
        if isinstance(group, chirp_settings.RadioSetting):
            try:
                val = group.value
                path_str = "/".join(path_prefix)
                entry = {"path": path_str, "name": group.get_shortname() or group.get_name() or path_prefix[-1] if path_prefix else ""}
                if not getattr(val, "get_mutable", lambda: True)():
                    entry["readOnly"] = True
                try:
                    current = val.get_value()
                except Exception:
                    current = None
                cls_name = type(val).__name__
                if "Integer" in cls_name:
                    entry["type"] = "int"
                    entry["value"] = int(current) if current is not None else 0
                    if hasattr(val, "get_min"):
                        entry["min"] = val.get_min()
                    if hasattr(val, "get_max"):
                        entry["max"] = val.get_max()
                elif "Float" in cls_name:
                    entry["type"] = "float"
                    entry["value"] = float(current) if current is not None else 0.0
                    if hasattr(val, "get_min"):
                        entry["min"] = val.get_min()
                    if hasattr(val, "get_max"):
                        entry["max"] = val.get_max()
                elif "Boolean" in cls_name:
                    entry["type"] = "bool"
                    entry["value"] = bool(current)
                elif "List" in cls_name or "Map" in cls_name:
                    entry["type"] = "list"
                    entry["value"] = str(current) if current is not None else ""
                    entry["options"] = list(val.get_options()) if hasattr(val, "get_options") else []
                elif "String" in cls_name:
                    entry["type"] = "string"
                    entry["value"] = str(current) if current is not None else ""
                    if hasattr(val, "minlength"):
                        entry["minLength"] = getattr(val, "minlength", 0)
                    if hasattr(val, "maxlength"):
                        entry["maxLength"] = getattr(val, "maxlength", 255)
                else:
                    entry["type"] = "string"
                    entry["value"] = str(current) if current is not None else ""
                out.append(entry)
            except Exception as e:
                LOG.debug("Skip setting %s: %s", "/".join(path_prefix), e)
            return
        order = getattr(group, "get_order", None) and group.get_order() or getattr(
            group, "_element_order", []
        )
        for name in order:
            el = group[name] if hasattr(group, "__getitem__") else getattr(group, "_elements", {}).get(name)
            if el is None:
                continue
            path = path_prefix + [str(name)]
            if isinstance(el, chirp_settings.RadioSetting):
                walk_group(el, path)
            elif hasattr(el, "get_order") or hasattr(el, "_element_order"):
                walk_group(el, path)

    top = settings_obj
    if isinstance(top, (list, tuple)):
        for i, g in enumerate(top):
            walk_group(g, [str(i)])
    else:
        walk_group(top, [])
    return out


def _apply_settings_from_json(settings_obj, settings_list: list):
    """Apply a list of {path, value} (or path + value in flat list) onto the live tree."""
    from chirp import settings as chirp_settings

    # Build by_path and by_leaf for lookup (leaf = last path component; fallback when path differs)
    by_path = {}
    by_leaf = {}
    for s in settings_list:
        if "path" not in s or "value" not in s:
            continue
        p = str(s["path"]).strip()
        by_path[p] = s
        if p.startswith("root/"):
            by_path[p[5:]] = s  # also register without "root/" for tree walk that omits root
        leaf = p.split("/")[-1] if "/" in p else p
        if leaf not in by_leaf:
            by_leaf[leaf] = s
        else:
            by_leaf[leaf] = None  # ambiguous, don't use leaf lookup

    applied_count = [0]  # use list so inner function can mutate
    lcd_brightness_value = [None]  # value we set for lcdBrightness, if any

    def walk_and_set(group, path_prefix: list):
        order = getattr(group, "get_order", None) and group.get_order() or getattr(
            group, "_element_order", []
        )
        for name in order:
            el = group[name] if hasattr(group, "__getitem__") else getattr(group, "_elements", {}).get(name)
            if el is None:
                continue
            path = path_prefix + [str(name)]
            path_str = "/".join(path)
            if isinstance(el, chirp_settings.RadioSetting):
                payload = (
                    by_path.get(path_str) or by_path.get("root/" + path_str) or
                    by_leaf.get(path_str.split("/")[-1] if "/" in path_str else path_str) or
                    by_leaf.get(el.get_name())
                )
                if payload is None or payload.get("readOnly"):
                    continue
                try:
                    val = el.value
                    v = payload["value"]
                    cls_name = type(val).__name__
                    if "Boolean" in cls_name:
                        val.set_value(bool(v))
                    elif "Integer" in cls_name:
                        val.set_value(int(v))
                    elif "Float" in cls_name:
                        val.set_value(float(v))
                    elif "List" in cls_name or "Map" in cls_name:
                        val.set_value(str(v))
                    else:
                        val.set_value(str(v))
                    applied_count[0] += 1
                    if el.get_name() == "lcdBrightness":
                        lcd_brightness_value[0] = int(v) if v is not None else None
                except Exception as e:
                    LOG.warning("Apply %s = %r: %s", path_str, payload.get("value"), e)
            elif hasattr(el, "get_order") or hasattr(el, "_element_order"):
                walk_and_set(el, path)

    top = settings_obj
    if isinstance(top, (list, tuple)):
        for i, g in enumerate(top):
            walk_and_set(g, [str(i)])
    else:
        walk_and_set(top, [])
    n = applied_count[0]
    if n == 0 and by_path:
        LOG.warning("set_radio_settings_to_mmap: applied 0 settings (paths may not match tree)")
    else:
        LOG.info("set_radio_settings_to_mmap: applied %d settings", n)
    return n, lcd_brightness_value[0]


def _apply_settings_via_driver(radio, settings_list: list) -> None:
    """
    Call the driver's apply_setting(name, value) for each applied setting.
    CloneModeRadio defines apply_setting() (default no-op); drivers that override it
    get correct persistence on runtimes where set_settings(tree) does not persist.
    Also supports legacy apply_setting_to_settings for backward compatibility.
    """
    apply_one = getattr(radio, "apply_setting", None)
    if not callable(apply_one):
        apply_one = getattr(radio, "apply_setting_to_settings", None)
    if not callable(apply_one):
        return
    for s in settings_list:
        if "path" not in s or "value" not in s:
            continue
        path = str(s.get("path", "")).strip()
        name = path.split("/")[-1] if "/" in path else path
        if not name:
            continue
        try:
            apply_one(name, s["value"])
        except Exception:
            pass


def get_radio_settings(vendor: str, model: str, port: str, baudrate: int) -> str:
    """
    Connect to the radio, sync_in(), get_settings(), and return a JSON string
    of the settings tree (flat list of {path, name, type, value, ...}).
    Requires an open connection; the radio must be connected first.
    """
    import json as _json
    _ensure_drivers()
    from serial_shim import AndroidSerial

    radio_cls = _find_radio_cls(vendor, model)
    radio = radio_cls(None)
    # Full-EEPROM radios (e.g. TD-H3 nicFW) do full clone in sync_in; use longer timeout for BLE/slow links
    radio.pipe = AndroidSerial(port, baudrate=baudrate, timeout=5.0)
    try:
        radio.sync_in()
        raw = radio.get_settings()
        if raw is None:
            return _json.dumps({"settings": []})
        flat = _settings_tree_to_json(raw)
        return _json.dumps({"settings": flat})
    except Exception:
        LOG.exception("get_radio_settings failed for %s %s", vendor, model)
        raise
    finally:
        radio.pipe.close()


def set_radio_settings(vendor: str, model: str, port: str, baudrate: int, settings_json: str) -> None:
    """
    Connect to the radio, sync_in(), get_settings(), apply the JSON values,
    call set_settings(), then sync_out().  settings_json must be the same
    structure as returned by get_radio_settings (e.g. {"settings": [ {...}, ...]}).
    """
    import json as _json
    _ensure_drivers()
    from serial_shim import AndroidSerial

    payload = _json.loads(str(settings_json))
    settings_list = payload.get("settings") or payload.get("settings_list") or []

    radio_cls = _find_radio_cls(vendor, model)
    radio = radio_cls(None)
    # Full-EEPROM clone in sync_in/sync_out; longer timeout for BLE/slow links
    radio.pipe = AndroidSerial(port, baudrate=baudrate, timeout=5.0)
    try:
        radio.sync_in()
        tree = radio.get_settings()
        if tree is not None:
            _apply_settings_from_json(tree, settings_list)
            radio.set_settings(tree)
        radio.sync_out()
    except Exception:
        LOG.exception("set_radio_settings failed for %s %s", vendor, model)
        raise
    finally:
        radio.pipe.close()


def set_settings_live(vendor: str, model: str, port: str, baudrate: int, settings_json: str) -> None:
    """
    Apply settings to a live non-clone radio.

    Connects to the radio, calls get_settings() to obtain the settings tree,
    applies the JSON values, then calls set_settings().  Does NOT call
    sync_in() or sync_out() — those are only for clone-mode full EEPROM
    transfers initiated by the user's explicit Save to Radio action.
    """
    import json as _json
    _ensure_drivers()
    from serial_shim import AndroidSerial

    payload = _json.loads(str(settings_json))
    settings_list = payload.get("settings") or payload.get("settings_list") or []

    radio_cls = _find_radio_cls(vendor, model)
    radio = radio_cls(None)
    radio.pipe = AndroidSerial(port, baudrate=baudrate, timeout=5.0)
    try:
        tree = radio.get_settings()
        if tree is not None:
            _apply_settings_from_json(tree, settings_list)
            radio.set_settings(tree)
    except Exception:
        LOG.exception("set_settings_live failed for %s %s", vendor, model)
        raise
    finally:
        radio.pipe.close()


def get_radio_settings_from_mmap(vendor: str, model: str, eeprom_base64: str) -> str:
    """
    Get settings from an in-memory EEPROM dump (clone-mode radios only).
    No radio connection. Returns same JSON structure as get_radio_settings().
    """
    import base64
    import json as _json
    from chirp import chirp_common
    from chirp import memmap

    _ensure_drivers()
    radio_cls = _find_radio_cls(vendor, model)
    if not issubclass(radio_cls, chirp_common.CloneModeRadio):
        raise Exception("get_radio_settings_from_mmap requires a clone-mode radio")
    data = base64.b64decode(eeprom_base64)
    mmap = memmap.MemoryMapBytes(bytes(data))
    radio = radio_cls(mmap)
    raw = radio.get_settings()
    if raw is None:
        return _json.dumps({"settings": []})
    flat = _settings_tree_to_json(raw)
    return _json.dumps({"settings": flat})


def set_radio_settings_to_mmap(vendor: str, model: str, eeprom_base64: str, settings_json: str) -> str:
    """
    Apply settings to an in-memory EEPROM dump; return new eeprom_base64.
    No radio connection. Used when user taps Save in Radio Settings (clone mode).
    """
    import base64
    import json as _json
    from chirp import chirp_common
    from chirp import memmap

    _ensure_drivers()
    payload = _json.loads(str(settings_json))
    settings_list = payload.get("settings") or payload.get("settings_list") or []

    # Debug: log what we received (paths and sample values)
    paths_with_value = [s.get("path") for s in settings_list if "path" in s and "value" in s]
    LOG.warning("set_radio_settings_to_mmap: received %d settings, first paths: %s",
                len(paths_with_value), paths_with_value[:5] if paths_with_value else [])

    radio_cls = _find_radio_cls(vendor, model)
    if not issubclass(radio_cls, chirp_common.CloneModeRadio):
        raise Exception("set_radio_settings_to_mmap requires a clone-mode radio")
    data = base64.b64decode(eeprom_base64)
    mmap = memmap.MemoryMapBytes(bytes(data))
    radio = radio_cls(mmap)
    applied_count = 0
    tree = radio.get_settings()
    if tree is not None:
        applied_count, lcd_brightness_applied = _apply_settings_from_json(tree, settings_list)
        radio.set_settings(tree)
        # On some runtimes the driver does not persist tree->struct; apply via driver API when available
        _apply_settings_via_driver(radio, settings_list)
    else:
        lcd_brightness_applied = None
    mmap_byte = radio.get_mmap().get_byte_compatible()
    # Sync settings struct to buffer (on some runtimes bitwise does not write through)
    if hasattr(radio, "_memobj") and hasattr(radio._memobj, "settings") and hasattr(mmap_byte, "_data"):
        try:
            raw = radio._memobj.settings.get_raw(asbytes=True)
            if raw is not None:
                raw = bytes(raw) if not isinstance(raw, bytes) else raw
                start = 0x1900
                for i, b in enumerate(raw):
                    if start + i < len(mmap_byte._data):
                        mmap_byte._data[start + i] = b if isinstance(b, int) else ord(b)
        except Exception as e:
            LOG.warning("set_radio_settings_to_mmap: sync struct to buffer: %s", e)
    new_raw = mmap_byte.get_packed()
    # Diagnostic: lcdBrightness at 0x1919 (settings + 25 bytes)
    struct_lcd = None
    buf_lcd = None
    if hasattr(radio, "_memobj") and hasattr(radio._memobj, "settings") and len(new_raw) > 0x1919:
        try:
            struct_lcd = int(getattr(radio._memobj.settings, "lcdBrightness", None))
        except (TypeError, ValueError):
            pass
        if hasattr(mmap_byte, "_data") and len(mmap_byte._data) > 0x1919:
            buf_lcd = int(mmap_byte._data[0x1919])
    out_b64 = base64.b64encode(new_raw).decode()
    out = {"eepromBase64": out_b64, "appliedCount": applied_count}
    if lcd_brightness_applied is not None:
        out["lcdBrightnessApplied"] = lcd_brightness_applied
    if struct_lcd is not None:
        out["structLcdBrightness"] = struct_lcd
    if buf_lcd is not None:
        out["bufferLcdBrightness"] = buf_lcd
    return _json.dumps(out)


def upload_mmap(vendor: str, model: str, port: str, baudrate: int, eeprom_base64: str) -> None:
    """
    Upload the in-memory EEPROM dump to the radio (clone-mode only).
    No sync_in(); load mmap from eeprom_base64, then sync_out().
    """
    import base64
    _ensure_drivers()
    from chirp import chirp_common
    from chirp import memmap
    from serial_shim import AndroidSerial

    radio_cls = _find_radio_cls(vendor, model)
    if not issubclass(radio_cls, chirp_common.CloneModeRadio):
        raise Exception("upload_mmap requires a clone-mode radio")
    data = base64.b64decode(eeprom_base64)
    mmap = memmap.MemoryMapBytes(bytes(data))
    radio = radio_cls(mmap)
    radio.pipe = AndroidSerial(port, baudrate=baudrate, timeout=5.0)
    try:
        radio.sync_out()
    except Exception:
        LOG.exception("upload_mmap failed for %s %s", vendor, model)
        raise
    finally:
        radio.pipe.close()


def load_from_eeprom(vendor: str, model: str, eeprom_base64: str) -> str:
    """
    Load channels from a raw EEPROM dump (clone-mode radios only). No radio connection needed.
    Instantiates the CHIRP driver from the provided EEPROM bytes, reads channels, and returns
    the same JSON structure as download(): {"channels": [...], "eeprom_base64": "<str>"}.

    Args:
        vendor:        Radio vendor string (e.g. "Radioddity")
        model:         Radio model string (e.g. "TD-H3 (nicFW)")
        eeprom_base64: Base64-encoded raw EEPROM bytes (e.g. from a saved .img file)

    Raises:
        Exception: if the driver is not clone-mode or the EEPROM cannot be parsed
    """
    import base64
    import json as _json
    _ensure_drivers()
    from chirp import chirp_common
    from chirp import memmap

    radio_cls = _find_radio_cls(vendor, model)
    if not issubclass(radio_cls, chirp_common.CloneModeRadio):
        raise Exception("load_from_eeprom requires a clone-mode radio driver")

    data = base64.b64decode(eeprom_base64)
    mmap = memmap.MemoryMapBytes(bytes(data))
    radio = radio_cls(mmap)

    features = radio.get_features()
    lo, hi = features.memory_bounds
    channels = []
    for n in range(lo, hi + 1):
        try:
            mem = radio.get_memory(n)
            channels.append(_memory_to_dict(mem))
        except Exception:
            channels.append({"number": n, "empty": True})

    return _json.dumps({"channels": channels, "eeprom_base64": eeprom_base64})


def _memory_extra_len(mem):
    """Return number of RadioSetting entries under Memory.extra (0 if missing)."""
    ex = getattr(mem, "extra", None)
    if ex is None:
        return 0
    try:
        return len(ex)
    except Exception:
        return 0


def _maybe_attach_memory_extra_template(radio, number, mem):
    """
    CHIRP often returns early for empty EEPROM slots without assigning mem.extra,
    leaving the class-level Memory.extra (a shared []) — then JSON 'extra' cannot
    be applied.  Rebuild templates by calling the driver's _get_memory(mem, …) if
    it exists.  RadioDroid bridge only; does not modify upstream driver .py files.
    """
    if _memory_extra_len(mem) > 0:
        return
    fn = getattr(radio, "_get_memory", None)
    if not callable(fn):
        return
    memobj = getattr(radio, "_memobj", None)
    if memobj is None:
        return
    import inspect
    from chirp import chirp_common

    try:
        sig = inspect.signature(fn)
        params = list(sig.parameters.keys())
        if params and params[0] == "self":
            params = params[1:]
        nargs = len(params)
    except (ValueError, TypeError):
        return

    shell = chirp_common.Memory()
    shell.number = number

    try:
        if nargs == 3:
            chan_mem = getattr(memobj, "chan_mem", None)
            chan_name = getattr(memobj, "chan_name", None)
            if chan_mem is None or chan_name is None:
                return
            _mem = chan_mem[number - 1]
            _name = chan_name[number - 1]
            filled = fn(shell, _mem, _name)
        elif nargs == 2:
            chan_mem = getattr(memobj, "chan_mem", None)
            if chan_mem is not None:
                _mem = chan_mem[number - 1]
            else:
                chm = getattr(memobj, "memory", None)
                if chm is None:
                    return
                _mem = chm[number - 1]
            filled = fn(shell, _mem)
        else:
            return
    except Exception as e:
        LOG.debug("_maybe_attach_memory_extra_template: %s", e)
        return

    if filled is not None and _memory_extra_len(filled) > 0:
        mem.extra = filled.extra


def _validation_messages_to_json(msgs) -> str:
    """Serialize CHIRP validate_memory results for Kotlin (errors and warnings only)."""
    import json as _json
    from chirp import chirp_common

    out = []
    for m in msgs:
        if isinstance(m, chirp_common.ValidationError):
            out.append({"kind": "error", "text": str(m)})
        elif isinstance(m, chirp_common.ValidationWarning):
            out.append({"kind": "warning", "text": str(m)})
    return _json.dumps(out)


def _prepare_and_validate_channel_memory(radio, number: int, ch: dict, features):
    """
    Build the same Memory as apply_channel_to_mmap (non-empty path), run validate_memory.
    Returns (messages, mem). For empty logical channels, returns ([], None) — caller handles clear.
    """
    if ch.get("empty"):
        return [], None
    mem = radio.get_memory(number)
    extra_d = ch.get("extra") or {}
    if isinstance(extra_d, dict) and extra_d:
        _maybe_attach_memory_extra_template(radio, number, mem)
    _channel_dict_into_memory(mem, ch, features)
    mem.number = number
    return radio.validate_memory(mem), mem


def validate_channel_dict(vendor: str, model: str, eeprom_base64: str, channel_json: str) -> str:
    """
    Clone-mode: load mmap, merge channel_json into that slot, run validate_memory.
    Returns JSON list of {kind: "error"|"warning", text: "..."}.
    Empty channels return [].
    """
    import base64
    import json as _json
    _ensure_drivers()
    from chirp import chirp_common
    from chirp import memmap

    ch = _json.loads(str(channel_json))
    if ch.get("empty"):
        return "[]"

    number = int(ch.get("number") or 0)
    if number < 1:
        return _validation_messages_to_json(
            [chirp_common.ValidationError("Channel number must be >= 1")])

    radio_cls = _find_radio_cls(vendor, model)
    if not issubclass(radio_cls, chirp_common.CloneModeRadio):
        return "[]"

    data = base64.b64decode(eeprom_base64)
    mmap = memmap.MemoryMapBytes(bytes(data))
    radio = radio_cls(mmap)
    features = radio.get_features()
    msgs, _ = _prepare_and_validate_channel_memory(radio, number, ch, features)
    return _validation_messages_to_json(msgs)


def apply_channel_to_mmap(vendor: str, model: str, eeprom_base64: str, channel_json: str) -> str:
    """
    Apply a single channel edit to the in-memory EEPROM (clone-mode only).
    Used when the user edits a channel so the raw dump stays in sync with the channel list.
    Returns new eeprom_base64.
    """
    import base64
    import json as _json
    _ensure_drivers()
    from chirp import chirp_common
    from chirp import memmap

    ch = _json.loads(str(channel_json))
    number = int(ch.get("number") or 0)
    if number < 1:
        raise ValueError("Channel number must be >= 1")

    radio_cls = _find_radio_cls(vendor, model)
    if not issubclass(radio_cls, chirp_common.CloneModeRadio):
        raise Exception("apply_channel_to_mmap requires a clone-mode radio")
    data = base64.b64decode(eeprom_base64)
    mmap = memmap.MemoryMapBytes(bytes(data))
    radio = radio_cls(mmap)
    features = radio.get_features()

    # Get template from radio so mem.extra has the driver's structure
    mem = radio.get_memory(number)
    if ch.get("empty"):
        mem.empty = True
        mem.number = number
        radio.set_memory(mem)
    else:
        msgs, mem = _prepare_and_validate_channel_memory(radio, number, ch, features)
        errs = [m for m in msgs if isinstance(m, chirp_common.ValidationError)]
        if errs:
            raise ValueError("; ".join(str(e) for e in errs))
        radio.set_memory(mem)

    new_raw = radio.get_mmap().get_byte_compatible().get_packed()
    return base64.b64encode(new_raw).decode()


def _apply_tones_to_memory(mem, ch, features):
    """
    Set mem.tmode / tone fields from a channel dict produced by _memory_to_dict().

    Handles all CHIRP tone modes including "Cross" (e.g. DTCS->DTCS repeaters
    where TX and RX DCS codes differ).  When the driver's valid_tmodes includes
    "Cross", the correct cross_mode is reconstructed so a round-trip through
    the driver is lossless.
    """
    tx_tmode  = ch.get("tx_tone_mode", "") or ""
    rx_tmode  = ch.get("rx_tone_mode", "") or ""
    tx_val    = ch.get("tx_tone_val")
    rx_val    = ch.get("rx_tone_val")
    tx_pol    = (ch.get("tx_tone_polarity") or "N")[:1]
    rx_pol    = (ch.get("rx_tone_polarity") or "N")[:1]
    valid_tmodes = getattr(features, "valid_tmodes", None)

    def _supports(mode):
        return not valid_tmodes or mode in valid_tmodes

    if tx_tmode == "DTCS" and rx_tmode == "DTCS":
        tx_code = int(tx_val or 23)
        rx_code = int(rx_val or 23)
        mem.dtcs_polarity = tx_pol + rx_pol
        if tx_code == rx_code and _supports("DTCS"):
            mem.tmode = "DTCS"
            mem.dtcs  = tx_code
        elif _supports("Cross"):
            mem.tmode      = "Cross"
            mem.cross_mode = "DTCS->DTCS"
            mem.dtcs       = tx_code
            mem.rx_dtcs    = rx_code
        else:
            # Driver doesn't support Cross — best effort: use TX code
            mem.tmode = "DTCS"
            mem.dtcs  = tx_code
    elif tx_tmode == "DTCS" and _supports("Cross"):
        # DTCS-> or DTCS->Tone
        mem.tmode       = "Cross"
        mem.cross_mode  = "DTCS->%s" % (rx_tmode or "")
        mem.dtcs        = int(tx_val or 23)
        mem.dtcs_polarity = tx_pol + rx_pol
        if rx_tmode == "Tone":
            mem.ctone = float(rx_val or 88.5)
    elif rx_tmode == "DTCS" and _supports("Cross"):
        # ->DTCS or Tone->DTCS
        mem.tmode      = "Cross"
        mem.cross_mode = "%s->DTCS" % (tx_tmode or "")
        mem.rx_dtcs    = int(rx_val or 23)
        mem.dtcs_polarity = tx_pol + rx_pol
        if tx_tmode == "Tone":
            mem.rtone = float(tx_val or 88.5)
    elif tx_tmode == "TSQL":
        mem.tmode = "TSQL"
        mem.rtone = float(tx_val or 88.5)
        mem.ctone = float(rx_val or 88.5)
    elif tx_tmode == "Tone":
        mem.tmode = "Tone"
        mem.rtone = float(tx_val or 88.5)
    else:
        mem.tmode = ""


def _channel_dict_into_memory(mem, ch, features):
    """Fill a CHIRP Memory from a channel dict (same logic as upload() loop)."""
    from chirp import chirp_common

    mem.empty = False
    mem.name = ch.get("name", "") or ""
    mem.freq = int(ch.get("freq") or 0)
    mem.offset = int(ch.get("offset") or 0)
    duplex = ch.get("duplex", "") or ""
    valid_duplexes = getattr(features, "valid_duplexes", None)
    if valid_duplexes and duplex not in valid_duplexes:
        duplex = ""
    mem.duplex = duplex
    mode = ch.get("mode", "FM") or "FM"
    valid_modes = getattr(features, "valid_modes", None)
    if valid_modes and mode not in valid_modes:
        mode = "FM"
    mem.mode = mode
    _apply_tones_to_memory(mem, ch, features)
    power_str = ch.get("power", "") or ""
    if power_str and features.valid_power_levels:
        matched = next(
            (p for p in features.valid_power_levels if str(p) == power_str),
            None
        )
        if matched:
            mem.power = matched
    skip = ch.get("skip", "") or ""
    valid_skips = getattr(features, "valid_skips", None)
    if valid_skips and skip not in valid_skips:
        skip = ""
    mem.skip = skip
    extra_dict = ch.get("extra") or {}
    if isinstance(extra_dict, dict):
        for item in getattr(mem, "extra", []) or []:
            try:
                name = item.get_name()
                if name not in extra_dict:
                    continue
                val = extra_dict[name]
                if isinstance(val, str):
                    val = val.strip()
                # RadioSetting has no .set_value; __getattr__("set_value") raises KeyError,
                # and hasattr() would trip that on some Python builds — use .value only.
                vobj = item.value
                if hasattr(vobj, "set_value"):
                    vobj.set_value(val)
                else:
                    item.value = val
            except Exception as e:
                try:
                    ename = item.get_name()
                except Exception:
                    ename = "?"
                LOG.warning("Failed to apply Memory.extra %r: %s", ename, e)


def _drain_pipe(pipe, drain_timeout: float = 0.15):
    """
    Consume any residual bytes left in the pipe's receive buffer.

    Called between sync_in() and sync_out() during upload.  Desktop CHIRP
    closes and reopens the serial port between those two phases, which
    implicitly discards leftover bytes.  Our persistent LocalSocket bridge
    keeps the connection open, so we must drain manually.

    Without this, sync_out()'s _do_ident() handshake reads stale "end of
    session" bytes sent by the radio at the tail of sync_in(), sees an
    unexpected byte in the response, and raises an immediate protocol error
    (e.g. "not the amount of data we want" / "Invalid model").
    """
    old_timeout = pipe.timeout
    try:
        pipe.timeout = drain_timeout
        drained = 0
        while True:
            chunk = pipe.read(256)
            if not chunk:
                break
            drained += len(chunk)
        if drained:
            LOG.debug("_drain_pipe: discarded %d stale byte(s) after sync_in", drained)
    except Exception as e:
        LOG.debug("_drain_pipe: %s (ignored)", e)
    finally:
        pipe.timeout = old_timeout


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

        # Drain residual bytes the radio sent at the end of its sync_in
        # session.  Desktop CHIRP implicitly discards them by closing and
        # reopening the serial port; our persistent socket must do it manually.
        _drain_pipe(radio.pipe)

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

            # Get template from radio so mem.extra has the driver's structure
            mem = radio.get_memory(number)
            mem.empty = False
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
            _apply_tones_to_memory(mem, ch, features)

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

            # ── Driver-specific extra (Memory.extra) ─────────────────────────────
            extra_dict = ch.get("extra") or {}
            if isinstance(extra_dict, dict) and extra_dict:
                _maybe_attach_memory_extra_template(radio, number, mem)
            if isinstance(extra_dict, dict):
                for item in getattr(mem, "extra", []) or []:
                    try:
                        name = item.get_name()
                        if name not in extra_dict:
                            continue
                        val = extra_dict[name]
                        if isinstance(val, str):
                            val = val.strip()
                        if hasattr(item, "set_value"):
                            item.set_value(val)
                        elif hasattr(item, "value") and hasattr(item.value, "set_value"):
                            item.value.set_value(val)
                        else:
                            item.value = val
                    except Exception as e:
                        try:
                            ename = item.get_name()
                        except Exception:
                            ename = "?"
                        LOG.warning(
                            "Failed to apply Memory.extra %r (upload): %s",
                            ename,
                            e,
                        )

            radio.set_memory(mem)

        radio.sync_out()
    except Exception:
        LOG.exception("upload() failed for %s %s on %s", vendor, model, port)
        raise
    finally:
        radio.pipe.close()
