package com.radiodroid.app.model

import org.json.JSONObject

/**
 * Mirrors CHIRP's RadioFeatures class — the driver's self-description of what
 * the radio hardware actually supports.
 *
 * Parsed from the JSON string returned by chirp_bridge.get_radio_features().
 * Used throughout the UI to show only spinners/fields that are meaningful for
 * the selected radio, populated with only the values the driver accepts.
 *
 * All fields have safe defaults so the class is usable even before a radio is
 * selected (via [RadioFeatures.DEFAULT]).
 */
data class RadioFeatures(

    // ── Spinner value lists ───────────────────────────────────────────────────

    /** Modulation modes the radio supports, e.g. ["FM", "NFM", "AM"]. */
    val validModes: List<String>,

    /** Duplex offsets the radio supports, e.g. ["", "+", "-", "split"]. */
    val validDuplexes: List<String>,

    /** Tone modes the radio supports, e.g. ["", "Tone", "TSQL", "DTCS"]. */
    val validTmodes: List<String>,

    /** Scan-skip values the radio supports, e.g. ["", "S"] or ["", "S", "P"]. */
    val validSkips: List<String>,

    /**
     * Driver-defined power level names, e.g. ["High", "Low", "Lowest"].
     * Empty list means the driver has no discrete power level concept.
     */
    val validPowerLevels: List<String>,

    /** DCS codes the radio accepts. Empty = use the full standard set. */
    val validDtcsCodes: List<Int>,

    /** DCS polarity combinations, e.g. ["NN", "NR", "RN", "RR"]. */
    val validDtcsPols: List<String>,

    // ── Name constraints ──────────────────────────────────────────────────────

    /** Maximum channel name length. 0 = driver default (use 16 as a safe cap). */
    val validNameLength: Int,

    /** Set of characters valid in channel names. Empty = any ASCII. */
    val validNameChars: String,

    // ── Capability flags ──────────────────────────────────────────────────────

    /** False if the radio has no channel name field at all. */
    val hasName: Boolean,

    /** False if TX and RX CTCSS tones are always the same (no separate ctone). */
    val hasCtone: Boolean,

    /** True if the radio supports a separate RX DCS code. */
    val hasRxDtcs: Boolean,

    /** True if get_settings() / set_settings() are available for this radio. */
    val hasSettings: Boolean,

    /** True if the radio supports programmable tuning steps. */
    val hasTuningStep: Boolean,

    /** True if a split TX frequency independent of an offset is supported. */
    val canOddSplit: Boolean,

    // ── Memory layout ─────────────────────────────────────────────────────────

    /** First channel slot number (inclusive). */
    val memoryBoundsLo: Int,

    /** Last channel slot number (inclusive). */
    val memoryBoundsHi: Int,

) {
    // ── Derived capability helpers ────────────────────────────────────────────

    /** True if any tone mode other than "" is supported by this radio. */
    val hasTone: Boolean get() = validTmodes.any { it.isNotEmpty() }

    /** True if CTCSS TX tone (Tone mode) is supported. */
    val hasTxCtcss: Boolean get() = "Tone" in validTmodes || "TSQL" in validTmodes

    /** True if a separate RX CTCSS tone (TSQL mode) is supported. */
    val hasRxCtcss: Boolean get() = "TSQL" in validTmodes && hasCtone

    /** True if DCS tone mode is supported. */
    val hasDtcs: Boolean get() = "DTCS" in validTmodes

    /** Total number of channel slots this radio has. */
    val channelCount: Int get() = memoryBoundsHi - memoryBoundsLo + 1

    /**
     * Human-readable label for a CHIRP duplex internal value.
     * Used to populate the duplex spinner without hardcoding labels.
     */
    fun duplexLabel(value: String): String = when (value) {
        ""      -> "Simplex"
        "+"     -> "+ (Plus offset)"
        "-"     -> "- (Minus offset)"
        "split" -> "Split TX freq"
        "off"   -> "Off (TX disabled)"
        else    -> value
    }

    companion object {

        /**
         * Parse from the JSON string returned by chirp_bridge.get_radio_features().
         * All fields have safe fallbacks so a partial JSON response never crashes.
         */
        fun fromJson(json: String): RadioFeatures {
            val j = JSONObject(json)

            fun strList(key: String): List<String> =
                j.optJSONArray(key)
                    ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                    ?: emptyList()

            fun intList(key: String): List<Int> =
                j.optJSONArray(key)
                    ?.let { arr -> (0 until arr.length()).map { arr.getInt(it) } }
                    ?: emptyList()

            return RadioFeatures(
                validModes       = strList("valid_modes").ifEmpty { listOf("FM") },
                validDuplexes    = strList("valid_duplexes").ifEmpty { listOf("", "+", "-") },
                validTmodes      = strList("valid_tmodes"),
                validSkips       = strList("valid_skips").ifEmpty { listOf("", "S") },
                validPowerLevels = strList("valid_power_levels"),
                validDtcsCodes   = intList("valid_dtcs_codes"),
                validDtcsPols    = strList("valid_dtcs_pols").ifEmpty {
                    listOf("NN", "NR", "RN", "RR")
                },
                validNameLength  = j.optInt("valid_name_length", 0),
                validNameChars   = j.optString("valid_name_chars", ""),
                hasName          = j.optBoolean("has_name", true),
                hasCtone         = j.optBoolean("has_ctone", true),
                hasRxDtcs        = j.optBoolean("has_rx_dtcs", false),
                hasSettings      = j.optBoolean("has_settings", false),
                hasTuningStep    = j.optBoolean("has_tuning_step", true),
                canOddSplit      = j.optBoolean("can_odd_split", false),
                memoryBoundsLo   = j.optInt("memory_bounds_lo", 0),
                memoryBoundsHi   = j.optInt("memory_bounds_hi", 199),
            )
        }

        /**
         * Safe fallback used before any radio is selected or when feature
         * introspection fails.  Shows all fields; uses generic list values.
         */
        val DEFAULT = RadioFeatures(
            validModes       = listOf("FM", "NFM", "AM"),
            validDuplexes    = listOf("", "+", "-"),
            validTmodes      = listOf("", "Tone", "TSQL", "DTCS"),
            validSkips       = listOf("", "S"),
            validPowerLevels = emptyList(),
            validDtcsCodes   = emptyList(),
            validDtcsPols    = listOf("NN", "NR", "RN", "RR"),
            validNameLength  = 0,
            validNameChars   = "",
            hasName          = true,
            hasCtone         = true,
            hasRxDtcs        = false,
            hasSettings      = false,
            hasTuningStep    = true,
            canOddSplit      = false,
            memoryBoundsLo   = 0,
            memoryBoundsHi   = 199,
        )
    }
}
