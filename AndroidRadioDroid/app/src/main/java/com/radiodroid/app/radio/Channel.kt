package com.radiodroid.app.radio

import com.chaquo.python.PyObject

/**
 * One memory channel (1–198). Mirrors chirp_common.Memory / _channel_to_memory.
 */
data class Channel(
    val number: Int,           // 1..198
    var empty: Boolean = true,
    var freqRxHz: Long = 0,
    var freqTxHz: Long = 0,
    var duplex: String = "",    // "", "+", "-", "split"
    var offsetHz: Long = 0,
    var power: String = "1",    // "N/T" or "1".."255"
    var name: String = "",
    var mode: String = "FM",    // Auto, FM, AM, USB
    var bandwidth: String = "Wide",
    var txToneMode: String? = null,  // "Tone", "DTCS", null
    var txToneVal: Double? = null,
    var txTonePolarity: String? = null,
    var rxToneMode: String? = null,
    var rxToneVal: Double? = null,
    var rxTonePolarity: String? = null,
    var group1: String = "None",
    var group2: String = "None",
    var group3: String = "None",
    var group4: String = "None",
    var busyLock: Boolean = false,
    /** Raw flags byte (byte 15 of channel struct). Preserves bits 3-6 (position, pttID, reversed)
     *  so a round-trip write doesn't clobber per-channel flags set by the radio firmware. */
    var flagsRaw: Int = 0,
) {
    fun displayFreq(): String = if (empty) "" else "%.4f".format(freqRxHz / 1_000_000.0)
    fun displayDuplex(): String = when {
        empty -> ""
        duplex == "+" -> "+${offsetHz / 1000}kHz"
        duplex == "-" -> "-${offsetHz / 1000}kHz"
        duplex == "split" -> "Split"
        else -> ""
    }

    fun displayTxTone(): String = formatTone(txToneMode, txToneVal, txTonePolarity)
    fun displayRxTone(): String = formatTone(rxToneMode, rxToneVal, rxTonePolarity)

    /** Returns active group letters space-separated, e.g. "A B" — empty string if all None. */
    fun displayGroups(): String = listOf(group1, group2, group3, group4)
        .filter { it != "None" }
        .joinToString("  ")

    private fun formatTone(mode: String?, value: Double?, polarity: String?): String = when (mode) {
        "Tone" -> "%.1f Hz".format(value ?: 0.0)
        "DTCS" -> "%03d %s".format((value ?: 0.0).toInt(), polarity ?: "N")
        else   -> ""
    }

    companion object {
        /**
         * Construct a Channel from a Python dict returned by chirp_bridge.download().
         *
         * NOTE: obj["key"] in Kotlin compiles to PyObject.get(String) = Python getattr(),
         * which returns null for dict keys.  Use obj.callAttr("get", "key") to call
         * Python dict.get(key) — the correct lookup for dict-backed objects.
         */
        fun fromPyObject(slotNumber: Int, obj: PyObject): Channel {
            // Python returns a bool (True/False) for "empty"; Chaquopy refuses to
            // convert Python bool → Java int via toInt() ("could not convert boolean
            // object to int"), so use toString() comparison instead.
            val isEmpty = obj.callAttr("get", "empty")?.toString() == "True"
            // CHIRP encodes narrow FM as mode="NFM"; there is no separate bandwidth
            // field in the Memory object.  Map NFM→Narrow so the adapter can show "N".
            val rawMode   = obj.callAttr("get", "mode")?.toString() ?: "FM"
            val bandwidth = if (rawMode == "NFM") "Narrow" else "Wide"
            return Channel(
                number          = obj.callAttr("get", "number")?.toInt() ?: slotNumber,
                empty           = isEmpty,
                freqRxHz        = obj.callAttr("get", "freq")?.toLong() ?: 0L,
                freqTxHz        = obj.callAttr("get", "tx_freq")?.toLong() ?: 0L,
                duplex          = obj.callAttr("get", "duplex")?.toString() ?: "",
                offsetHz        = obj.callAttr("get", "offset")?.toLong() ?: 0L,
                power           = obj.callAttr("get", "power")?.toString() ?: "1",
                name            = obj.callAttr("get", "name")?.toString() ?: "",
                mode            = rawMode,
                bandwidth       = bandwidth,
                txToneMode      = obj.callAttr("get", "tx_tone_mode")?.toString()?.ifEmpty { null },
                txToneVal       = obj.callAttr("get", "tx_tone_val")?.toDouble(),
                txTonePolarity  = obj.callAttr("get", "tx_tone_polarity")?.toString()?.ifEmpty { null },
                rxToneMode      = obj.callAttr("get", "rx_tone_mode")?.toString()?.ifEmpty { null },
                rxToneVal       = obj.callAttr("get", "rx_tone_val")?.toDouble(),
                rxTonePolarity  = obj.callAttr("get", "rx_tone_polarity")?.toString()?.ifEmpty { null },
            )
        }
    }
}
