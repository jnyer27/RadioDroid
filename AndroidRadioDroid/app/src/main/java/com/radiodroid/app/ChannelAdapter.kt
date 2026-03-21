package com.radiodroid.app

import android.text.TextUtils
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

        private val channelNumber:     TextView     = card.findViewById(R.id.channelNumber)
        private val channelFreq:       TextView     = card.findViewById(R.id.channelFreq)
        private val channelName:       TextView     = card.findViewById(R.id.channelName)
        private val channelToneGroup:   LinearLayout = card.findViewById(R.id.channelToneGroup)
        private val channelTxTone:     TextView     = card.findViewById(R.id.channelTxTone)
        private val channelRxTone:     TextView     = card.findViewById(R.id.channelRxTone)
        private val channelDuplex:     TextView     = card.findViewById(R.id.channelDuplex)
        private val channelPower:      TextView     = card.findViewById(R.id.channelPower)
        private val channelSlot1:      TextView     = card.findViewById(R.id.channelSlot1)
        private val channelSlot2:      TextView     = card.findViewById(R.id.channelSlot2)
        private val channelDragHandle: ImageView    = card.findViewById(R.id.channelDragHandle)
        private val channelDriverRow:  LinearLayout = card.findViewById(R.id.channelDriverRow)
        private val channelRadioSpecColumns: LinearLayout = card.findViewById(R.id.channelRadioSpecColumns)

        private var pendingRadioSpecItems: List<String>? = null
        private var lastSpecCols: Int = -1
        private var lastSpecItems: List<String>? = null

        init {
            channelRadioSpecColumns.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                val w = right - left
                val ow = oldRight - oldLeft
                if (w > 0 && w != ow) {
                    val pending = pendingRadioSpecItems
                    if (pending != null && pending.isNotEmpty()) {
                        lastSpecCols = -1
                        applyRadioSpecColumns(pending)
                    }
                }
            }
        }

        @Suppress("ClickableViewAccessibility")
        fun bind(channel: Channel) {
            channelNumber.text = card.context.getString(R.string.channel_number, channel.number)

            if (channel.empty) {
                channelFreq.text   = card.context.getString(R.string.empty_channel)
                channelName.text   = ""
                channelDuplex.text = ""
                channelPower.text  = ""
                channelSlot1.text = ""
                channelSlot1.visibility = View.GONE
                channelSlot2.text = ""
                channelSlot2.visibility = View.GONE
                channelTxTone.text = ""; channelRxTone.text = ""
                channelToneGroup.visibility = View.GONE
                channelDriverRow.visibility = View.GONE
                channelRadioSpecColumns.removeAllViews()
                pendingRadioSpecItems = null
                lastSpecCols = -1
                lastSpecItems = null
            } else {
                channelFreq.text   = channel.displayFreq()
                channelName.text   = channel.name.ifEmpty { "-" }
                channelDuplex.text = channel.displayDuplex()
                val wattsText = EepromConstants.powerToWatts(channel.power)
                val txRestricted = EepromConstants.isTxRestricted(channel.freqRxHz)
                channelPower.text = if (wattsText != "N/T" && txRestricted)
                    "$wattsText (RX)" else wattsText

                val slot1Key = MainDisplayPref.getSlot1(card.context)
                val slot2Key = MainDisplayPref.getSlot2(card.context)
                val v1 = MainDisplayPref.getChannelDisplayValue(channel, slot1Key)
                val v2 = MainDisplayPref.getChannelDisplayValue(channel, slot2Key)
                channelSlot1.text = v1
                channelSlot1.visibility = if (v1.isEmpty()) View.GONE else View.VISIBLE
                channelSlot2.text = v2
                channelSlot2.visibility = if (v2.isEmpty()) View.GONE else View.VISIBLE

                val specItems = buildRadioSpecItems(channel)
                pendingRadioSpecItems = specItems
                channelDriverRow.visibility =
                    if (specItems.isEmpty()) View.GONE else View.VISIBLE
                if (specItems.isNotEmpty()) {
                    channelRadioSpecColumns.post { applyRadioSpecColumns(specItems) }
                } else {
                    channelRadioSpecColumns.removeAllViews()
                    lastSpecCols = -1
                    lastSpecItems = null
                }

                val tx = channel.displayTxTone()
                val rx = channel.displayRxTone()
                channelTxTone.text    = if (tx.isNotEmpty()) "T: $tx" else ""
                channelRxTone.text    = if (rx.isNotEmpty()) "R: $rx" else ""
                channelToneGroup.visibility = if (tx.isEmpty() && rx.isEmpty()) View.GONE else View.VISIBLE
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

        /**
         * Lines for the radio-specific grid: [Channel.extra] only, ordered by
         * [EepromHolder.channelExtraSchema] then remaining keys alphabetically.
         */
        private fun buildRadioSpecItems(channel: Channel): List<String> {
            if (channel.extra.isEmpty()) return emptyList()
            val ordered = linkedSetOf<String>()
            for (s in EepromHolder.channelExtraSchema) {
                if (s.name in channel.extra) ordered.add(s.name)
            }
            for (k in channel.extra.keys.sorted()) {
                ordered.add(k)
            }
            return ordered.map { key -> "$key: ${channel.extra[key]}" }
        }

        /**
         * Distributes [items] across weighted vertical columns **column-major**:
         * fill column 0 top→bottom, then column 1 top→bottom, etc.
         */
        private fun applyRadioSpecColumns(items: List<String>) {
            if (items.isEmpty()) return
            val container = channelRadioSpecColumns
            val w = container.width
            if (w <= 0) {
                container.post { applyRadioSpecColumns(items) }
                return
            }
            val res = card.context.resources
            val minCellPx = res.getDimensionPixelSize(R.dimen.channel_radio_spec_min_cell_width)
            val maxCols = res.getInteger(R.integer.channel_radio_spec_max_columns)
            val cols = maxOf(1, minOf(maxCols, w / minCellPx))
            if (cols == lastSpecCols && items == lastSpecItems) return
            lastSpecCols = cols
            lastSpecItems = items.toList()

            val rowCount = (items.size + cols - 1) / cols

            container.removeAllViews()
            val density = res.displayMetrics.density
            val gapEnd = (6 * density).toInt()
            for (c in 0 until cols) {
                val col = LinearLayout(card.context).apply {
                    orientation = LinearLayout.VERTICAL
                    val padEnd = if (c < cols - 1) gapEnd else 0
                    setPadding(0, 0, padEnd, 0)
                }
                for (r in 0 until rowCount) {
                    val idx = c * rowCount + r
                    if (idx < items.size) {
                        col.addView(newSpecCell(items[idx]))
                    }
                }
                container.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
        }

        private fun newSpecCell(text: CharSequence): TextView {
            val ctx = card.context
            return TextView(ctx).apply {
                this.text = text
                textSize = 11f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                val a = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary))
                setTextColor(a.getColor(0, 0xFF888888.toInt()))
                a.recycle()
                val padB = (4 * ctx.resources.displayMetrics.density).toInt()
                setPadding(0, 0, 0, padB)
            }
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(a: Channel, b: Channel) = a.number == b.number
        override fun areContentsTheSame(a: Channel, b: Channel) = a == b
    }
}
