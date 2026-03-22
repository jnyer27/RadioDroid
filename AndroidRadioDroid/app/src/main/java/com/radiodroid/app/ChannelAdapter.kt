package com.radiodroid.app

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.radiodroid.app.radio.Channel
import com.radiodroid.app.radio.EepromConstants
import com.radiodroid.app.radio.MainDisplayPref

/**
 * RecyclerView adapter for the 198-channel list on [MainActivity].
 *
 * Normal mode — tap a card to open the channel editor.
 * Selection mode — long-press a card to enter selection mode; subsequent taps
 *   toggle selection; the in-app selection bar in MainActivity handles
 *   Move Up, Move Down, Clear, and Done operations.
 *   A drag handle appears on each selected card — touching it starts an
 *   [ItemTouchHelper] drag so the user can physically reposition that channel.
 *
 * Selection state is intentionally kept inside the adapter (not in MainActivity)
 * so it survives [submitList] updates triggered by move/clear operations.
 */
class ChannelAdapter(
    private val onChannelClick:      (Channel) -> Unit,
    private val onLongClick:         (Channel) -> Unit,
    private val onSelectionChanged:  (count: Int) -> Unit,
    private val onDragStart:         (RecyclerView.ViewHolder) -> Unit
) : ListAdapter<Channel, ChannelAdapter.ViewHolder>(DiffCallback) {

    // ── Selection state ───────────────────────────────────────────────────────

    /** True while one or more channels are selected via long-press. */
    var isSelectionMode: Boolean = false
        private set

    /** Channel slot numbers currently selected (1-based). */
    private val selectedNumbers = mutableSetOf<Int>()

    /** Snapshot of [selectedNumbers] for callers (MainActivity move/clear). */
    val selectedChannelNumbers: Set<Int> get() = selectedNumbers.toSet()

    /** Number of currently selected channels. */
    val selectedCount: Int get() = selectedNumbers.size

    // ── Selection API (called from MainActivity) ───────────────────────────────

    /**
     * Enters selection mode with [number] as the first selected channel.
     * Notifies all items so cards can show/hide the check state and drag handle.
     */
    fun enterSelectionMode(number: Int) {
        isSelectionMode = true
        selectedNumbers.clear()
        selectedNumbers.add(number)
        notifyDataSetChanged()
        onSelectionChanged(selectedNumbers.size)
    }

    /**
     * Exits selection mode and clears all selections.
     * Called when the Done button or back navigation dismisses the selection bar.
     */
    fun exitSelectionMode() {
        isSelectionMode = false
        selectedNumbers.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    /**
     * Toggles selection for [number]. Notifies the affected item.
     * Returns the new selection count.
     */
    fun toggleSelection(number: Int): Int {
        if (number in selectedNumbers) selectedNumbers.remove(number)
        else selectedNumbers.add(number)

        val pos = currentList.indexOfFirst { it.number == number }
        if (pos >= 0) notifyItemChanged(pos)

        onSelectionChanged(selectedNumbers.size)
        return selectedNumbers.size
    }

    /**
     * Selects all non-empty channels in [currentList] and enters selection mode.
     * Used by the search bar's "Select All" button to bulk-select all visible matches.
     */
    fun selectAllVisible() {
        val targets = currentList.filter { !it.empty }
        if (targets.isEmpty()) return
        isSelectionMode = true
        selectedNumbers.clear()
        selectedNumbers.addAll(targets.map { it.number })
        notifyDataSetChanged()
        onSelectionChanged(selectedNumbers.size)
    }

    /**
     * Replaces the selection set (called after move operations so the same
     * logical channels remain highlighted at their new slot positions).
     */
    fun updateSelection(numbers: Set<Int>) {
        selectedNumbers.clear()
        selectedNumbers.addAll(numbers)
        notifyDataSetChanged()
        onSelectionChanged(selectedNumbers.size)
    }

    // ── Adapter overrides ──────────────────────────────────────────────────────

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        val card = v as MaterialCardView
        // Enable the Material checked-state overlay (shows a checkmark when isChecked = true)
        card.isCheckable = true
        return ViewHolder(card)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    // ── ViewHolder ────────────────────────────────────────────────────────────

    inner class ViewHolder(private val card: MaterialCardView) : RecyclerView.ViewHolder(card) {

        private val channelNumber: TextView = card.findViewById(R.id.channelNumber)
        private val channelFreq: TextView = card.findViewById(R.id.channelFreq)
        private val channelName: TextView = card.findViewById(R.id.channelName)
        private val channelBadgeRow: LinearLayout = card.findViewById(R.id.channelBadgeRow)
        private val channelToneSummary: TextView = card.findViewById(R.id.channelToneSummary)
        private val channelDragHandle: ImageView = card.findViewById(R.id.channelDragHandle)
        private val channelRadioSpecCompact: TextView = card.findViewById(R.id.channelRadioSpecCompact)

        @Suppress("ClickableViewAccessibility")
        fun bind(channel: Channel) {
            channelNumber.text = card.context.getString(R.string.channel_number, channel.number)

            if (channel.empty) {
                channelFreq.text = card.context.getString(R.string.empty_channel)
                channelName.text = ""
                channelBadgeRow.removeAllViews()
                channelToneSummary.text = ""
                channelToneSummary.visibility = View.GONE
                channelRadioSpecCompact.text = ""
                channelRadioSpecCompact.visibility = View.GONE
            } else {
                channelFreq.text = channel.displayFreq()
                channelName.text = channel.name.ifEmpty { "-" }

                channelBadgeRow.removeAllViews()
                val wattsText = EepromConstants.powerToWatts(channel.power)
                val txRestricted = EepromConstants.isTxRestricted(channel.freqRxHz)
                val powerLabel = if (wattsText != "N/T" && txRestricted)
                    "$wattsText (RX)" else wattsText
                if (powerLabel.isNotBlank()) addBadge(channelBadgeRow, powerLabel)
                if (channel.mode.isNotBlank()) addBadge(channelBadgeRow, channel.mode)
                val duplexShown = channel.displayDuplex()
                if (duplexShown.isNotBlank()) addBadge(channelBadgeRow, duplexShown)

                val slot1Key = MainDisplayPref.getSlot1(card.context)
                val slot2Key = MainDisplayPref.getSlot2(card.context)
                maybeAddSlotBadge(channelBadgeRow, channel, slot1Key,
                    MainDisplayPref.getChannelDisplayValue(channel, slot1Key), duplexShown)
                maybeAddSlotBadge(channelBadgeRow, channel, slot2Key,
                    MainDisplayPref.getChannelDisplayValue(channel, slot2Key), duplexShown)

                val tx = channel.displayTxTone()
                val rx = channel.displayRxTone()
                val toneLine = when {
                    tx.isNotEmpty() && rx.isNotEmpty() -> "T: $tx · R: $rx"
                    tx.isNotEmpty() -> "T: $tx"
                    rx.isNotEmpty() -> "R: $rx"
                    else -> ""
                }
                if (toneLine.isEmpty()) {
                    channelToneSummary.visibility = View.GONE
                } else {
                    channelToneSummary.text = toneLine
                    channelToneSummary.visibility = View.VISIBLE
                }

                val specCompact = buildCompactRadioSpecSummary(channel)
                if (specCompact.isNullOrBlank()) {
                    channelRadioSpecCompact.visibility = View.GONE
                } else {
                    channelRadioSpecCompact.text = specCompact
                    channelRadioSpecCompact.visibility = View.VISIBLE
                }
            }

            // Selection check state (drives the MaterialCardView checked-icon overlay)
            val isSelected = isSelectionMode && channel.number in selectedNumbers
            card.isChecked = isSelected

            // Drag handle: visible only on selected cards while in selection mode
            channelDragHandle.visibility = if (isSelected) View.VISIBLE else View.GONE
            if (isSelected) {
                channelDragHandle.setOnTouchListener { _, e ->
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                        onDragStart(this@ViewHolder)
                    }
                    false
                }
            } else {
                channelDragHandle.setOnTouchListener(null)
            }

            // Touch behaviour differs by mode
            card.setOnClickListener {
                if (isSelectionMode) {
                    toggleSelection(channel.number)
                } else {
                    onChannelClick(channel)
                }
            }
            card.setOnLongClickListener {
                if (!isSelectionMode) {
                    onLongClick(channel)
                }
                true
            }
        }

        private fun addBadge(row: LinearLayout, text: String) {
            if (text.isBlank()) return
            val ctx = row.context
            val dm = ctx.resources.displayMetrics.density
            val tv = TextView(ctx).apply {
                this.text = text
                textSize = 10f
                setBackgroundResource(R.drawable.bg_channel_badge)
                val h = (6 * dm).toInt()
                val v = (3 * dm).toInt()
                setPadding(h, v, h, v)
                val a = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
                setTextColor(a.getColor(0, 0))
                a.recycle()
            }
            val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            lp.marginEnd = (4 * dm).toInt()
            row.addView(tv, lp)
        }

        /**
         * Adds a user-configured main-display slot as a badge when it does not duplicate
         * badges already shown (power/mode/duplex/tones).
         */
        private fun maybeAddSlotBadge(
            row: LinearLayout,
            channel: Channel,
            slotKey: String,
            value: String,
            duplexChipText: String,
        ) {
            if (slotKey == MainDisplayPref.KEY_NONE || value.isBlank()) return
            when (slotKey) {
                "mode" -> return
                "duplex" -> if (duplexChipText.isNotBlank()) return
                "tx_tone", "rx_tone" -> return
            }
            val label = when (slotKey) {
                "bandwidth" -> "BW $value"
                else -> value
            }
            addBadge(row, label)
        }

        /**
         * Ordered keys for [channel.extra]: [EepromHolder.channelExtraSchema] first, then any
         * remaining keys alphabetically (same ordering as the channel editor / legacy list).
         */
        private fun orderedExtraKeys(channel: Channel): List<String> {
            if (channel.extra.isEmpty()) return emptyList()
            val ordered = linkedSetOf<String>()
            for (s in EepromHolder.channelExtraSchema) {
                if (s.name in channel.extra) ordered.add(s.name)
            }
            for (k in channel.extra.keys.sorted()) {
                ordered.add(k)
            }
            return ordered.toList()
        }

        private fun groupSlotIndex(key: String): Int? {
            val t = key.trim()
            Regex("(?i)^group\\s*([1-4])$").matchEntire(t)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
            Regex("(?i)^group([1-4])$").matchEntire(t)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
            return null
        }

        private fun matchesBusyLockKey(name: String): Boolean {
            val n = name.trim().lowercase().replace(" ", "_")
            return n == "busylock" || n == "busy_lock" ||
                n == "bcl" || n == "bclo" || n == "bclb" ||
                n == "busychannellockout" || n == "busy_lockout"
        }

        private fun prettyExtraLabel(key: String): String = when {
            matchesBusyLockKey(key) -> "BusyLock"
            key.trim().equals("Bandwidth", ignoreCase = true) -> "BW"
            key.trim().lowercase().contains("bandwidth") -> "BW"
            else -> key.trim()
        }

        /** @return null to omit this key from the summary line */
        private fun formatExtraSummaryValue(key: String, raw: String): String? {
            val v = raw.trim()
            if (v.isEmpty()) return null
            if (v.equals("none", ignoreCase = true)) return null
            return when {
                v.equals("true", ignoreCase = true) -> "On"
                v.equals("false", ignoreCase = true) -> "Off"
                else -> v
            }
        }

        /**
         * Single dense line for driver extras: merged groups, hidden None/empty,
         * bullet-separated (matches high-density main list design).
         */
        private fun buildCompactRadioSpecSummary(channel: Channel): String? {
            if (channel.extra.isEmpty()) return null
            val ordered = orderedExtraKeys(channel)
            val groupSlots = arrayOfNulls<String>(4)
            for (key in ordered) {
                val idx = groupSlotIndex(key) ?: continue
                val raw = channel.extra[key]?.trim() ?: continue
                if (raw.isEmpty() || raw.equals("none", ignoreCase = true)) continue
                groupSlots[idx - 1] = raw
            }
            val groupParts = (0..3).mapNotNull { groupSlots[it] }
            val segments = mutableListOf<String>()
            if (groupParts.isNotEmpty()) {
                segments.add("Groups: ${groupParts.joinToString(", ")}")
            }
            for (key in ordered) {
                if (groupSlotIndex(key) != null) continue
                val raw = channel.extra[key] ?: continue
                val formatted = formatExtraSummaryValue(key, raw) ?: continue
                segments.add("${prettyExtraLabel(key)}: $formatted")
            }
            if (segments.isEmpty()) return null
            return segments.joinToString("  ·  ")
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(a: Channel, b: Channel) = a.number == b.number
        override fun areContentsTheSame(a: Channel, b: Channel) = a == b
    }
}
