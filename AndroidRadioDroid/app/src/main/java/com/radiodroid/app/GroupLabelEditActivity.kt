package com.radiodroid.app

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.textfield.TextInputEditText
import com.radiodroid.app.databinding.ActivityGroupLabelEditBinding
import com.radiodroid.app.radio.EepromConstants
import com.radiodroid.app.radio.EepromParser

/**
 * Allows the user to view and edit the 15 group labels (A–O) stored at
 * EEPROM offset 0x1C90 (6 bytes each, null-padded ASCII).
 *
 * Each label is limited to 5 printable characters; the 6th EEPROM byte is
 * always the null terminator consumed by the radio firmware.
 *
 * Changes are written into the in-memory [EepromHolder.eeprom] buffer and
 * [EepromHolder.groupLabels] immediately on save; they will be uploaded to
 * the radio the next time the user taps "Save to radio" in MainActivity.
 */
class GroupLabelEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGroupLabelEditBinding

    /** Parallel arrays of (letter TextView, label EditText) for each row A–O. */
    private lateinit var rows: List<Pair<TextView, TextInputEditText>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGroupLabelEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        if (EepromHolder.eeprom == null) {
            Toast.makeText(this, "No EEPROM data — load from radio first", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Each <include> with an id generates a typed binding object (ItemGroupLabelRowBinding).
        // Access the child views directly through those binding objects.
        val rowBindings = listOf(
            binding.rowA, binding.rowB, binding.rowC, binding.rowD, binding.rowE,
            binding.rowF, binding.rowG, binding.rowH, binding.rowI, binding.rowJ,
            binding.rowK, binding.rowL, binding.rowM, binding.rowN, binding.rowO
        )

        rows = rowBindings.mapIndexed { i, rowBinding ->
            Pair(rowBinding.groupLetter, rowBinding.groupLabelInput)
        }

        // Set letter badges and populate current labels
        val currentLabels = EepromHolder.groupLabels
        rows.forEachIndexed { i, (letterView, inputView) ->
            letterView.text = EepromConstants.GROUP_LETTERS[i]
            inputView.setText(currentLabels.getOrNull(i) ?: "")
        }

        binding.btnGroupCancel.setOnClickListener { finish() }
        binding.btnGroupSave.setOnClickListener { saveAndFinish() }
    }

    private fun saveAndFinish() {
        val eep = EepromHolder.eeprom ?: run {
            Toast.makeText(this, "No EEPROM data", Toast.LENGTH_SHORT).show()
            return
        }

        // Collect edited labels (trimmed, max 5 chars enforced by XML maxLength)
        val updatedLabels = rows.map { (_, input) ->
            input.text?.toString()?.trim() ?: ""
        }

        // Write into the in-memory EEPROM buffer
        EepromParser.writeGroupLabels(eep, updatedLabels)

        // Update the holder so the rest of the app sees the new labels immediately
        EepromHolder.eeprom = eep
        EepromHolder.groupLabels = updatedLabels

        Toast.makeText(this, "Group labels saved (upload to radio to persist)", Toast.LENGTH_SHORT).show()
        finish()
    }
}
