package com.radiodroid.app.bridge

import com.chaquo.python.Python
import com.radiodroid.app.model.RadioInfo
import com.radiodroid.app.radio.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kotlin façade over the Python chirp_bridge module.
 * All calls are dispatched on Dispatchers.IO to avoid blocking the UI thread.
 */
object ChirpBridge {

    private val py by lazy { Python.getInstance() }
    private val bridge by lazy { py.getModule("chirp_bridge") }

    /** Returns all radio models registered in the CHIRP driver directory. */
    fun getRadioList(): List<RadioInfo> =
        bridge.callAttr("get_radio_list")
              .asList()
              .map { RadioInfo.fromPyObject(it) }

    /**
     * Downloads the full channel list from the radio.
     * @param radio  Selected radio model
     * @param port   Serial port string: "/dev/ttyUSB0" for USB OTG,
     *               "ble:///tmp/radiodroid_ble.sock" for BLE socket bridge
     */
    suspend fun download(radio: RadioInfo, port: String): List<Channel> =
        withContext(Dispatchers.IO) {
            bridge.callAttr("download", radio.vendor, radio.model, port, radio.baudRate)
                  .asList()
                  .mapIndexed { idx, obj -> Channel.fromPyObject(idx + 1, obj) }
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
     * Channels are serialised to a JSON string rather than passed as a
     * Kotlin List<Map> to avoid Chaquopy Java-proxy conversion issues:
     *  - Kotlin List<Map<String,Any>> arrives in Python as a Java ArrayList
     *    of LinkedHashMaps; Java Map is not directly iterable in Python,
     *    so dict(ch) / "for k in ch" fail with TypeError.
     *  - Passing a plain JSON string and parsing with json.loads() gives
     *    native Python dicts immediately — no proxy layer involved.
     */
    suspend fun upload(radio: RadioInfo, port: String, channels: List<Channel>) =
        withContext(Dispatchers.IO) {
            val json = JSONArray().also { arr ->
                channels.forEach { ch ->
                    arr.put(JSONObject().apply {
                        put("number",       ch.number)
                        put("name",         ch.name)
                        put("freq",         ch.freqRxHz)
                        put("tx_freq",      ch.freqTxHz)
                        put("duplex",       ch.duplex)
                        put("offset",       ch.offsetHz)
                        put("power",        ch.power)
                        put("mode",         ch.mode)
                        put("tx_tone_mode", ch.txToneMode ?: "")
                        put("tx_tone_val",  ch.txToneVal  ?: 0.0)
                        put("rx_tone_mode", ch.rxToneMode ?: "")
                        put("rx_tone_val",  ch.rxToneVal  ?: 0.0)
                        put("empty",        ch.empty)
                    })
                }
            }.toString()
            bridge.callAttr("upload", radio.vendor, radio.model, port, radio.baudRate, json)
        }
}
