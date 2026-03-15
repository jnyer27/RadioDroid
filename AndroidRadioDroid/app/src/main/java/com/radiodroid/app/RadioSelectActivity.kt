package com.radiodroid.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.radiodroid.app.bridge.ChirpBridge
import com.radiodroid.app.databinding.ActivityRadioSelectBinding
import com.radiodroid.app.model.RadioInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Radio model selection screen.
 *
 * Loads the full CHIRP driver list from [ChirpBridge.getRadioList()], shows it in a
 * searchable RecyclerView grouped by vendor, remembers the last selection in
 * SharedPreferences, and returns the chosen [RadioInfo] to the caller via
 * [EXTRA_VENDOR] / [EXTRA_MODEL] / [EXTRA_BAUD_RATE] Intent extras.
 */
class RadioSelectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRadioSelectBinding
    private lateinit var radioListAdapter: RadioListAdapter

    /** Full sorted radio list loaded from CHIRP drivers. */
    private var allRadios: List<RadioInfo> = emptyList()

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRadioSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupLastRadioButton()
        setupSearch()
        setupRecyclerView()
        loadRadioList()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Setup helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Shows the "Continue with last: Vendor Model" shortcut if SharedPreferences has a prior pick. */
    private fun setupLastRadioButton() {
        val prefs      = getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        val lastVendor = prefs.getString(PREF_VENDOR, null) ?: return
        val lastModel  = prefs.getString(PREF_MODEL,  null) ?: return
        val lastBaud   = prefs.getInt(PREF_BAUD, 9600)

        binding.btnLastRadio.text = "Continue with: $lastVendor $lastModel"
        binding.btnLastRadio.visibility = View.VISIBLE
        binding.btnLastRadio.setOnClickListener {
            deliverResult(RadioInfo(lastVendor, lastModel, lastBaud))
        }
    }

    private fun setupSearch() {
        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int)     = Unit
            override fun afterTextChanged(s: Editable?) {
                applyFilter(s?.toString() ?: "")
            }
        })
    }

    private fun setupRecyclerView() {
        radioListAdapter = RadioListAdapter { radio ->
            persistLastRadio(radio)
            deliverResult(radio)
        }
        binding.recyclerRadios.adapter = radioListAdapter
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Load radio list from CHIRP
    // ─────────────────────────────────────────────────────────────────────────

    private fun loadRadioList() {
        binding.progressBar.visibility    = View.VISIBLE
        binding.emptyText.visibility      = View.VISIBLE
        binding.emptyText.text            = "Loading radio list…"
        binding.recyclerRadios.visibility = View.GONE

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { ChirpBridge.getRadioList() }
            }
            binding.progressBar.visibility = View.GONE

            result.fold(
                onSuccess = { radios ->
                    allRadios = radios.sortedWith(compareBy({ it.vendor }, { it.model }))
                    if (allRadios.isEmpty()) {
                        binding.emptyText.text    = "No radios found — check that CHIRP drivers are bundled."
                        binding.emptyText.visibility = View.VISIBLE
                    } else {
                        binding.emptyText.visibility      = View.GONE
                        binding.recyclerRadios.visibility = View.VISIBLE
                        applyFilter(binding.searchEditText.text?.toString() ?: "")
                    }
                },
                onFailure = { e ->
                    binding.emptyText.text    = "Failed to load radio list:\n${e.message}"
                    binding.emptyText.visibility = View.VISIBLE
                    Toast.makeText(this@RadioSelectActivity,
                        "Failed to load radio list: ${e.message}", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Search / filter
    // ─────────────────────────────────────────────────────────────────────────

    private fun applyFilter(query: String) {
        if (allRadios.isEmpty()) return
        val q = query.trim()
        if (q.isBlank()) {
            // No query — full grouped list with vendor headers
            radioListAdapter.submitGroupedList(allRadios, isFiltered = false)
            binding.emptyText.visibility      = View.GONE
            binding.recyclerRadios.visibility = View.VISIBLE
        } else {
            val filtered = allRadios.filter { r ->
                r.vendor.contains(q, ignoreCase = true) || r.model.contains(q, ignoreCase = true)
            }
            if (filtered.isEmpty()) {
                binding.emptyText.text           = "No results for \"$q\""
                binding.emptyText.visibility      = View.VISIBLE
                binding.recyclerRadios.visibility = View.GONE
            } else {
                // isFiltered = true → show vendor subtitle on each row instead of headers
                radioListAdapter.submitGroupedList(filtered, isFiltered = true)
                binding.emptyText.visibility      = View.GONE
                binding.recyclerRadios.visibility = View.VISIBLE
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Result delivery
    // ─────────────────────────────────────────────────────────────────────────

    private fun persistLastRadio(radio: RadioInfo) {
        getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit()
            .putString(PREF_VENDOR, radio.vendor)
            .putString(PREF_MODEL,  radio.model)
            .putInt(PREF_BAUD,      radio.baudRate)
            .apply()
    }

    private fun deliverResult(radio: RadioInfo) {
        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_VENDOR,    radio.vendor)
            putExtra(EXTRA_MODEL,     radio.model)
            putExtra(EXTRA_BAUD_RATE, radio.baudRate)
        })
        finish()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Companion / constants
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        /** Extras returned to the caller activity via setResult. */
        const val EXTRA_VENDOR    = "radio_vendor"
        const val EXTRA_MODEL     = "radio_model"
        const val EXTRA_BAUD_RATE = "radio_baud_rate"

        /** SharedPreferences file/keys — also read by MainActivity on startup. */
        const val PREFS_FILE  = "radiodroid_prefs"
        const val PREF_VENDOR = "last_vendor"
        const val PREF_MODEL  = "last_model"
        const val PREF_BAUD   = "last_baud"
    }
}
