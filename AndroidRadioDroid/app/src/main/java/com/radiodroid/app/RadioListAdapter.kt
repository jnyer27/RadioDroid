package com.radiodroid.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.radiodroid.app.model.RadioInfo

/**
 * RecyclerView adapter for the radio-selection screen.
 *
 * Displays a flat list that may contain two item types:
 *  - [TYPE_HEADER] — a vendor group heading (e.g. "BAOFENG")
 *  - [TYPE_RADIO]  — a clickable model row (e.g. "UV-5R")
 *
 * Call [submitGroupedList] with a sorted [List<RadioInfo>] to auto-insert
 * vendor headers whenever the vendor name changes.
 *
 * During a search the list is pre-filtered (no headers); the vendor name is
 * shown as a subtitle on each row instead so context is never lost.
 */
class RadioListAdapter(
    private val onRadioClick: (RadioInfo) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    // ── Item model ────────────────────────────────────────────────────────────

    private sealed class Item {
        data class Header(val vendor: String) : Item()
        data class Radio(val info: RadioInfo, val showVendor: Boolean) : Item()
    }

    private var items: List<Item> = emptyList()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Rebuilds the list from [radios] (assumed sorted by vendor then model).
     * When [isFiltered] is true the list came from a search query — vendor
     * headers are omitted and the vendor name is shown as a subtitle instead.
     */
    fun submitGroupedList(radios: List<RadioInfo>, isFiltered: Boolean = false) {
        val built = mutableListOf<Item>()
        if (isFiltered) {
            radios.forEach { built += Item.Radio(it, showVendor = true) }
        } else {
            var lastVendor = ""
            for (r in radios) {
                if (r.vendor != lastVendor) {
                    built += Item.Header(r.vendor)
                    lastVendor = r.vendor
                }
                built += Item.Radio(r, showVendor = false)
            }
        }
        val oldSize = items.size
        items = built
        if (oldSize == built.size) {
            notifyItemRangeChanged(0, oldSize)
        } else {
            notifyItemRangeRemoved(0, oldSize)
            notifyItemRangeInserted(0, built.size)
        }
    }

    // ── ViewHolder types ──────────────────────────────────────────────────────

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_RADIO  = 1
    }

    override fun getItemViewType(position: Int) = when (items[position]) {
        is Item.Header -> TYPE_HEADER
        is Item.Radio  -> TYPE_RADIO
    }

    override fun getItemCount() = items.size

    // ── Inflation ─────────────────────────────────────────────────────────────

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderVH(inf.inflate(R.layout.item_radio_vendor_header, parent, false))
            else        -> RadioVH (inf.inflate(R.layout.item_radio_entry,         parent, false))
        }
    }

    // ── Binding ───────────────────────────────────────────────────────────────

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is Item.Header -> (holder as HeaderVH).bind(item)
            is Item.Radio  -> (holder as RadioVH ).bind(item)
        }
    }

    // ── ViewHolder implementations ────────────────────────────────────────────

    private inner class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tv = view.findViewById<TextView>(R.id.tvVendorHeader)
        fun bind(item: Item.Header) { tv.text = item.vendor }
    }

    private inner class RadioVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvModel    = view.findViewById<TextView>(R.id.tvModel)
        private val tvVendor   = view.findViewById<TextView>(R.id.tvVendorSubtitle)

        fun bind(item: Item.Radio) {
            tvModel.text = item.info.model
            if (item.showVendor) {
                tvVendor.text = item.info.vendor
                tvVendor.visibility = View.VISIBLE
            } else {
                tvVendor.visibility = View.GONE
            }
            itemView.setOnClickListener { onRadioClick(item.info) }
        }
    }
}
