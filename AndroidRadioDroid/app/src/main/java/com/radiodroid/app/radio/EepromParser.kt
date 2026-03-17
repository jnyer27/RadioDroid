package com.radiodroid.app.radio

import com.radiodroid.app.EepromHolder
import com.radiodroid.app.RadioSettings
import com.radiodroid.app.TuneSettings

/**
 * Compatibility shim — RadioDroid stores channels as a List<Channel> in
 * [EepromHolder], not as a raw EEPROM byte array.  All methods that accept
 * a [ByteArray] parameter ignore it and delegate to the in-memory list.
 *
 * This preserves the call-site API inherited from the nicFW project so that
 * minimally-changed callers (ChannelEditActivity, etc.)
 * continue to compile without large-scale rewrites.
 */
object EepromParser {

    /** Return every channel slot from the in-memory list (ignores [eep]). */
    fun parseAllChannels(@Suppress("UNUSED_PARAMETER") eep: ByteArray): List<Channel> =
        EepromHolder.channels.toList()

    /**
     * Return the channel for [number] (1-based) from the in-memory list.
     *
     * Returns a defensive COPY so that ChannelEditActivity can mutate its local
     * instance without also mutating the object the RecyclerView adapter already
     * holds.  When [writeChannel] stores the edited copy back and MainActivity's
     * onResume() calls adapter.submitList(), DiffUtil compares the old (unmutated)
     * reference against the new (written-back copy) by value — letting it detect
     * the change and refresh only the affected card.
     */
    fun parseChannel(@Suppress("UNUSED_PARAMETER") eep: ByteArray, number: Int): Channel? =
        EepromHolder.channels.getOrNull(number - 1)?.copy()

    /**
     * Write [ch] back into the in-memory channel list.
     * The [eep] byte array is not modified (RadioDroid has no raw EEPROM image).
     */
    fun writeChannel(@Suppress("UNUSED_PARAMETER") eep: ByteArray, ch: Channel) {
        val idx = ch.number - 1
        if (idx in EepromHolder.channels.indices) {
            EepromHolder.channels[idx] = ch
        }
    }

    /** Group labels are stored in [EepromHolder.groupLabels] — return directly. */
    fun parseGroupLabels(@Suppress("UNUSED_PARAMETER") eep: ByteArray): List<String> =
        EepromHolder.groupLabels

    // ── Stubs for NICFW-derived call sites that are not used in RadioDroid ────

    fun parseBandPlan(@Suppress("UNUSED_PARAMETER") eep: ByteArray): List<Any> = emptyList()
    fun parseScanPresets(@Suppress("UNUSED_PARAMETER") eep: ByteArray): List<Any> = emptyList()
    fun readTuneSettings(@Suppress("UNUSED_PARAMETER") eep: ByteArray): TuneSettings = TuneSettings()
    fun readRadioSettings(@Suppress("UNUSED_PARAMETER") eep: ByteArray): RadioSettings = RadioSettings()

    /**
     * Saves updated group labels into [EepromHolder.groupLabels].
     * The [eep] byte array is ignored — RadioDroid has no raw EEPROM image.
     */
    fun writeGroupLabels(@Suppress("UNUSED_PARAMETER") eep: ByteArray, labels: List<String>) {
        EepromHolder.groupLabels = labels
    }
}
