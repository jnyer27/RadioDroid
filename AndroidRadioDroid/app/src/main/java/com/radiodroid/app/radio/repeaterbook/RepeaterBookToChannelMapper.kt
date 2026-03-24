package com.radiodroid.app.radio.repeaterbook

import com.radiodroid.app.radio.Channel
import org.json.JSONObject
import kotlin.math.abs

/**
 * Maps one RepeaterBook [export.php] JSON record (field names with spaces, per hamkit / RB format)
 * into a [Channel] suitable for [com.radiodroid.app.radio.ChirpCsvExporter].
 */
object RepeaterBookToChannelMapper {

    fun fromJson(obj: JSONObject): Channel? {
        val rxMhz = obj.optFreqMhz("Frequency") ?: return null
        val rxHz = (rxMhz * 1_000_000.0).toLong()
        val txMhz = obj.optFreqMhz("Input Freq") ?: rxMhz
        val txHz = (txMhz * 1_000_000.0).toLong()
        val diff = txHz - rxHz

        val (duplex, offsetHz, freqTxFinal) = when {
            abs(diff) < 500L -> Triple("", 0L, rxHz)
            abs(diff) <= 9_000_000L && txHz > rxHz ->
                Triple("+", diff, txHz)
            abs(diff) <= 9_000_000L ->
                Triple("-", -diff, txHz)
            else ->
                Triple("split", 0L, txHz)
        }

        val pl = obj.optStringOrNull("PL")
        val tsq = obj.optStringOrNull("TSQ")
        val (txToneMode, txToneVal, txPol, rxToneMode, rxToneVal, rxPol) =
            mapTones(pl, tsq)

        val callsign = obj.optStringOrNull("Callsign").orEmpty()
        val city = obj.optStringOrNull("Nearest City").orEmpty()
        val name = (callsign.ifBlank { city }).take(12)

        val mode = if (obj.optBool("FM Analog") == true || obj.optBool("DMR") != true) {
            "FM"
        } else {
            "FM"
        }

        return Channel(
            number = 0,
            empty = false,
            freqRxHz = rxHz,
            freqTxHz = freqTxFinal,
            duplex = duplex,
            offsetHz = offsetHz,
            power = "1",
            name = name,
            mode = mode,
            bandwidth = "Wide",
            driverMode = mode,
            txToneMode = txToneMode,
            txToneVal = txToneVal,
            txTonePolarity = txPol,
            rxToneMode = rxToneMode,
            rxToneVal = rxToneVal,
            rxTonePolarity = rxPol,
        )
    }

    /** Subtitle line for CHIRP import comment column. */
    fun commentLine(obj: JSONObject): String = buildString {
        obj.optStringOrNull("Nearest City")?.let { append(it) }
        obj.optStringOrNull("State")?.let {
            if (isNotEmpty()) append(", ")
            append(it)
        }
        obj.optStringOrNull("County")?.let {
            if (isNotEmpty()) append(" · ")
            append(it)
        }
    }

    private fun mapTones(
        pl: String?,
        tsq: String?,
    ): ToneTuple {
        val plHz = parseCtcssHz(pl)
        val tsqHz = parseCtcssHz(tsq)
        return when {
            plHz != null && tsqHz != null && abs(plHz - tsqHz) < 0.05 ->
                ToneTuple("Tone", plHz, null, "Tone", plHz, null)
            plHz != null && tsqHz != null ->
                ToneTuple("Tone", plHz, null, null, null, null)
            plHz != null ->
                ToneTuple("Tone", plHz, null, null, null, null)
            tsqHz != null ->
                ToneTuple("Tone", tsqHz, null, "Tone", tsqHz, null)
            else ->
                ToneTuple(null, null, null, null, null, null)
        }
    }

    private data class ToneTuple(
        val txToneMode: String?,
        val txToneVal: Double?,
        val txPol: String?,
        val rxToneMode: String?,
        val rxToneVal: Double?,
        val rxPol: String?,
    )

    /**
     * Picks a plausible CTCSS frequency (Hz) from RB strings such as "107.2" or "CC 1 114.8".
     */
    private fun parseCtcssHz(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()
        if (s.contains("DCS", ignoreCase = true)) return null
        if (Regex("^D\\d{2,3}$", RegexOption.IGNORE_CASE).matches(s)) return null
        val tokens = s.split(Regex("\\s+"))
        for (t in tokens.asReversed()) {
            val d = t.toDoubleOrNull() ?: continue
            if (d in 60.0..300.0) return d
        }
        return null
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || JSONObject.NULL == get(key)) return null
        val s = optString(key, "").trim()
        return s.ifEmpty { null }
    }

    private fun JSONObject.optFreqMhz(key: String): Double? {
        if (!has(key) || JSONObject.NULL == get(key)) return null
        return when (val v = get(key)) {
            is String -> v.trim().toDoubleOrNull()
            is Number -> v.toDouble()
            else -> null
        }
    }

    private fun JSONObject.optBool(key: String): Boolean? {
        if (!has(key) || JSONObject.NULL == get(key)) return null
        val v = get(key)
        return when (v) {
            is Boolean -> v
            is String -> when (v.trim().lowercase()) {
                "yes", "true", "1", "t" -> true
                "no", "false", "0", "f" -> false
                else -> null
            }
            is Number -> v.toInt() != 0
            else -> null
        }
    }
}

