# Copyright 2026 RadioDroid — Memory.extra bool coercion (Busy Lock / JSON strings)
import os
import sys
import unittest

# Repo: AndroidRadioDroid/app/src/main/python/{chirp_bridge.py, tests/unit/this file}
_UNIT_DIR = os.path.dirname(os.path.abspath(__file__))
_PY_ROOT = os.path.abspath(os.path.join(_UNIT_DIR, "..", ".."))
if _PY_ROOT not in sys.path:
    sys.path.insert(0, _PY_ROOT)

from chirp import settings as chirp_settings  # noqa: E402
import chirp_bridge  # noqa: E402


class TestCoerceExtraBool(unittest.TestCase):
    def test_string_false_is_false(self):
        v = chirp_settings.RadioSettingValueBoolean(False)
        c = chirp_bridge._coerce_extra_value_for_chirp(v, "False")
        self.assertIs(c, False)
        v.set_value(c)
        self.assertFalse(v.get_value())

    def test_string_true_is_true(self):
        v = chirp_settings.RadioSettingValueBoolean(False)
        c = chirp_bridge._coerce_extra_value_for_chirp(v, "True")
        self.assertIs(c, True)

    def test_lowercase_false(self):
        v = chirp_settings.RadioSettingValueBoolean(True)
        c = chirp_bridge._coerce_extra_value_for_chirp(v, "false")
        self.assertIs(c, False)

    def test_raw_set_value_string_false_was_broken(self):
        """Document pre-fix bug: bool('False') is True in Python."""
        v = chirp_settings.RadioSettingValueBoolean(False)
        v.set_value("False")
        self.assertTrue(v.get_value(), "CHIRP API quirk: proves coercion is required")

    def test_non_bool_unchanged(self):
        class _V:
            pass

        x = _V()
        self.assertIs(chirp_bridge._coerce_extra_value_for_chirp(x, "hello"), "hello")


if __name__ == "__main__":
    unittest.main()
