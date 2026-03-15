package com.radiodroid.app.radio

/**
 * Exports a list of [Channel] objects to a CHIRP-compatible CSV string.
 *
 * The CSV header and column ordering matches what [ChirpCsvImporter] expects so that
 * an exported file can be re-imported without loss of data.
 *
 * Tone mapping (inverse of ChirpCsvImporter):
 *   txToneMode=Tone, rxToneMode=Tone  → "TSQL", cToneFreq = rxToneVal, rToneFreq = 88.5
 *   txToneMode=Tone, rxToneMode=null  → "Tone",  rToneFreq = txToneVal, cToneFreq = 88.5
 *   txToneMode=DTCS                   → "DTCS",  DtcsCode = txToneVal formatted as 3-digit,
 *                                                DtcsPolarity = txPol + rxPol
 *   no tone                           → blank Tone, rToneFreq = 88.5, cToneFreq = 88.5
 *
 * Power: stored raw byte string (e.g. "130") passed through as-is; CHIRP accepts numeric power.
 * Location: sequential 0-based index within the export (not the original EEPROM slot number).
 */
object ChirpCsvExporter {

    private const val HEADER =
        "Location,Name,Frequency,Duplex,Offset,Tone,rToneFreq,cToneFreq," +
        "DtcsCode,DtcsPolarity,Mode,TStep,Skip,Comment,URCALL,RPT1CALL,RPT2CALL,DVCODE"

    /**
     * Converts [channels] to a CHIRP CSV string.
     * Empty channels are silently skipped; non-empty channels are numbered from 0.
     */
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

    // ── Row builder ───────────────────────────────────────────────────────────

    private fun channelToCsvRow(location: Int, ch: Channel): String {
        val freqMHz = "%.5f".format(ch.freqRxHz / 1_000_000.0)

        val (duplexCol, offsetCol) = when (ch.duplex) {
            "+"     -> Pair("+",     "%.6f".format(ch.offsetHz  / 1_000_000.0))
            "-"     -> Pair("-",     "%.6f".format(ch.offsetHz  / 1_000_000.0))
            "split" -> Pair("split", "%.6f".format(ch.freqTxHz  / 1_000_000.0))
            else    -> Pair("",      "0.000000")
        }

        val (toneMode, rToneFreq, cToneFreq, dtcsCode, dtcsPol) = resolveTone(ch)

        val mode = when (ch.mode.uppercase()) {
            "AM"  -> "AM"
            "USB" -> "USB"
            else  -> "FM"
        }

        // Power: pass raw value; "N/T" becomes 0 which CHIRP treats as Low
        val power = if (ch.power == "N/T") "0" else ch.power

        return "$location,${csvQuote(ch.name)},$freqMHz,$duplexCol,$offsetCol," +
               "$toneMode,$rToneFreq,$cToneFreq,$dtcsCode,$dtcsPol," +
               "$mode,5.00,,$power,,,,,"
    }

    // ── Tone resolution ───────────────────────────────────────────────────────

    private data class ToneFields(
        val toneMode: String,
        val rToneFreq: String,
        val cToneFreq: String,
        val dtcsCode:  String,
        val dtcsPol:   String
    )

    private fun resolveTone(ch: Channel): ToneFields {
        val noTone = ToneFields("", "88.5", "88.5", "023", "NN")

        return when {
            ch.txToneMode == "DTCS" -> {
                // DCS on TX (and optionally RX)
                val code   = "%03d".format((ch.txToneVal ?: 0.0).toInt())
                val txPol  = ch.txTonePolarity ?: "N"
                val rxPol  = ch.rxTonePolarity ?: ch.txTonePolarity ?: "N"
                ToneFields("DTCS", "88.5", "88.5", code, "$txPol$rxPol")
            }

            ch.txToneMode == "Tone" && ch.rxToneMode == "Tone" -> {
                // Both TX and RX CTCSS → TSQL; cToneFreq = the receive (squelch) tone
                val freq = "%.1f".format(ch.rxToneVal ?: ch.txToneVal ?: 88.5)
                ToneFields("TSQL", "88.5", freq, "023", "NN")
            }

            ch.txToneMode == "Tone" -> {
                // TX CTCSS only → Tone; rToneFreq carries the frequency
                val freq = "%.1f".format(ch.txToneVal ?: 88.5)
                ToneFields("Tone", freq, "88.5", "023", "NN")
            }

            else -> noTone
        }
    }

    // ── CSV helpers ───────────────────────────────────────────────────────────

    private fun csvQuote(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n'))
            "\"${s.replace("\"", "\"\"")}\""
        else s
}
