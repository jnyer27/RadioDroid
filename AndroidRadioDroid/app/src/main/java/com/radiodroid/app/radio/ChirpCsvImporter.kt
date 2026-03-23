package com.radiodroid.app.radio

import java.util.Locale

/**
 * Parses a CHIRP-format CSV export (e.g. from RepeaterBook or desktop CHIRP) into a list of
 * [ChirpEntry] objects ready to be mapped into empty EEPROM slots.
 *
 * Headers are matched by name (case-insensitive). When present, column order follows
 * [chirp_common.Memory.CSV_FORMAT] in the bundled CHIRP tree (`chirp/chirp_common.py`).
 *
 * **Mode** values are accepted per [chirp_common.MODES] (including **NFM** / **NAM** for
 * narrow FM/AM). Unknown modes fall back to **FM**.
 *
 * Tone mapping:
 *   ""     → no TX or RX tone; EXCEPT when rToneFreq ≠ 88.5 (CHIRP sentinel), treat as TX Tone.
 *   "Tone" → TX CTCSS only (rToneFreq)
 *   "TSQL" → TX + RX CTCSS (cToneFreq)
 *   "DTCS" → TX + RX DCS (DtcsCode; RxDtcsCode when present)
 *   "Cross" + CrossMode **DTCS->DTCS** → different TX/RX DCS from DtcsCode / RxDtcsCode
 *
 * DCS: CHIRP "023" → parseInt 23.
 */
object ChirpCsvImporter {

    /** Mirrors `chirp_common.MODES`. */
    private val CHIRP_MODES: Set<String> = setOf(
        "WFM", "FM", "NFM", "AM", "NAM", "DV", "USB", "LSB", "CW", "RTTY",
        "DIG", "PKT", "NCW", "NCWR", "CWR", "P25", "Auto", "RTTYR",
        "FSK", "FSKR", "DMR", "DN"
    )

    data class ChirpEntry(
        val csvLocation: Int,
        val channel: Channel
    )

    fun parse(csvText: String): List<ChirpEntry> {
        val lines = csvText.lines()

        val headerIdx = lines.indexOfFirst {
            it.trimStart().startsWith("Location", ignoreCase = true)
        }
        if (headerIdx < 0) return emptyList()

        val headers = parseCsvLine(lines[headerIdx]).map { it.trim().lowercase(Locale.US).trim('"') }

        fun colOf(cols: List<String>, name: String): String {
            val idx = headers.indexOf(name.lowercase(Locale.US))
            return if (idx in cols.indices) cols[idx].trim().trim('"') else ""
        }

        val results = mutableListOf<ChirpEntry>()

        for (lineIdx in (headerIdx + 1) until lines.size) {
            val raw = lines[lineIdx].trim()
            if (raw.isEmpty()) continue
            val cols = parseCsvLine(raw)

            val location = colOf(cols, "location").toIntOrNull() ?: continue
            val freqMhz  = colOf(cols, "frequency").toDoubleOrNull() ?: continue
            val freqHz   = (freqMhz * 1_000_000).toLong()
            val name     = colOf(cols, "name").take(12)

            val duplexRaw = colOf(cols, "duplex").lowercase(Locale.US)
            val offsetMhz = colOf(cols, "offset").toDoubleOrNull() ?: 0.0
            val duplex: String
            val freqTxHz: Long
            val offsetHz: Long
            when (duplexRaw) {
                "+"      -> { duplex = "+";     offsetHz = (offsetMhz * 1_000_000).toLong(); freqTxHz = freqHz + offsetHz }
                "-"      -> { duplex = "-";     offsetHz = (offsetMhz * 1_000_000).toLong(); freqTxHz = freqHz - offsetHz }
                "split"  -> { duplex = "split"; offsetHz = 0L; freqTxHz = (offsetMhz * 1_000_000).toLong() }
                else     -> {
                    if (offsetMhz != 0.0) {
                        duplex   = "+"
                        offsetHz = (offsetMhz * 1_000_000).toLong()
                        freqTxHz = freqHz + offsetHz
                    } else {
                        duplex   = ""
                        offsetHz = 0L
                        freqTxHz = freqHz
                    }
                }
            }

            val toneMode  = colOf(cols, "tone").uppercase(Locale.US)
            val rToneFreq = colOf(cols, "rtonefreq").toDoubleOrNull() ?: 88.5
            val cToneFreq = colOf(cols, "ctonefreq").toDoubleOrNull() ?: 88.5
            val dtcsCode  = colOf(cols, "dtcscode").toIntOrNull() ?: 0
            val rxDtcsRaw = colOf(cols, "rxdtcscode").toIntOrNull()
            val dtcsPol   = colOf(cols, "dtcspolarity")
            val txDtcsPol = dtcsPol.getOrNull(0)?.toString() ?: "N"
            val rxDtcsPol = dtcsPol.getOrNull(1)?.toString() ?: "N"

            var txToneMode: String? = null
            var txToneVal:  Double? = null
            var txPol:      String? = null
            var rxToneMode: String? = null
            var rxToneVal:  Double? = null
            var rxPol:      String? = null

            val chirpNoToneSentinel = 88.5

            when (toneMode) {
                "TONE" -> {
                    txToneMode = "Tone"; txToneVal = rToneFreq
                }
                "TSQL" -> {
                    txToneMode = "Tone"; txToneVal = cToneFreq
                    rxToneMode = "Tone"; rxToneVal = cToneFreq
                }
                "DTCS" -> {
                    txToneMode = "DTCS"; txToneVal = dtcsCode.toDouble(); txPol = txDtcsPol
                    rxToneMode = "DTCS"; rxToneVal = (rxDtcsRaw ?: dtcsCode).toDouble(); rxPol = rxDtcsPol
                }
                "CROSS" -> {
                    val cm = colOf(cols, "crossmode").uppercase(Locale.US)
                    if (cm.contains("DTCS->DTCS")) {
                        val rxD = (rxDtcsRaw ?: dtcsCode)
                        txToneMode = "DTCS"; txToneVal = dtcsCode.toDouble(); txPol = txDtcsPol
                        rxToneMode = "DTCS"; rxToneVal = rxD.toDouble(); rxPol = rxDtcsPol
                    }
                }
                "" -> {
                    if (rToneFreq != chirpNoToneSentinel) {
                        txToneMode = "Tone"; txToneVal = rToneFreq
                    }
                }
            }

            val powerRaw = colOf(cols, "power")
            val power = parsePower(powerRaw)

            val modeRaw = colOf(cols, "mode").trim()
            val modeUpper = modeRaw.uppercase(Locale.US)
            val modeStr = if (modeUpper in CHIRP_MODES) modeUpper else "FM"

            val bandwidth = Channel.displayBandwidthForChannel(modeStr, emptyMap())

            results.add(
                ChirpEntry(
                    csvLocation = location,
                    channel = Channel(
                        number         = 0,
                        empty          = false,
                        freqRxHz       = freqHz,
                        freqTxHz       = freqTxHz,
                        duplex         = duplex,
                        offsetHz       = offsetHz,
                        power          = power,
                        name           = name,
                        mode           = modeStr,
                        bandwidth      = bandwidth,
                        driverMode     = modeStr,
                        txToneMode     = txToneMode,
                        txToneVal      = txToneVal,
                        txTonePolarity = txPol,
                        rxToneMode     = rxToneMode,
                        rxToneVal      = rxToneVal,
                        rxTonePolarity = rxPol,
                        extra          = emptyMap(),
                    )
                )
            )
        }

        return results
    }

    private fun parsePower(raw: String): String {
        val s = raw.trim()
        if (s.isBlank()) return "1"
        // CHIRP / our exporter use 0 for no-TX (N/T on nicFW).
        if (s == "0") return "N/T"
        s.toIntOrNull()?.let { if (it in 1..255) return it.toString() }
        return when (s.lowercase(Locale.US)) {
            "high", "hi"              -> "130"
            "medium", "mid", "med"    -> "65"
            "low", "lo"               -> "1"
            else                      -> "1"
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val result   = mutableListOf<String>()
        val current  = StringBuilder()
        var inQuotes = false
        for (c in line) {
            when {
                c == '"'           -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { result += current.toString(); current.clear() }
                else               -> current.append(c)
            }
        }
        result += current.toString()
        return result
    }
}
