# Tests for the Android bridge's apply_setting flow.
# Run from app/src/main/python so chirp_bridge is on the path:
#   cd AndroidRadioDroid/app/src/main/python && python -m pytest chirp/tests/unit/test_apply_setting_bridge.py -v
# Or without pytest:  python chirp/tests/unit/test_apply_setting_bridge.py

import os
import sys
# When run as script, ensure app Python root (parent of chirp/) is on path for chirp_bridge
if __name__ == '__main__':
    _root = os.path.normpath(os.path.join(os.path.dirname(__file__), '..', '..', '..'))
    if _root not in sys.path:
        sys.path.insert(0, _root)

import unittest


class TestApplySettingsViaDriver(unittest.TestCase):
    """Test _apply_settings_via_driver calls radio.apply_setting(name, value)."""

    def test_calls_apply_setting_for_each_item(self):
        try:
            import chirp_bridge as bridge
        except ImportError:
            self.skipTest('chirp_bridge not on path (run from app/src/main/python)')
        applied = []

        class MockRadio:
            def apply_setting(self, name, value):
                applied.append((name, value))

        settings_list = [
            {'path': 'root/display/lcdBrightness', 'value': 27},
            {'path': 'root/general/tuning_step', 'value': '25.0 kHz'},
        ]
        bridge._apply_settings_via_driver(MockRadio(), settings_list)
        self.assertEqual(applied, [
            ('lcdBrightness', 27),
            ('tuning_step', '25.0 kHz'),
        ])

    def test_fallback_to_apply_setting_to_settings(self):
        try:
            import chirp_bridge as bridge
        except ImportError:
            self.skipTest('chirp_bridge not on path (run from app/src/main/python)')
        applied = []

        class MockRadio:
            def apply_setting_to_settings(self, name, value):
                applied.append(('legacy', name, value))

        settings_list = [{'path': 'display/foo', 'value': 1}]
        bridge._apply_settings_via_driver(MockRadio(), settings_list)
        self.assertEqual(applied, [('legacy', 'foo', 1)])

    def test_skips_missing_path_or_value(self):
        try:
            import chirp_bridge as bridge
        except ImportError:
            self.skipTest('chirp_bridge not on path (run from app/src/main/python)')
        applied = []

        class MockRadio:
            def apply_setting(self, name, value):
                applied.append((name, value))

        settings_list = [
            {'path': 'a', 'value': 1},
            {'value': 2},
            {'path': 'c'},
        ]
        bridge._apply_settings_via_driver(MockRadio(), settings_list)
        self.assertEqual(applied, [('a', 1)])

    def test_no_call_when_no_apply_method(self):
        try:
            import chirp_bridge as bridge
        except ImportError:
            self.skipTest('chirp_bridge not on path (run from app/src/main/python)')

        class MockRadio:
            pass

        settings_list = [{'path': 'x', 'value': 1}]
        bridge._apply_settings_via_driver(MockRadio(), settings_list)
        # Should not raise; MockRadio has no apply_setting


if __name__ == '__main__':
    unittest.main()
