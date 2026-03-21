package com.radiodroid.app.radio

import android.content.Context
import android.content.SharedPreferences
import com.radiodroid.app.EepromHolder
import com.radiodroid.app.radio.Channel

/**
 * User choice of up to 2 channel values to show below Power on the main channel list.
 * Stored in SharedPreferences. Options include standard fields (Bandwidth, Mode, etc.)
 * and radio-specific extra params (e.g. Group 1, Tuning Step).
 */
object MainDisplayPref {

    private const val PREFS_FILE = "radiodroid_main_display"
    private const val KEY_SLOT_1 = "main_display_slot_1"
    private const val KEY_SLOT_2 = "main_display_slot_2"
    const val KEY_NONE = "none"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun getSlot1(context: Context): String =
        prefs(context).getString(KEY_SLOT_1, "bandwidth") ?: "bandwidth"

    fun getSlot2(context: Context): String =
        prefs(context).getString(KEY_SLOT_2, "mode") ?: "mode"

    fun setSlots(context: Context, slot1: String, slot2: String) {
        prefs(context).edit()
            .putString(KEY_SLOT_1, slot1)
            .putString(KEY_SLOT_2, slot2)
            .apply()
    }

    /**
     * Options for the two slots: (key, display label).
     * Includes standard channel fields plus radio-specific extra param names.
     */
    fun getDisplayOptions(context: Context): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>(
            KEY_NONE to "— None —",
            "bandwidth" to "Bandwidth (N/W)",
            "mode" to "Mode",
            "duplex" to "Duplex",
            "tx_tone" to "TX Tone",
            "rx_tone" to "RX Tone",
        )
        for (name in EepromHolder.extraParamNames) {
            if (name.isNotBlank() && list.none { it.first == name })
                list.add(name to name)
        }
        return list
    }

    /** Common extra param keys drivers use for bandwidth (radio-specific). Prefer over channel.bandwidth. */
    private val BANDWIDTH_EXTRA_KEYS = listOf("Bandwidth", "bandwidth")

    /** Normalize driver/extra bandwidth value to "N" or "W" for main display. */
    private fun bandwidthToNw(value: String): String {
        val v = value.trim().lowercase()
        return when {
            v == "narrow" || v == "nfm" || v == "nam" || v == "n" -> "N"
            else -> "W"  // Wide, FM, or any other
        }
    }

    /**
     * Returns the short display string for [channel] for the given [key].
     * Used by the main channel list to fill the two customizable slots.
     * Bandwidth comes from radio-specific extra (e.g. channel.extra["Bandwidth"]) when present,
     * so it matches what you see under Radio Specific Settings per channel; otherwise uses channel.bandwidth.
     */
    fun getChannelDisplayValue(channel: Channel, key: String): String {
        if (channel.empty || key == KEY_NONE) return ""
        return when (key) {
            "bandwidth" -> bandwidthToNw(
                channel.extra.entries.asSequence()
                    .filter { (k, v) -> v.isNotBlank() && (k in BANDWIDTH_EXTRA_KEYS || k.trim().lowercase().contains("bandwidth")) }
                    .map { it.value.trim() }
                    .firstOrNull()
                    ?: channel.bandwidth
            )
            "mode" -> channel.mode
            "duplex" -> when (channel.duplex) {
                "+" -> "+"
                "-" -> "-"
                "split" -> "Split"
                else -> ""
            }
            "tx_tone" -> channel.displayTxTone().take(12)
            "rx_tone" -> channel.displayRxTone().take(12)
            // Legacy pref keys: map to Memory.extra group fields when present
            "group1" -> (channel.extra["Group 1"] ?: channel.extra["group1"] ?: "").trim()
            "group2" -> (channel.extra["Group 2"] ?: channel.extra["group2"] ?: "").trim()
            "group3" -> (channel.extra["Group 3"] ?: channel.extra["group3"] ?: "").trim()
            "group4" -> (channel.extra["Group 4"] ?: channel.extra["group4"] ?: "").trim()
            else -> channel.extra[key] ?: ""  // radio-specific extra param
        }
    }
}
