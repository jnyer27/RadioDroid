# Copyright 2026 RadioDroid — chirp_bridge mmap validation (clone apply + validate API)
import base64
import json
import os
import sys
import unittest

_UNIT_DIR = os.path.dirname(os.path.abspath(__file__))
_PY_ROOT = os.path.abspath(os.path.join(_UNIT_DIR, "..", "..", ".."))
if _PY_ROOT not in sys.path:
    sys.path.insert(0, _PY_ROOT)

import chirp_bridge  # noqa: E402


def _h3_eeprom_b64():
    data = bytearray(8192)
    data[0x1900] = 0xD8
    data[0x1901] = 0x2F
    f10 = 14652000
    data[0x40:0x44] = f10.to_bytes(4, "big")
    data[0x44:0x48] = f10.to_bytes(4, "big")
    return base64.b64encode(bytes(data)).decode()


def _channel_json(number=1, mode="NFM", extra=None):
    d = {
        "number": number,
        "name": "T",
        "freq": 146520000,
        "tx_freq": 146520000,
        "duplex": "",
        "offset": 0,
        "power": "1",
        "mode": mode,
        "tx_tone_mode": "",
        "tx_tone_val": 0.0,
        "tx_tone_polarity": "N",
        "rx_tone_mode": "",
        "rx_tone_val": 0.0,
        "rx_tone_polarity": "N",
        "empty": False,
    }
    if extra is not None:
        d["extra"] = extra
    return json.dumps(d)


VENDOR = "TIDRADIO"
MODEL = "TD-H3 nicFW 2.5"


class TestApplyChannelToMmapValidation(unittest.TestCase):
    def test_nfm_wide_raises_before_write(self):
        b64 = _h3_eeprom_b64()
        bad = _channel_json(mode="NFM", extra={"bandwidth": "Wide"})
        with self.assertRaises(ValueError):
            chirp_bridge.apply_channel_to_mmap(VENDOR, MODEL, b64, bad)
        good = _channel_json(mode="NFM", extra={"bandwidth": "Narrow"})
        raw_in = base64.b64decode(b64)
        out_b64 = chirp_bridge.apply_channel_to_mmap(VENDOR, MODEL, b64, good)
        self.assertNotEqual(base64.b64decode(out_b64), raw_in)

    def test_validate_channel_dict_json_errors(self):
        b64 = _h3_eeprom_b64()
        bad = _channel_json(mode="NFM", extra={"bandwidth": "Wide"})
        arr = json.loads(chirp_bridge.validate_channel_dict(VENDOR, MODEL, b64, bad))
        self.assertTrue(any(x.get("kind") == "error" for x in arr))


if __name__ == "__main__":
    unittest.main()
