package com.radiodroid.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.radiodroid.app.ui.applyEdgeToEdgeInsets
import androidx.lifecycle.lifecycleScope
import com.radiodroid.app.bridge.ChirpBridge
import com.radiodroid.app.databinding.ActivityRadioSelectBinding
import com.radiodroid.app.model.RadioInfo
import com.radiodroid.app.radio.CustomDriverManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Radio model selection screen.
 *
 * Loads the full CHIRP driver list from [ChirpBridge.getRadioList], shows it in a
 * searchable RecyclerView grouped by vendor, remembers the last selection in
 * SharedPreferences, and returns the chosen [RadioInfo] to the caller via
 * [EXTRA_VENDOR] / [EXTRA_MODEL] / [EXTRA_BAUD_RATE] Intent extras.
 *
 * Custom driver sideloading:
 *   The FAB ("Load .py Driver") launches the system file picker for *.py files.
 *   The selected file is copied to app-private internal storage by [CustomDriverManager],
 *   then loaded into the CHIRP driver registry via [ChirpBridge.loadCustomDriver].
 *   Newly registered radios are appended to [allRadios] and the list refreshes
 *   immediately.  Previously sideloaded drivers are re-registered on every cold
 *   start via [loadSavedCustomDrivers].
 */
class RadioSelectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRadioSelectBinding
    private lateinit var radioListAdapter: RadioListAdapter
    private lateinit var customDriverManager: CustomDriverManager

    /** Full sorted radio list (built-in + any loaded custom drivers). */
    private var allRadios: MutableList<RadioInfo> = mutableListOf()

    // ─────────────────────────────────────────────────────────────────────────
    // File picker launcher — picks a .py file for custom driver sideloading
    // ─────────────────────────────────────────────────────────────────────────

    private val pickDriverFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) importCustomDriver(uri)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityRadioSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applyEdgeToEdgeInsets()

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        customDriverManager = CustomDriverManager(this)

        setupLastRadioButton()
        setupSearch()
        setupRecyclerView()
        setupFab()
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

    /**
     * FAB opens the system file picker, filtered to Python source files.
     * Multiple MIME types are listed because Android file providers vary in how
     * they describe .py files (text/plain, text/x-python, application/octet-stream).
     */
    private fun setupFab() {
        binding.fabLoadDriver.setOnClickListener {
            pickDriverFile.launch(
                arrayOf(
                    "text/x-python",
                    "text/plain",
                    "application/octet-stream",
                    "*/*"           // last resort so the user can still navigate to .py
                )
            )
        }
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
                        .toMutableList()

                    // Re-register any custom drivers saved from previous sessions
                    loadSavedCustomDrivers()

                    showList()
                },
                onFailure = { e ->
                    binding.emptyText.text       = "Failed to load radio list:\n${e.message}"
                    binding.emptyText.visibility = View.VISIBLE
                    Toast.makeText(
                        this@RadioSelectActivity,
                        "Failed to load radio list: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    /**
     * Re-register all .py driver files previously imported in past sessions.
     * Any newly registered radios are merged into [allRadios].
     * Failures are logged as toasts but do not block the rest of the list.
     */
    private fun loadSavedCustomDrivers() {
        val saved = customDriverManager.listDriverFiles()
        if (saved.isEmpty()) return

        lifecycleScope.launch {
            for (file in saved) {
                runCatching {
                    val newRadios = ChirpBridge.loadCustomDriver(file.absolutePath)
                    mergeRadios(newRadios)
                }.onFailure { e ->
                    Toast.makeText(
                        this@RadioSelectActivity,
                        "Warning: could not reload ${file.name}: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            showList()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Custom driver import (triggered by FAB → file picker result)
    // ─────────────────────────────────────────────────────────────────────────

    private fun importCustomDriver(uri: Uri) {
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            runCatching {
                // 1. Copy file to internal storage
                val file = customDriverManager.importDriver(uri)

                // 2. Load it into CHIRP registry via Python
                val newRadios = ChirpBridge.loadCustomDriver(file.absolutePath)
                Pair(file.name, newRadios)
            }.fold(
                onSuccess = { (fileName, newRadios) ->
                    binding.progressBar.visibility = View.GONE
                    if (newRadios.isEmpty()) {
                        Toast.makeText(
                            this@RadioSelectActivity,
                            "$fileName loaded — no new radios registered.\n" +
                            "Check that the file uses @directory.register.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        mergeRadios(newRadios)
                        showList()
                        Toast.makeText(
                            this@RadioSelectActivity,
                            "$fileName: added ${newRadios.size} radio model(s)",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                onFailure = { e ->
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(
                        this@RadioSelectActivity,
                        "Failed to load driver: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // List helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Merge [newRadios] into [allRadios], avoiding duplicates, re-sort. */
    private fun mergeRadios(newRadios: List<RadioInfo>) {
        val existing = allRadios.map { "${it.vendor}|${it.model}" }.toHashSet()
        for (r in newRadios) {
            if ("${r.vendor}|${r.model}" !in existing) {
                allRadios.add(r)
                existing.add("${r.vendor}|${r.model}")
            }
        }
        allRadios.sortWith(compareBy({ it.vendor }, { it.model }))
    }

    private fun showList() {
        if (allRadios.isEmpty()) {
            binding.emptyText.text           = "No radios found — check that CHIRP drivers are bundled."
            binding.emptyText.visibility      = View.VISIBLE
            binding.recyclerRadios.visibility = View.GONE
        } else {
            binding.emptyText.visibility      = View.GONE
            binding.recyclerRadios.visibility = View.VISIBLE
            applyFilter(binding.searchEditText.text?.toString() ?: "")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Search / filter
    // ─────────────────────────────────────────────────────────────────────────

    private fun applyFilter(query: String) {
        if (allRadios.isEmpty()) return
        val q = query.trim()
        if (q.isBlank()) {
            radioListAdapter.submitGroupedList(allRadios, isFiltered = false)
            binding.emptyText.visibility      = View.GONE
            binding.recyclerRadios.visibility = View.VISIBLE
        } else {
            val filtered = allRadios.filter { r ->
                r.vendor.contains(q, ignoreCase = true) || r.model.contains(q, ignoreCase = true)
            }
            if (filtered.isEmpty()) {
                binding.emptyText.text            = "No results for \"$q\""
                binding.emptyText.visibility      = View.VISIBLE
                binding.recyclerRadios.visibility = View.GONE
            } else {
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

    /**
     * Deliver the selected radio to whatever started this activity:
     *
     *  - If started for result by [MainActivity] (user changing radio model):
     *    call setResult + finish so the [radioSelectLauncher] callback fires.
     *
     *  - If started as the launcher entry point (no calling activity):
     *    navigate directly to [MainActivity].  MainActivity reads the persisted
     *    radio from SharedPreferences in its own onCreate — persistLastRadio()
     *    is always called before deliverResult() for list selections, so the
     *    prefs are already up-to-date by the time MainActivity starts.
     */
    private fun deliverResult(radio: RadioInfo) {
        if (callingActivity != null) {
            // Launched for result (radio model change from within MainActivity)
            setResult(RESULT_OK, Intent().apply {
                putExtra(EXTRA_VENDOR,    radio.vendor)
                putExtra(EXTRA_MODEL,     radio.model)
                putExtra(EXTRA_BAUD_RATE, radio.baudRate)
            })
        } else {
            // Launched as app entry point — start MainActivity directly
            startActivity(Intent(this, MainActivity::class.java))
        }
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
