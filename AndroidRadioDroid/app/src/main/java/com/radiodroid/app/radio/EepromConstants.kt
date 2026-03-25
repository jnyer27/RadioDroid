package com.radiodroid.app.radio

/**
 * Radio-agnostic constants used across the RadioDroid UI.
 *
 * These replace the nicFW-specific EepromConstants (which contained hard-coded
 * EEPROM offsets for the TD-H3).  Values here are generic CHIRP equivalents that
 * work across all radios supported by RadioDroid.
 */
object EepromConstants {

    // ── Group labels ──────────────────────────────────────────────────────────

    /** 15 group letters A–O. */
    val GROUP_LETTERS: List<String> = ('A'..'O').map { it.toString() }

    /** Spinner items for group selection: "None" + A–O. */
    val GROUPS_LIST: List<String> = listOf("None") + GROUP_LETTERS

    // ── Modulation / bandwidth / duplex ────────────────────────────────────────

    /** CHIRP mode names presented in the channel editor. */
    val MODULATION_LIST: List<String> = listOf("Auto", "FM", "AM", "USB", "LSB", "CW")

    /** Bandwidth options (25 kHz Wide, 12.5 kHz Narrow). */
    val BANDWIDTH_LIST: List<String> = listOf("Wide", "Narrow")

    /** Display labels for the duplex spinner. */
    val DUPLEX_LABELS: List<String> = listOf("Off (Simplex)", "+ (Plus offset)", "- (Minus offset)", "Split TX")

    /** Internal CHIRP duplex values parallel to [DUPLEX_LABELS]. */
    val DUPLEX_VALUES: List<String> = listOf("", "+", "-", "split")

    // ── Power levels ─────────────────────────────────────────────────────────

    /**
     * CHIRP-compatible power level labels shown in editor spinners.
     * "N/T" means no-transmit; remaining names are common CHIRP driver level names.
     */
    val POWERLEVEL_LIST: List<String> = listOf(
        "N/T", "Lowest", "Low", "Medium", "High", "Highest"
    )

    // ── Frequency range constants ──────────────────────────────────────────────

    /** Approximate VHF/UHF boundary used for display icon colouring. */
    const val VHF_UHF_BOUNDARY_HZ: Long = 300_000_000L  // 300 MHz

    /** Minimum valid RX frequency accepted by the channel editor (108 MHz). */
    const val VHF_LOW: Long  = 108_000_000L
    /** Top of VHF (just below 300 MHz). */
    const val VHF_HIGH: Long = 299_999_999L
    /** Start of UHF (300 MHz). */
    const val UHF_LOW: Long  = 300_000_000L
    /** Top of common UHF allocation (generous upper bound). */
    const val UHF_HIGH: Long = 999_000_000L

    // ── CTCSS tones ───────────────────────────────────────────────────────────

    /** Standard CTCSS tone frequencies in Hz. */
    val CTCSS_TONES: List<Double> = listOf(
        67.0,  69.3,  71.9,  74.4,  77.0,  79.7,  82.5,  85.4,  88.5,  91.5,
        94.8,  97.4, 100.0, 103.5, 107.2, 110.9, 114.8, 118.8, 123.0, 127.3,
       131.8, 136.5, 141.3, 146.2, 151.4, 156.7, 159.8, 162.2, 165.5, 167.9,
       171.3, 173.8, 177.3, 179.9, 183.5, 186.2, 189.9, 192.8, 196.6, 199.5,
       203.5, 206.5, 210.7, 218.1, 225.7, 229.1, 233.6, 241.8, 250.3, 254.1
    )

    // ── DCS codes ────────────────────────────────────────────────────────────

    /** Standard CHIRP DCS codes (displayed in octal/decimal notation). */
    val DCS_CODES: List<Int> = listOf(
         23,  25,  26,  31,  32,  36,  43,  47,  51,  53,  54,  65,  71,  72,  73,  74,
        114, 115, 116, 122, 125, 131, 132, 134, 143, 145, 152, 155, 156, 162, 165, 172, 174,
        205, 212, 223, 225, 226, 243, 244, 245, 246, 251, 252, 255, 261, 263, 265, 266, 271, 274,
        306, 311, 315, 325, 331, 332, 343, 346, 351, 356, 364, 365, 371,
        411, 412, 413, 423, 431, 432, 445, 446, 452, 454, 455, 462, 464, 465, 466,
        503, 506, 516, 523, 526, 532, 546, 565,
        606, 612, 624, 627, 631, 632, 654, 662, 664,
        703, 712, 723, 731, 732, 734, 743, 754
    )

    // ── Flat tone spinner labels ───────────────────────────────────────────────

    /**
     * All available tone values as a flat list for a single-spinner UI:
     *   index 0          = "None"
     *   index 1..50      = CTCSS tones  "67.0 Hz" … "254.1 Hz"
     *   index 51..153    = DCS Normal   "DCS 023 N" … "DCS 754 N"
     *   index 154..256   = DCS Reverse  "DCS 023 R" … "DCS 754 R"
     */
    val TONE_LABELS: List<String> = buildList {
        add("None")
        CTCSS_TONES.forEach { add("%.1f Hz".format(it)) }
        DCS_CODES.forEach   { add("DCS %03d N".format(it)) }
        DCS_CODES.forEach   { add("DCS %03d R".format(it)) }
    }

    // ── Tone index converters ─────────────────────────────────────────────────

    /** Convert channel tone fields to a [TONE_LABELS] spinner index. */
    fun toneToIndex(mode: String?, value: Double?, polarity: String?): Int {
        if (mode == null || value == null) return 0
        val label = when (mode) {
            "Tone", "TSQL" -> "%.1f Hz".format(value)
            "DTCS" -> {
                val pol = if (polarity == "R" || polarity == "I") "R" else "N"
                "DCS %03d %s".format(value.toInt(), pol)
            }
            else -> return 0
        }
        return TONE_LABELS.indexOf(label).coerceAtLeast(0)
    }

    /** Convert a [TONE_LABELS] spinner index back to (mode, value, polarity). */
    fun indexToTone(index: Int): Triple<String?, Double?, String?> {
        val label = TONE_LABELS.getOrNull(index) ?: return Triple(null, null, null)
        if (label == "None") return Triple(null, null, null)
        if (label.endsWith("Hz")) {
            val freq = label.dropLast(3).trim().toDoubleOrNull()
                ?: return Triple(null, null, null)
            return Triple("Tone", freq, null)
        }
        if (label.startsWith("DCS ")) {
            val parts = label.split(" ")
            val code  = parts.getOrNull(1)?.toIntOrNull() ?: return Triple(null, null, null)
            val pol   = parts.getOrNull(2) ?: "N"
            return Triple("DTCS", code.toDouble(), pol)
        }
        return Triple(null, null, null)
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    /** Pass-through display formatter for CHIRP power strings. */
    fun powerToWatts(power: String): String = power.ifBlank { "?" }

    /**
     * Returns true if [freqHz] falls inside a well-known receive-only band.
     *  - 108–137.999 MHz  Aviation voice (AM) — TX never permitted
     */
    fun isTxRestricted(freqHz: Long): Boolean =
        freqHz in 108_000_000L..137_999_999L
}
