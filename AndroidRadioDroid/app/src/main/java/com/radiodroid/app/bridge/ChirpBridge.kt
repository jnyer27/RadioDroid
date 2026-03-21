package com.radiodroid.app.bridge

import com.chaquo.python.Python
import com.radiodroid.app.model.ChannelExtraSetting
import com.radiodroid.app.model.RadioFeatures
import com.radiodroid.app.model.RadioInfo
import com.radiodroid.app.radio.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Result of a clone or normal download: channels and optional EEPROM dump (clone-mode).
 */
data class DownloadResult(
    val channels: List<Channel>,
    val eepromBase64: String? = null,
) {
    val isCloneMode: Boolean get() = !eepromBase64.isNullOrBlank()
}

/**
 * Kotlin façade over the Python chirp_bridge module.
 * All calls are dispatched on Dispatchers.IO to avoid blocking the UI thread.
 */
object ChirpBridge {

    private val py by lazy { Python.getInstance() }
    private val bridge by lazy { py.getModule("chirp_bridge") }

    /**
     * Queries the CHIRP driver for [radio] and returns its [RadioFeatures] —
     * the complete set of values and capabilities the driver supports.
     *
     * **No USB connection is needed**: CHIRP drivers expose their features via
     * `get_features()` on a bare (pipe=None) radio instance, so this can be
     * called immediately after the user picks a radio model.
     */
    suspend fun getRadioFeatures(radio: RadioInfo): RadioFeatures =
        withContext(Dispatchers.IO) {
            try {
                val json = bridge.callAttr("get_radio_features", radio.vendor, radio.model)
                    ?.toString() ?: return@withContext RadioFeatures.DEFAULT
                RadioFeatures.fromJson(json)
            } catch (_: Throwable) {
                // Catch Throwable — Chaquopy can surface Python/JVM errors as
                // java.lang.Error subclasses (e.g. if a driver import fails hard).
                RadioFeatures.DEFAULT
            }
        }

    /**
     * Returns the channel-extra schema for [radio]: name, type, options (for list),
     * min/max (for int/float), etc. Used to show Spinner/Switch/number EditText
     * in the channel editor.
     *
     * For clone-mode radios, pass [eepromBase64] from the last download so the
     * driver can load the EEPROM and build mem.extra; without it, get_memory()
     * typically fails and the app falls back to free-text fields.
     *
     * Returns empty list if the driver has no extra or get_memory fails.
     */
    suspend fun getChannelExtraSchema(radio: RadioInfo, eepromBase64: String? = null): List<ChannelExtraSetting> =
        withContext(Dispatchers.IO) {
            try {
                val jsonStr = when {
                    !eepromBase64.isNullOrBlank() ->
                        bridge.callAttr("get_channel_extra_schema", radio.vendor, radio.model, eepromBase64).toString()
                    else ->
                        bridge.callAttr("get_channel_extra_schema", radio.vendor, radio.model).toString()
                }
                val arr = org.json.JSONArray(jsonStr)
                (0 until arr.length()).map { i ->
                    ChannelExtraSetting.fromJson(arr.getJSONObject(i))
                }
            } catch (_: Throwable) {
                emptyList()
            }
        }

    /** Returns all radio models registered in the CHIRP driver directory. */
    fun getRadioList(): List<RadioInfo> =
        bridge.callAttr("get_radio_list")
              .asList()
              .map { RadioInfo.fromPyObject(it) }

    /**
     * True if the driver is clone-mode (full EEPROM); app works off in-memory dump.
     */
    suspend fun isCloneModeRadio(radio: RadioInfo): Boolean =
        withContext(Dispatchers.IO) {
            bridge.callAttr("is_clone_mode_radio", radio.vendor, radio.model).toBoolean()
        }

    /**
     * Downloads the full channel list from the radio. For clone-mode radios also
     * returns the EEPROM dump (base64) so the app can work off a local copy.
     *
     * @param radio Selected radio model
     * @param port  Serial port string
     */
    suspend fun download(radio: RadioInfo, port: String): DownloadResult =
        withContext(Dispatchers.IO) {
            val jsonStr = bridge.callAttr("download", radio.vendor, radio.model, port, radio.baudRate).toString()
            val obj = org.json.JSONObject(jsonStr)
            val arr = obj.getJSONArray("channels")
            val channels = (0 until arr.length()).map { i ->
                Channel.fromJson(i + 1, arr.getJSONObject(i))
            }
            // optString("eeprom_base64") can be "" for JSON null, or literal "null" in some parsers
            val eepromStr = obj.optString("eeprom_base64", "")
            val eepromBase64 = eepromStr.takeIf { it.isNotBlank() && it != "null" }
            DownloadResult(channels = channels, eepromBase64 = eepromBase64)
        }

    /**
     * Dynamically load a custom CHIRP driver .py file from device storage.
     *
     * @param path  Absolute path to the .py file in app-private internal storage.
     * @return      Newly registered [RadioInfo] entries (may be empty if the file
     *              registered no new classes, e.g. a helper-only module).
     * @throws      Exception if the Python module fails to load or register.
     */
    suspend fun loadCustomDriver(path: String): List<RadioInfo> =
        withContext(Dispatchers.IO) {
            bridge.callAttr("load_custom_driver", path)
                  .asList()
                  .map { RadioInfo.fromPyObject(it) }
        }

    /**
     * Uploads modified channels back to the radio.
     *
     * The mode sent to Python is [Channel.driverMode] when set, otherwise [Channel.mode].
     *
     * Channels are serialised to a JSON string rather than passed as a
     * Kotlin List<Map> to avoid Chaquopy Java-proxy conversion issues.
     */
    suspend fun upload(radio: RadioInfo, port: String, channels: List<Channel>) =
        withContext(Dispatchers.IO) {
            val json = JSONArray().also { arr ->
                channels.forEach { ch ->
                    val modeForUpload = ch.driverMode ?: ch.mode
                    arr.put(JSONObject().apply {
                        put("number",            ch.number)
                        put("name",              ch.name)
                        put("freq",              ch.freqRxHz)
                        put("tx_freq",           ch.freqTxHz)
                        put("duplex",            ch.duplex)
                        put("offset",            ch.offsetHz)
                        put("power",             ch.power)
                        put("mode",              modeForUpload)
                        put("tx_tone_mode",      ch.txToneMode      ?: "")
                        put("tx_tone_val",       ch.txToneVal       ?: 0.0)
                        put("tx_tone_polarity",  ch.txTonePolarity  ?: "N")
                        put("rx_tone_mode",      ch.rxToneMode      ?: "")
                        put("rx_tone_val",       ch.rxToneVal       ?: 0.0)
                        put("rx_tone_polarity",  ch.rxTonePolarity  ?: "N")
                        put("empty",             ch.empty)
                        if (ch.extra.isNotEmpty()) {
                            put("extra", JSONObject().apply {
                                ch.extra.forEach { (k, v) -> put(k, v) }
                            })
                        }
                    })
                }
            }.toString()
            bridge.callAttr("upload", radio.vendor, radio.model, port, radio.baudRate, json)
        }

    /**
     * Applies pending settings to a live non-clone radio.
     * Called during Save to Radio after channels have been uploaded.
     * Does NOT call sync_in/sync_out — those are clone-mode operations.
     */
    suspend fun setSettingsLive(radio: RadioInfo, port: String, settingsJson: String) =
        withContext(Dispatchers.IO) {
            bridge.callAttr("set_settings_live", radio.vendor, radio.model, port, radio.baudRate, settingsJson)
        }

    /**
     * Fetches the radio's current settings (requires connection).
     * Returns JSON string: {"settings": [ {"path", "name", "type", "value", ...}, ... ]}.
     */
    suspend fun getRadioSettings(radio: RadioInfo, port: String): String =
        withContext(Dispatchers.IO) {
            bridge.callAttr("get_radio_settings", radio.vendor, radio.model, port, radio.baudRate)
                .toString()
        }

    /**
     * Applies settings to the radio (requires connection).
     * [settingsJson] must be the same structure as returned by [getRadioSettings].
     */
    suspend fun setRadioSettings(radio: RadioInfo, port: String, settingsJson: String) =
        withContext(Dispatchers.IO) {
            bridge.callAttr("set_radio_settings", radio.vendor, radio.model, port, radio.baudRate, settingsJson)
        }

    /**
     * Gets settings from the in-memory EEPROM dump (clone mode). No connection.
     */
    suspend fun getRadioSettingsFromMmap(radio: RadioInfo, eepromBase64: String): String =
        withContext(Dispatchers.IO) {
            bridge.callAttr("get_radio_settings_from_mmap", radio.vendor, radio.model, eepromBase64).toString()
        }

    /**
     * Applies settings to the in-memory EEPROM; returns new eeprom base64 (clone mode).
     */
    suspend fun setRadioSettingsToMmap(radio: RadioInfo, eepromBase64: String, settingsJson: String): String =
        withContext(Dispatchers.IO) {
            bridge.callAttr("set_radio_settings_to_mmap", radio.vendor, radio.model, eepromBase64, settingsJson).toString()
        }

    /**
     * Uploads the in-memory EEPROM dump to the radio (clone mode only).
     */
    suspend fun uploadMmap(radio: RadioInfo, port: String, eepromBase64: String) =
        withContext(Dispatchers.IO) {
            bridge.callAttr("upload_mmap", radio.vendor, radio.model, port, radio.baudRate, eepromBase64)
        }

    /**
     * Loads channels from a raw EEPROM file dump (clone-mode radios only). No radio connection
     * is needed — the driver is instantiated directly from the provided bytes.
     *
     * Returns a [DownloadResult] in the same shape as [download] so the caller can populate
     * [EepromHolder] exactly as if the data had come from a live radio download.
     *
     * @param radio       Selected radio model (must be clone-mode)
     * @param eepromBase64 Base64-encoded raw EEPROM bytes read from a .img / .bin file
     */
    suspend fun loadFromEeprom(radio: RadioInfo, eepromBase64: String): DownloadResult =
        withContext(Dispatchers.IO) {
            val jsonStr = bridge.callAttr("load_from_eeprom", radio.vendor, radio.model, eepromBase64).toString()
            val obj = org.json.JSONObject(jsonStr)
            val arr = obj.getJSONArray("channels")
            val channels = (0 until arr.length()).map { i ->
                Channel.fromJson(i + 1, arr.getJSONObject(i))
            }
            val eepromStr = obj.optString("eeprom_base64", "")
            val eepromBase64Out = eepromStr.takeIf { it.isNotBlank() && it != "null" }
            DownloadResult(channels = channels, eepromBase64 = eepromBase64Out)
        }

    /**
     * Applies a single channel edit to the in-memory clone EEPROM so the raw dump
     * stays in sync with the channel list. Returns new eeprom bytes (base64 decoded).
     */
    suspend fun applyChannelToMmap(radio: RadioInfo, eepromBase64: String, channel: Channel): ByteArray =
        withContext(Dispatchers.IO) {
            val modeForUpload = channel.driverMode ?: channel.mode
            val channelJson = org.json.JSONObject().apply {
                put("number",            channel.number)
                put("name",              channel.name)
                put("freq",              channel.freqRxHz)
                put("tx_freq",           channel.freqTxHz)
                put("duplex",            channel.duplex)
                put("offset",            channel.offsetHz)
                put("power",             channel.power)
                put("mode",              modeForUpload)
                put("tx_tone_mode",      channel.txToneMode      ?: "")
                put("tx_tone_val",       channel.txToneVal       ?: 0.0)
                put("tx_tone_polarity",  channel.txTonePolarity  ?: "N")
                put("rx_tone_mode",      channel.rxToneMode     ?: "")
                put("rx_tone_val",       channel.rxToneVal      ?: 0.0)
                put("rx_tone_polarity",  channel.rxTonePolarity  ?: "N")
                put("empty",             channel.empty)
                if (channel.extra.isNotEmpty()) {
                    put("extra", org.json.JSONObject().apply {
                        channel.extra.forEach { (k, v) -> put(k, v) }
                    })
                }
            }.toString()
            val newB64 = bridge.callAttr("apply_channel_to_mmap", radio.vendor, radio.model, eepromBase64, channelJson).toString()
            android.util.Base64.decode(newB64, android.util.Base64.NO_WRAP)
        }

    /**
     * Re-applies every channel in [channels] to the clone-mode mmap so raw EEPROM bytes match
     * that list. Needed because [com.radiodroid.app.radio.EepromParser.writeChannel]
     * only updates the in-memory list, not the byte image — bulk edits and CSV import would
     * otherwise leave [initialEeprom] stale while the list reflects user changes.
     *
     * No-op if [initialEeprom] is empty or [radio] is not clone-mode.
     */
    suspend fun syncCloneMmapToChannelList(
        radio: RadioInfo,
        initialEeprom: ByteArray,
        channels: List<Channel>,
    ): ByteArray {
        if (initialEeprom.isEmpty()) return initialEeprom
        if (!isCloneModeRadio(radio)) return initialEeprom
        var b64 = android.util.Base64.encodeToString(initialEeprom, android.util.Base64.NO_WRAP)
        for (ch in channels.sortedBy { it.number }) {
            val bytes = applyChannelToMmap(radio, b64, ch)
            b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }
        return android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
    }
}
