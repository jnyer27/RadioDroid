package com.radiodroid.app.radio

import com.chaquo.python.PyObject

/**
 * One memory channel (1–198). Mirrors chirp_common.Memory / _channel_to_memory.
 *
 * [mode] and [bandwidth] are universal display values (may be mapped from driver).
 * [driverMode] is the raw value from the CHIRP driver; used on upload so the
 * driver receives the string it expects. When null, upload uses [mode].
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
    var mode: String = "FM",    // Universal display mode (FM, NFM, AM, …)
    var bandwidth: String = "Wide",
    /** Raw mode from the driver; sent to Python on upload. Null = use [mode]. */
    var driverMode: String? = null,
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
    /** Driver-specific params from Memory.extra (name -> value). Populated from download. */
    var extra: Map<String, String> = emptyMap(),
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
         * When [mapping] is non-null and non-empty, [mode] and [bandwidth] are set
         * from the mapping; otherwise NFM→Narrow/FM→Wide and mode is the raw value.
         * [driverMode] is always set to the raw driver value for upload.
         *
         * NOTE: obj["key"] in Kotlin compiles to PyObject.get(String) = Python getattr(),
         * which returns null for dict keys.  Use obj.callAttr("get", "key") to call
         * Python dict.get(key) — the correct lookup for dict-backed objects.
         */
        fun fromPyObject(slotNumber: Int, obj: PyObject, mapping: ParamMapping? = null): Channel {
            val isEmpty = obj.callAttr("get", "empty")?.toString() == "True"
            val rawMode = obj.callAttr("get", "mode")?.toString() ?: "FM"
            val (displayMode, displayBandwidth) = if (mapping != null && mapping.modeMap.isNotEmpty()) {
                Pair(
                    mapping.modeMap[rawMode] ?: rawMode,
                    mapping.bandwidthMap[rawMode] ?: if (rawMode == "NFM" || rawMode == "NAM") "Narrow" else "Wide"
                )
            } else {
                Pair(rawMode, if (rawMode == "NFM" || rawMode == "NAM") "Narrow" else "Wide")
            }
            return Channel(
                number          = obj.callAttr("get", "number")?.toInt() ?: slotNumber,
                empty           = isEmpty,
                freqRxHz        = obj.callAttr("get", "freq")?.toLong() ?: 0L,
                freqTxHz        = obj.callAttr("get", "tx_freq")?.toLong() ?: 0L,
                duplex          = obj.callAttr("get", "duplex")?.toString() ?: "",
                offsetHz        = obj.callAttr("get", "offset")?.toLong() ?: 0L,
                power           = obj.callAttr("get", "power")?.toString() ?: "1",
                name            = obj.callAttr("get", "name")?.toString() ?: "",
                mode            = displayMode,
                bandwidth       = displayBandwidth,
                driverMode      = rawMode,
                txToneMode      = obj.callAttr("get", "tx_tone_mode")?.toString()?.ifEmpty { null },
                txToneVal       = obj.callAttr("get", "tx_tone_val")?.toDouble(),
                txTonePolarity  = obj.callAttr("get", "tx_tone_polarity")?.toString()?.ifEmpty { null },
                rxToneMode      = obj.callAttr("get", "rx_tone_mode")?.toString()?.ifEmpty { null },
                rxToneVal       = obj.callAttr("get", "rx_tone_val")?.toDouble(),
                rxTonePolarity  = obj.callAttr("get", "rx_tone_polarity")?.toString()?.ifEmpty { null },
                extra           = parseExtraFromPyObject(obj),
            )
        }

        /** Build a map from Python dict "extra" (Memory.extra). */
        private fun parseExtraFromPyObject(obj: PyObject): Map<String, String> {
            val extraObj = obj.callAttr("get", "extra") ?: return emptyMap()
            if (extraObj.toString() == "None") return emptyMap()
            return try {
                val keys = extraObj.callAttr("keys")?.asList() ?: return emptyMap()
                keys.mapNotNull { key ->
                    val k = key?.toString() ?: return@mapNotNull null
                    val v = extraObj.callAttr("get", k)?.toString() ?: ""
                    k to v
                }.toMap()
            } catch (_: Throwable) {
                emptyMap()
            }
        }

        /** Build Channel from JSON (e.g. download result from chirp_bridge). */
        fun fromJson(slotNumber: Int, obj: org.json.JSONObject, mapping: ParamMapping? = null): Channel {
            val isEmpty = obj.optString("empty") == "true" || obj.optBoolean("empty", false)
            val rawMode = obj.optString("mode", "FM")
            val (displayMode, displayBandwidth) = if (mapping != null && mapping.modeMap.isNotEmpty()) {
                Pair(
                    mapping.modeMap[rawMode] ?: rawMode,
                    mapping.bandwidthMap[rawMode] ?: if (rawMode == "NFM" || rawMode == "NAM") "Narrow" else "Wide"
                )
            } else {
                Pair(rawMode, if (rawMode == "NFM" || rawMode == "NAM") "Narrow" else "Wide")
            }
            val extra = parseExtraFromJson(obj.optJSONObject("extra"))
            return Channel(
                number          = obj.optInt("number", slotNumber),
                empty           = isEmpty,
                freqRxHz        = obj.optLong("freq", 0L),
                freqTxHz        = obj.optLong("tx_freq", obj.optLong("freq", 0L)),
                duplex          = obj.optString("duplex", ""),
                offsetHz        = obj.optLong("offset", 0L),
                power           = obj.optString("power", "1"),
                name            = obj.optString("name", ""),
                mode            = displayMode,
                bandwidth       = displayBandwidth,
                driverMode      = rawMode,
                txToneMode      = obj.optString("tx_tone_mode", "").ifEmpty { null },
                txToneVal       = if (obj.has("tx_tone_val")) obj.optDouble("tx_tone_val", 0.0) else null,
                txTonePolarity  = obj.optString("tx_tone_polarity", "").ifEmpty { null },
                rxToneMode      = obj.optString("rx_tone_mode", "").ifEmpty { null },
                rxToneVal       = if (obj.has("rx_tone_val")) obj.optDouble("rx_tone_val", 0.0) else null,
                rxTonePolarity  = obj.optString("rx_tone_polarity", "").ifEmpty { null },
                extra           = extra,
            )
        }

        private fun parseExtraFromJson(extraObj: org.json.JSONObject?): Map<String, String> {
            if (extraObj == null) return emptyMap()
            return try {
                extraObj.keys().asSequence().map { k -> k to extraObj.optString(k, "") }.toMap()
            } catch (_: Throwable) {
                emptyMap()
            }
        }
    }
}
