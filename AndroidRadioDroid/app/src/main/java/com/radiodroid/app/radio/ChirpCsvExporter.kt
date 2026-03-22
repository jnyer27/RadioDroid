package com.radiodroid.app.radio

import java.util.Locale

/**
 * Exports a list of [Channel] objects to a CHIRP-compatible CSV string.
 *
 * Column names and order follow [chirp_common.Memory.CSV_FORMAT] in the bundled CHIRP tree
 * (`chirp/chirp_common.py`). Row formatting aligns with [chirp_common.Memory.to_csv] for
 * standard (non–D-STAR) memories: frequencies use CHIRP `format_freq` (MHz + 6-digit fraction),
 * [Mode] values are drawn from [chirp_common.MODES].
 *
 * Tone mapping (inverse of [ChirpCsvImporter]):
 *   txToneMode=Tone, rxToneMode=Tone  → "TSQL", cToneFreq = rxToneVal, rToneFreq = 88.5
 *   txToneMode=Tone, rxToneMode=null  → "Tone",  rToneFreq = txToneVal, cToneFreq = 88.5
 *   txToneMode=DTCS (same RX/TX code) → "DTCS", DtcsCode, RxDtcsCode same, CrossMode Tone->Tone
 *   txToneMode=DTCS, rxToneMode=DTCS, different codes → "Cross", CrossMode DTCS->DTCS
 *   no tone                           → blank Tone, CHIRP sentinels for tone columns
 *
 * Power: "N/T" is exported as "0" (CHIRP low / no-TX style). Location: sequential 0-based
 * index within the export (not the original EEPROM slot number).
 */
object ChirpCsvExporter {

    /**
     * Mirrors `chirp_common.MODES` — authoritative set for the CSV Mode column.
     */
    private val CHIRP_MODES: Set<String> = setOf(
        "WFM", "FM", "NFM", "AM", "NAM", "DV", "USB", "LSB", "CW", "RTTY",
        "DIG", "PKT", "NCW", "NCWR", "CWR", "P25", "Auto", "RTTYR",
        "FSK", "FSKR", "DMR", "DN"
    )

    /** Same order as `Memory.CSV_FORMAT` in chirp_common.py (non-DV). */
    private const val HEADER =
        "Location,Name,Frequency,Duplex,Offset,Tone,rToneFreq,cToneFreq," +
            "DtcsCode,DtcsPolarity,RxDtcsCode,CrossMode," +
            "Mode,TStep,Skip,Power,Comment,URCALL,RPT1CALL,RPT2CALL,DVCODE"

    fun export(channels: List<Channel>): String {
        val sb = StringBuilder()
        sb.appendLine(HEADER)
        var location = 0
        for (ch in channels) {
            if (ch.empty) continue
            sb.appendLine(channelToCsvRow(location++, ch))
        }
        return sb.toString()
    }

    /** CHIRP `format_freq(freq_hz)` — `"%d.%06d" % (mhz, frac)`. */
    private fun formatChirpFreqHz(hz: Long): String {
        val f = kotlin.math.abs(hz)
        val mhz = f / 1_000_000L
        val frac = (f % 1_000_000L).toInt()
        val sign = if (hz < 0) "-" else ""
        return String.format(Locale.US, "%s%d.%06d", sign, mhz, frac)
    }

    private fun channelToCsvRow(location: Int, ch: Channel): String {
        val freqStr = formatChirpFreqHz(ch.freqRxHz)

        val (duplexCol, offsetStr) = when (ch.duplex) {
            "+"     -> Pair("+",     formatChirpFreqHz(ch.offsetHz))
            "-"     -> Pair("-",     formatChirpFreqHz(ch.offsetHz))
            "split" -> Pair("split", formatChirpFreqHz(ch.freqTxHz))
            else    -> Pair("",      formatChirpFreqHz(0L))
        }

        val t = resolveChirpToneCsv(ch)

        val modeRaw = (ch.driverMode ?: ch.mode).trim().ifEmpty { "FM" }
        val modeUpper = modeRaw.uppercase(Locale.US)
        val modeOut = if (modeUpper in CHIRP_MODES) modeUpper else "FM"

        val power = if (ch.power == "N/T") "0" else ch.power

        val parts = listOf(
            location.toString(),
            csvQuote(ch.name),
            freqStr,
            duplexCol,
            offsetStr,
            t.tone,
            t.rToneFreq,
            t.cToneFreq,
            t.dtcsCode,
            t.dtcsPolarity,
            t.rxDtcsCode,
            t.crossMode,
            modeOut,
            "5.00",
            "",
            power,
            "",
            "", "", "", ""
        )
        return parts.joinToString(",")
    }

    private data class ChirpToneCsv(
        val tone: String,
        val rToneFreq: String,
        val cToneFreq: String,
        val dtcsCode: String,
        val dtcsPolarity: String,
        val rxDtcsCode: String,
        val crossMode: String,
    )

    private fun resolveChirpToneCsv(ch: Channel): ChirpToneCsv {
        val none = ChirpToneCsv("", "88.5", "88.5", "023", "NN", "023", "Tone->Tone")

        return when {
            ch.txToneMode == "DTCS" && ch.rxToneMode == "DTCS" -> {
                val txC = (ch.txToneVal ?: 23.0).toInt().coerceIn(0, 999)
                val rxC = (ch.rxToneVal ?: ch.txToneVal ?: 23.0).toInt().coerceIn(0, 999)
                val txPol = (ch.txTonePolarity ?: "N").take(1)
                val rxPol = (ch.rxTonePolarity ?: ch.txTonePolarity ?: "N").take(1)
                val pol = "$txPol$rxPol"
                val txS = "%03d".format(txC)
                val rxS = "%03d".format(rxC)
                if (txC == rxC) {
                    ChirpToneCsv("DTCS", "88.5", "88.5", txS, pol, rxS, "Tone->Tone")
                } else {
                    ChirpToneCsv("Cross", "88.5", "88.5", txS, pol, rxS, "DTCS->DTCS")
                }
            }

            ch.txToneMode == "DTCS" -> {
                val code = "%03d".format((ch.txToneVal ?: 0.0).toInt().coerceIn(0, 999))
                val txPol = ch.txTonePolarity ?: "N"
                val rxPol = ch.rxTonePolarity ?: ch.txTonePolarity ?: "N"
                ChirpToneCsv("DTCS", "88.5", "88.5", code, "$txPol$rxPol", code, "DTCS->")
            }

            ch.txToneMode == "Tone" && ch.rxToneMode == "Tone" -> {
                val freq = "%.1f".format(ch.rxToneVal ?: ch.txToneVal ?: 88.5)
                ChirpToneCsv("TSQL", "88.5", freq, "023", "NN", "023", "Tone->Tone")
            }

            ch.txToneMode == "Tone" -> {
                val freq = "%.1f".format(ch.txToneVal ?: 88.5)
                ChirpToneCsv("Tone", freq, "88.5", "023", "NN", "023", "Tone->Tone")
            }

            else -> none
        }
    }

    private fun csvQuote(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n'))
            "\"${s.replace("\"", "\"\"")}\""
        else s
}
