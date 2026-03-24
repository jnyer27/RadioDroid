package com.radiodroid.app

import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.radiodroid.app.ui.applyEdgeToEdgeInsets
import androidx.lifecycle.lifecycleScope
import com.radiodroid.app.bridge.ChirpBridge
import com.radiodroid.app.databinding.ActivityChirpImportBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.radiodroid.app.radio.Channel
import com.radiodroid.app.radio.ChirpCsvImporter
import com.radiodroid.app.radio.EepromConstants
import com.radiodroid.app.radio.EepromParser

/**
 * Shows a preview of channels parsed from a CHIRP CSV and lets the user confirm import
 * into the first available empty EEPROM slots. Optional TX power override applies to
 * all imported rows. Radio-specific parameters can be set afterward on the main screen.
 *
 * Launched by [MainActivity] after the user picks a CSV file.
 * Writes directly into [EepromHolder.eeprom]; MainActivity refreshes on resume.
 */
class ChirpImportActivity : AppCompatActivity() {

    companion object {
        /** Intent extra — the full text content of the CHIRP CSV file. */
        const val EXTRA_CSV_TEXT = "csv_text"
        /** Intent extra — optional comment column text shown in preview rows. */
        const val EXTRA_COMMENTS = "csv_comments"
    }

    private lateinit var binding: ActivityChirpImportBinding

    /** Parsed CHIRP entries (channel.number == 0 until slot is assigned). */
    private lateinit var entries: List<ChirpCsvImporter.ChirpEntry>

    /** Comment strings parallel to [entries] (may be empty strings). */
    private var comments: List<String> = emptyList()

    /** Channel numbers of empty slots in slot order (1-based). */
    private lateinit var emptySlots: List<Int>

    /** Valid starting indices into [emptySlots] from which all entries fit. */
    private lateinit var validStartIndices: List<Int>

    /**
     * Driver power level strings (same source as channel editor / bulk TX power), or
     * [EepromConstants.POWERLEVEL_LIST] when the driver has no discrete power list.
     */
    private var importPowerOptions: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityChirpImportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applyEdgeToEdgeInsets()

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // ── Guard: need EEPROM loaded ─────────────────────────────────────────
        val eep = EepromHolder.eeprom
        if (eep == null) {
            Toast.makeText(this, "No EEPROM loaded — load from radio first", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // ── Parse CSV ─────────────────────────────────────────────────────────
        val csvText = intent.getStringExtra(EXTRA_CSV_TEXT) ?: ""
        entries = ChirpCsvImporter.parse(csvText)

        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        comments = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(EXTRA_COMMENTS, ArrayList::class.java) as? ArrayList<String>
        } else {
            intent.getSerializableExtra(EXTRA_COMMENTS) as? ArrayList<String>
        } ?: List(entries.size) { "" }

        if (entries.isEmpty()) {
            Toast.makeText(this, "No valid channels found in CSV", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // ── Find empty slots ──────────────────────────────────────────────────
        emptySlots = EepromParser.parseAllChannels(eep)
            .filter { it.empty }
            .map { it.number }

        validStartIndices = if (entries.size <= emptySlots.size) {
            (0..emptySlots.size - entries.size).toList()
        } else {
            emptyList()
        }

        val canImport = minOf(entries.size, emptySlots.size)

        // ── Summary text ──────────────────────────────────────────────────────
        binding.textImportSummary.text = buildString {
            appendLine("${entries.size} channel(s) in CSV · ${emptySlots.size} empty slot(s) available")
            if (canImport == 0) {
                append("⚠ No empty slots — cannot import. Save existing channels to radio first.")
            } else if (entries.size > emptySlots.size) {
                append("⚠ Only $canImport of ${entries.size} channels will be imported (not enough empty slots)")
            } else {
                append("✓ All ${entries.size} channels will be imported")
            }
        }

        // ── TX Power spinner ──────────────────────────────────────────────────
        setupPowerSpinner()

        // ── Starting channel spinner ──────────────────────────────────────────
        setupStartSlotSpinner()

        // ── Preview rows ──────────────────────────────────────────────────────
        buildPreview()

        // ── Buttons ───────────────────────────────────────────────────────────
        binding.btnImportCancel.setOnClickListener { finish() }
        binding.btnImportConfirm.isEnabled = canImport > 0
        binding.btnImportConfirm.setOnClickListener { doImport(eep) }
    }

    // ── TX Power spinner ──────────────────────────────────────────────────────

    /**
     * "From CSV" (position 0) preserves the power value parsed from each CSV row.
     * Other positions use [EepromHolder.radioFeatures.validPowerLevels] when non-empty
     * (same as the channel editor and bulk TX power), else [EepromConstants.POWERLEVEL_LIST].
     */
    private fun setupPowerSpinner() {
        val driverLevels = EepromHolder.radioFeatures.validPowerLevels
        importPowerOptions = driverLevels.ifEmpty { EepromConstants.POWERLEVEL_LIST }
        val items = listOf("From CSV") + importPowerOptions
        binding.spinnerImportPower.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        // Default to "From CSV" (index 0)
        binding.spinnerImportPower.setSelection(0)
    }

    // ── Starting channel spinner ──────────────────────────────────────────────

    private fun setupStartSlotSpinner() {
        if (validStartIndices.isEmpty()) {
            binding.labelStartSlot.visibility = View.GONE
            binding.spinnerStartSlot.visibility = View.GONE
            return
        }
        val labels = validStartIndices.map { "Ch ${emptySlots[it]}" }
        binding.spinnerStartSlot.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        binding.spinnerStartSlot.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, view: View?, position: Int, id: Long
                ) = buildPreview()
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
    }

    /** Destination slot list based on the current starting channel spinner selection. */
    private fun currentSlots(): List<Int> {
        if (validStartIndices.isEmpty()) return emptySlots.take(entries.size)
        val startIdx = validStartIndices.getOrElse(
            binding.spinnerStartSlot.selectedItemPosition) { 0 }
        return emptySlots.subList(startIdx, startIdx + entries.size)
    }

    // ── Preview list ──────────────────────────────────────────────────────────

    private fun buildPreview() {
        val slots     = currentSlots()
        val canImport = slots.size
        val container = binding.previewContainer
        container.removeAllViews()

        for (i in 0 until canImport) {
            val entry = entries[i]
            val slot  = slots[i]
            val ch    = entry.channel

            val row = layoutInflater.inflate(R.layout.item_import_preview, container, false)

            row.findViewById<TextView>(R.id.importSlot).text = "→ Ch $slot"
            row.findViewById<TextView>(R.id.importName).text =
                ch.name.ifBlank { "(no name)" }
            row.findViewById<TextView>(R.id.importFreq).text =
                "%.4f MHz".format(ch.freqRxHz / 1_000_000.0)

            val chipGroup = row.findViewById<ChipGroup>(R.id.importChips)
            chipGroup.removeAllViews()
            addPreviewChip(chipGroup, "TX: ${previewToneLabel(ch.displayTxTone())}")
            addPreviewChip(chipGroup, "RX: ${previewToneLabel(ch.displayRxTone())}")
            addPreviewChip(chipGroup, duplexChipLabel(ch))

            // Comment (CSV Location + optional comment column)
            val comment = comments.getOrNull(i)?.trim() ?: ""
            val commentView = row.findViewById<TextView>(R.id.importComment)
            val commentText = buildString {
                append("CSV #${entry.csvLocation}")
                if (comment.isNotEmpty()) append(" · $comment")
            }
            commentView.text = commentText

            container.addView(row)
        }

        // Warn about channels that won't fit
        if (entries.size > emptySlots.size) {
            val skipped = entries.size - emptySlots.size
            val warn = TextView(this)
            warn.text = "($skipped channel(s) skipped — not enough empty slots)"
            @Suppress("DEPRECATION")
            warn.setTextColor(resources.getColor(android.R.color.holo_orange_dark))
            warn.setPadding(8, 12, 8, 4)
            container.addView(warn)
        }
    }

    private fun previewToneLabel(display: String): String =
        display.trim().ifEmpty { "None" }

    /** Duplex + offset for preview chip; simplex when no offset/split. Split shows TX MHz. */
    private fun duplexChipLabel(ch: Channel): String {
        if (ch.duplex == "split" && ch.freqTxHz > 0L) {
            return "Split " + "%.4f MHz".format(ch.freqTxHz / 1_000_000.0)
        }
        val d = ch.displayDuplex().trim()
        return if (d.isEmpty()) "Simplex" else d
    }

    private fun addPreviewChip(group: ChipGroup, label: String) {
        val chip = Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
            text = label
            isClickable = false
            isCheckable = false
            isFocusable = false
        }
        group.addView(chip)
    }

    // ── Import action ─────────────────────────────────────────────────────────

    private fun doImport(eep: ByteArray) {
        val slots     = currentSlots()
        val canImport = slots.size

        // Position 0 = "From CSV" (no override); positions 1+ map into [importPowerOptions]
        val powerPos = binding.spinnerImportPower.selectedItemPosition
        val powerOverride: String? = if (powerPos > 0)
            importPowerOptions.getOrNull(powerPos - 1)
        else null

        for (i in 0 until canImport) {
            val slot = slots[i]
            val ch   = entries[i].channel.copy(
                number = slot,
                power  = powerOverride ?: entries[i].channel.power,
            )
            EepromParser.writeChannel(eep, ch)
        }

        // Persist in EepromHolder so MainActivity picks it up on resume
        EepromHolder.eeprom = eep

        // Clone radios: list was updated via writeChannel but bytes were not — sync before finish
        // (lifecycleScope is cancelled on finish(), so we must not return until sync completes).
        val radio = EepromHolder.selectedRadio
        lifecycleScope.launch {
            try {
                if (radio != null && eep.isNotEmpty()) {
                    val synced = withContext(Dispatchers.IO) {
                        ChirpBridge.syncCloneMmapToChannelList(radio, eep, EepromHolder.channels.toList())
                    }
                    EepromHolder.eeprom = synced
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@ChirpImportActivity,
                    "Channels imported; EEPROM sync failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
            Toast.makeText(
                this@ChirpImportActivity,
                "Imported $canImport channel(s). Tap Save to upload to radio.",
                Toast.LENGTH_LONG
            ).show()
            setResult(RESULT_OK)
            finish()
        }
    }
}
