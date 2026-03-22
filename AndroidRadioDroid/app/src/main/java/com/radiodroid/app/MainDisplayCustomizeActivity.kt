package com.radiodroid.app

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.radiodroid.app.ui.applyEdgeToEdgeInsets
import com.radiodroid.app.databinding.ActivityMainDisplayCustomizeBinding
import com.radiodroid.app.radio.MainDisplayPref

/**
 * Lets the user choose up to two channel values to show below Power on each
 * channel row on the main list. Options include standard fields (Bandwidth,
 * Mode, Duplex, etc.) and radio-specific extra params when a radio is loaded.
 */
class MainDisplayCustomizeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainDisplayCustomizeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainDisplayCustomizeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applyEdgeToEdgeInsets()

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val options = MainDisplayPref.getDisplayOptions(this)
        val labels = options.map { it.second }
        val keys = options.map { it.first }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSlot1.adapter = adapter
        binding.spinnerSlot2.adapter = adapter

        val slot1 = MainDisplayPref.getSlot1(this)
        val slot2 = MainDisplayPref.getSlot2(this)
        val idx1 = keys.indexOf(slot1).coerceAtLeast(0)
        val idx2 = keys.indexOf(slot2).coerceAtLeast(0)
        binding.spinnerSlot1.setSelection(idx1)
        binding.spinnerSlot2.setSelection(idx2)

        binding.btnSave.setOnClickListener {
            val i1 = (binding.spinnerSlot1.selectedItemPosition).coerceIn(0, keys.lastIndex)
            val i2 = (binding.spinnerSlot2.selectedItemPosition).coerceIn(0, keys.lastIndex)
            MainDisplayPref.setSlots(this, keys[i1], keys[i2])
            Toast.makeText(this, getString(R.string.save) + " — main list will update.", Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        }
    }
}
