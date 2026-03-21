package com.radiodroid.app

import com.radiodroid.app.model.ChannelExtraSetting
import com.radiodroid.app.model.RadioFeatures
import com.radiodroid.app.model.RadioInfo
import com.radiodroid.app.radio.Channel

// ── Data classes retained for source-compatibility with nicFW-derived code ───

/**
 * Per-radio calibration stubs.  nicFW used these to cap TX power; RadioDroid
 * leaves both limits at 255 (no cap) because CHIRP drivers manage power levels.
 */
data class TuneSettings(
    val maxPowerSettingVHF: Int = 255,
    val maxPowerSettingUHF: Int = 255,
)

/**
 * General radio settings stub.  nicFW stored these in EEPROM; RadioDroid
 * exposes them via CHIRP get_settings() in a future phase.  The data class
 * exists only so existing call-sites compile without changes.
 */
data class RadioSettings(
    val squelch: Int = 0,
)

// ── Application-level memory holder ──────────────────────────────────────────

/**
 * Application-level singleton holding the current radio memory so that
 * ChannelEditActivity and ChirpImportActivity can
 * read and write the same channel list without passing it through Intents.
 *
 * In RadioDroid the "memory" is a List<Channel> populated by ChirpBridge
 * after a successful radio download (sync_in).  There is no raw EEPROM byte
 * array — [eeprom] is kept only so legacy call-sites that null-check it still
 * compile; it is set to a non-null sentinel after a successful download.
 */
object EepromHolder {

    /**
     * Non-null sentinel written after a successful download so that legacy
     * `if (eeprom == null)` guards behave correctly.  The content of the
     * byte array is not used by RadioDroid.
     */
    var eeprom: ByteArray? = null

    /**
     * The current channel list (slots 1-based, indices 0-based).
     * Populated by MainActivity after ChirpBridge.download() returns.
     * ChannelEditActivity and ChirpImportActivity write directly into this list.
     */
    var channels: MutableList<Channel> = mutableListOf()

    /**
     * Decoded group labels (A–O, up to 15 items).
     * Empty string means the label is blank.
     * Populated from the radio; editable via Radio Settings (driver get_settings/set_settings).
     */
    var groupLabels: List<String> = List(15) { "" }

    /**
     * Names of driver-specific channel params (from Memory.extra).
     * Derived from the first channel with non-empty [Channel.extra] after download.
     * Used to show dynamic "Radio-specific" fields in the channel editor and list.
     */
    var extraParamNames: List<String> = emptyList()

    /**
     * Schema for channel extra params (name, type, options for list, min/max, etc.).
     * Fetched via ChirpBridge.getChannelExtraSchema() after download; used by
     * ChannelEditActivity to show Spinner/Switch/number EditText instead of free-text.
     * Empty when the driver has no extra or get_memory(pipe=None) fails.
     */
    var channelExtraSchema: List<ChannelExtraSetting> = emptyList()

    /**
     * Band-plan stubs — RadioDroid does not yet download band plans from
     * the radio.  Stored as List<Any> so ChannelAdapter's legacy guard
     * (`if (bp.isNotEmpty())`) stays false and falls back to
     * EepromConstants.isTxRestricted().
     */
    var bandPlan: List<Any> = emptyList()

    /**
     * Scan-preset stubs (nicFW-specific concept; not used in RadioDroid Phase 1).
     */
    var scanPresets: List<Any> = emptyList()

    /**
     * Tune-settings stub — CHIRP manages TX power via driver power levels.
     * maxPowerSetting* = 255 means "no cap"; the ChannelAdapter excess-cap
     * warning will never fire for CHIRP string power values.
     */
    var tuneSettings: TuneSettings = TuneSettings()

    /**
     * Radio-settings stub — populated in a future phase via CHIRP get_settings().
     */
    var radioSettings: RadioSettings = RadioSettings()

    /**
     * Pending radio settings for non-clone radios.
     * Set by RadioSettingsActivity when the user saves settings; cleared by
     * MainActivity after a successful Save to Radio.
     * Null means no pending settings change.
     * Clone-mode radios do not use this — their settings are baked into [eeprom].
     */
    var pendingSettingsJson: String? = null

    /**
     * CHIRP driver capabilities for the currently selected radio.
     * Populated by MainActivity when the user selects a radio model, so
     * ChannelEditActivity can show only the fields the driver supports.
     * Falls back to [RadioFeatures.DEFAULT] until a real radio is chosen.
     */
    var radioFeatures: RadioFeatures = RadioFeatures.DEFAULT

    /**
     * Currently selected radio (vendor/model). Set by MainActivity when the user
     * picks a model and when download completes, so Parameter Mapping and
     * ChannelEditActivity can resolve mode/bandwidth mapping for upload.
     */
    var selectedRadio: RadioInfo? = null
}
