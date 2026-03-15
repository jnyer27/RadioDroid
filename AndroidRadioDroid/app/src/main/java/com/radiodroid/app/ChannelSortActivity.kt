package com.radiodroid.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.radiodroid.app.databinding.ActivityChannelSortBinding
import com.radiodroid.app.radio.Channel
import com.radiodroid.app.radio.EepromConstants
import com.radiodroid.app.radio.EepromParser
import java.util.Collections

/**
 * Lets the user drag-to-reorder the 15 channel groups and then apply a sort that
 * makes every group's channels contiguous in the channel list.
 *
 * Sort rules:
 *  1. Channels whose primary group (first non-None of group1..group4) matches a
 *     group in the user-ordered list — sorted by that group priority, stable within
 *     each group (relative order is preserved).
 *  2. Channels with no group assignment — appended after all group blocks.
 *  3. Empty slots — always moved to the end.
 *
 * Writes the reorganised data into [EepromHolder.eeprom]; the caller (MainActivity)
 * refreshes the channel list on resume.
 */
class ChannelSortActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChannelSortBinding
    private lateinit var touchHelper: ItemTouchHelper
    private lateinit var sortAdapter: SortGroupAdapter

    /** All 15 groups (A–O) in user-defined drag order; count may be 0. */
    private val sortableGroups = mutableListOf<SortGroup>()

    private var ungroupedCount = 0
    private var emptyCount = 0

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelSortBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val eep = EepromHolder.eeprom
        if (eep == null) {
            Toast.makeText(this, "No EEPROM loaded — load from radio first", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        buildGroupData(eep)
        setupRecyclerView()
        updateTexts()

        binding.btnSortCancel.setOnClickListener { finish() }
        binding.btnSortConfirm.setOnClickListener { confirmAndSort(eep) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Data preparation
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildGroupData(eep: ByteArray) {
        val channels = EepromParser.parseAllChannels(eep)
        val labels   = EepromHolder.groupLabels

        val counts = mutableMapOf<String, Int>()
        for (ch in channels) {
            when {
                ch.empty -> emptyCount++
                else -> {
                    // A channel can belong to up to 4 groups simultaneously —
                    // count it under every group it is assigned to.
                    val assigned = listOf(ch.group1, ch.group2, ch.group3, ch.group4)
                        .filter { it != "None" }
                    if (assigned.isEmpty()) ungroupedCount++
                    else assigned.forEach { g -> counts[g] = (counts[g] ?: 0) + 1 }
                }
            }
        }

        // Initial order: alphabetical (A→O); all 15 groups shown so user
        // can set a complete priority order. Count is 0 for unused groups.
        EepromConstants.GROUP_LETTERS.forEachIndexed { i, letter ->
            val count = counts[letter] ?: 0
            val label = labels.getOrNull(i)?.trim() ?: ""
            sortableGroups.add(SortGroup(letter, label, count))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RecyclerView setup with drag-to-reorder
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        sortAdapter = SortGroupAdapter(sortableGroups) { holder ->
            touchHelper.startDrag(holder)
        }

        binding.recyclerSortGroups.layoutManager = LinearLayoutManager(this)
        binding.recyclerSortGroups.adapter = sortAdapter

        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                rv: RecyclerView,
                from: RecyclerView.ViewHolder,
                to: RecyclerView.ViewHolder
            ): Boolean {
                val f = from.bindingAdapterPosition
                val t = to.bindingAdapterPosition
                if (f < 0 || t < 0) return false
                Collections.swap(sortableGroups, f, t)
                sortAdapter.notifyItemMoved(f, t)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            // Drag only via the handle, not long-press
            override fun isLongPressDragEnabled() = false
        }

        touchHelper = ItemTouchHelper(callback)
        touchHelper.attachToRecyclerView(binding.recyclerSortGroups)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI text helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun updateTexts() {
        val grouped = sortableGroups.sumOf { it.count }
        val activeGroups = sortableGroups.count { it.count > 0 }
        binding.textSortSummary.text = buildString {
            append("Drag groups into your preferred order. ")
            append("Channels will be reorganized so each group's channels appear together.\n\n")
            append("${grouped} grouped across ${activeGroups} group(s)")
            if (ungroupedCount > 0) append(" · ${ungroupedCount} ungrouped")
            if (emptyCount > 0)     append(" · ${emptyCount} empty slots")
        }

        binding.textSortFooter.text = buildString {
            if (ungroupedCount > 0)
                appendLine("Ungrouped channels will be placed after all group blocks.")
            append("Empty slots are always moved to the end.")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sort execution
    // ─────────────────────────────────────────────────────────────────────────

    private fun confirmAndSort(eep: ByteArray) {
        val orderDesc = sortableGroups.joinToString(" → ") { sg ->
            if (sg.label.isNotEmpty()) "${sg.letter} (${sg.label})" else sg.letter
        }
        AlertDialog.Builder(this)
            .setTitle("Sort Channels")
            .setMessage(
                "Reorganize channels in order:\n\n$orderDesc" +
                (if (ungroupedCount > 0) "\n→ Ungrouped" else "") +
                "\n→ Empty\n\n" +
                "Slot numbers will change. Tap Save to radio after to persist."
            )
            .setPositiveButton("Sort") { _, _ -> doSort(eep) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doSort(eep: ByteArray) {
        val channels = EepromParser.parseAllChannels(eep)

        // Priority: position in sortableGroups list; ungrouped after; empty last
        val priorityOf: Map<String, Int> = sortableGroups
            .mapIndexed { i, sg -> sg.letter to i }
            .toMap()
        val ungroupedPriority = sortableGroups.size
        val emptyPriority     = sortableGroups.size + 1

        // Stable sort — channels at the same priority keep their relative order.
        // Each channel is sorted under its HIGHEST-PRIORITY group (the one the
        // user placed earliest in the drag order), checking all four group slots.
        // A channel with no group assignments (all None) goes to ungroupedPriority.
        val sorted = channels.sortedWith(compareBy { ch ->
            when {
                ch.empty -> emptyPriority
                else -> {
                    val best = listOf(ch.group1, ch.group2, ch.group3, ch.group4)
                        .filter { it != "None" }
                        .mapNotNull { priorityOf[it] }
                        .minOrNull()        // lowest index = highest user priority
                    best ?: ungroupedPriority
                }
            }
        })

        // Write back with updated slot numbers (1-based)
        for ((idx, ch) in sorted.withIndex()) {
            EepromParser.writeChannel(eep, ch.copy(number = idx + 1))
        }

        EepromHolder.eeprom = eep

        val grouped = sortableGroups.sumOf { it.count }
        Toast.makeText(
            this,
            "Sorted $grouped channel(s) into ${sortableGroups.size} group block(s). Tap Save to upload.",
            Toast.LENGTH_LONG
        ).show()

        setResult(RESULT_OK)
        finish()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Data model + Adapter
    // ─────────────────────────────────────────────────────────────────────────

    data class SortGroup(
        val letter: String,
        val label:  String,
        val count:  Int
    )

    class SortGroupAdapter(
        private val items: MutableList<SortGroup>,
        private val onDragStart: (RecyclerView.ViewHolder) -> Unit
    ) : RecyclerView.Adapter<SortGroupAdapter.ViewHolder>() {

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val dragHandle: ImageView = itemView.findViewById(R.id.dragHandle)
            val letter:     TextView  = itemView.findViewById(R.id.sortGroupLetter)
            val label:      TextView  = itemView.findViewById(R.id.sortGroupLabel)
            val count:      TextView  = itemView.findViewById(R.id.sortGroupCount)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_sort_group, parent, false)
            return ViewHolder(view)
        }

        @Suppress("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.letter.text = item.letter
            holder.label.text  = if (item.label.isNotEmpty()) item.label else "(no label)"
            holder.count.text  = if (item.count > 0) "${item.count} ch" else "—"

            holder.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onDragStart(holder)
                }
                false
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
