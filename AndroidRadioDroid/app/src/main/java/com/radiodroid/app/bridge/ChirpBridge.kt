package com.radiodroid.app.bridge

import com.chaquo.python.Python
import com.radiodroid.app.model.RadioInfo
import com.radiodroid.app.radio.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
     */
    suspend fun upload(radio: RadioInfo, port: String, channels: List<Channel>) =
        withContext(Dispatchers.IO) {
            // Convert channel list to Python-friendly list of dicts
            val pyList = channels.map { ch ->
                mapOf(
                    "number"   to ch.number,
                    "name"     to ch.name,
                    "freq"     to ch.freqRxHz,
                    "tx_freq"  to ch.freqTxHz,
                    "duplex"   to ch.duplex,
                    "offset"   to ch.offsetHz,
                    "power"    to ch.power,
                    "mode"     to ch.mode,
                    "tx_tone_mode" to (ch.txToneMode ?: ""),
                    "tx_tone_val"  to (ch.txToneVal ?: 0.0),
                    "rx_tone_mode" to (ch.rxToneMode ?: ""),
                    "rx_tone_val"  to (ch.rxToneVal ?: 0.0),
                )
            }
            bridge.callAttr("upload", radio.vendor, radio.model, port, radio.baudRate, pyList)
        }
}
