package com.radiodroid.app.radio.repeaterbook

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.radiodroid.app.databinding.ItemRepeaterbookResultBinding
import org.json.JSONObject

/** One selectable repeater row (JSON record from RepeaterBook / CHIRP mirror). */
data class RepeaterBookJsonRow(val json: JSONObject, var selected: Boolean) {
    fun matchesQuickFilter(q: String): Boolean {
        val call = json.optString("Callsign", "").lowercase()
        val city = json.optString("Nearest City", "").lowercase()
        val freq = json.optString("Frequency", "") + json.opt("Frequency")?.toString().orEmpty()
        val line = "$call $city $freq".lowercase()
        return line.contains(q)
    }
}

class RepeaterBookResultsAdapter(
    private val rows: List<RepeaterBookJsonRow>,
    private val onToggle: (RepeaterBookJsonRow, Boolean) -> Unit,
) : RecyclerView.Adapter<RepeaterBookResultsAdapter.VH>() {

    class VH(val binding: ItemRepeaterbookResultBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemRepeaterbookResultBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return VH(binding)
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = rows[position]
        val j = row.json
        val call = j.optString("Callsign", "").ifBlank { "—" }
        val freq = j.opt("Frequency")?.toString()?.trim().orEmpty().ifBlank { j.optString("Frequency") }
        val title = "$call  $freq MHz".trim()
        val pl = j.optString("PL", "")
        val tsq = j.optString("TSQ", "")
        val tone = when {
            pl.isNotBlank() && tsq.isNotBlank() -> "PL $pl  TSQ $tsq"
            pl.isNotBlank() -> "PL $pl"
            tsq.isNotBlank() -> "TSQ $tsq"
            else -> ""
        }
        val sub = buildString {
            append(RepeaterBookToChannelMapper.commentLine(j))
            if (tone.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append(tone)
            }
        }
        holder.binding.textTitle.text = title
        holder.binding.textSubtitle.text = sub.ifBlank { "—" }
        holder.binding.checkSelected.setOnCheckedChangeListener(null)
        holder.binding.checkSelected.isChecked = row.selected
        holder.binding.checkSelected.setOnCheckedChangeListener { _, checked ->
            onToggle(row, checked)
        }
        holder.itemView.setOnClickListener {
            holder.binding.checkSelected.isChecked = !holder.binding.checkSelected.isChecked
        }
    }
}

