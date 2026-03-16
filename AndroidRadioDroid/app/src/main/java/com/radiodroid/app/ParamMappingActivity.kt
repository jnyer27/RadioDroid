package com.radiodroid.app

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.radiodroid.app.databinding.ActivityParamMappingBinding
import com.radiodroid.app.model.RadioInfo
import com.radiodroid.app.radio.ParamMapping
import com.radiodroid.app.radio.ParamMappingStore

/**
 * Lets the user map driver-specific Mode (and derived Bandwidth) values to
 * universal display values. Supports a global default plus optional per-model
 * overrides, persisted in SharedPreferences.
 */
class ParamMappingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityParamMappingBinding

    /** Current radio when launched with vendor/model extras; null when editing global default only. */
    private var radio: RadioInfo? = null

    /** Driver mode strings to show rows for (from current driver or default list). */
    private var driverModes: List<String> = emptyList()

    /** Spinners per driver mode: index matches [driverModes]. Pair = (mode spinner, bandwidth spinner). */
    private var rowSpinners: MutableList<Pair<Spinner, Spinner>> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityParamMappingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val vendor = intent.getStringExtra(EXTRA_VENDOR)
        val model = intent.getStringExtra(EXTRA_MODEL)
        radio = if (!vendor.isNullOrBlank() && !model.isNullOrBlank()) {
            RadioInfo(vendor, model, intent.getIntExtra(EXTRA_BAUD_RATE, 9600))
        } else null

        if (radio != null) {
            binding.textMappingFor.text = getString(R.string.param_mapping_for, radio!!.vendor, radio!!.model)
            binding.textNoRadioHint.visibility = View.GONE
            driverModes = EepromHolder.radioFeatures.validModes
            val hasOverride = ParamMappingStore.getMapping(this, radio) != ParamMappingStore.getMapping(this, null)
            binding.switchCustomizeForRadio.isChecked = hasOverride
        } else {
            binding.textMappingFor.text = getString(R.string.param_mapping_global_default)
            binding.textNoRadioHint.visibility = View.VISIBLE
            binding.switchCustomizeForRadio.visibility = View.GONE
            driverModes = DEFAULT_DRIVER_MODES
        }

        if (driverModes.isEmpty()) {
            driverModes = DEFAULT_DRIVER_MODES
        }

        buildRows()
        loadMappingIntoUi()

        if (radio != null) {
            binding.switchCustomizeForRadio.setOnCheckedChangeListener { _, _ -> loadMappingIntoUi() }
        }
        binding.btnReset.setOnClickListener { resetToBuiltInDefault() }
        binding.btnSave.setOnClickListener { saveFromUi() }
    }

    private fun buildRows() {
        val container = binding.mappingRows
        container.removeAllViews()
        rowSpinners.clear()

        val modeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, UNIVERSAL_MODES).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val bwAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, UNIVERSAL_BANDWIDTHS).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        for (driverMode in driverModes) {
            val row = layoutInflater.inflate(R.layout.item_param_mapping_row, container, false) as LinearLayout
            val label = row.findViewById<TextView>(R.id.labelDriverValue)
            val spinnerMode = row.findViewById<Spinner>(R.id.spinnerMode)
            val spinnerBandwidth = row.findViewById<Spinner>(R.id.spinnerBandwidth)

            label.text = getString(R.string.param_mapping_driver_value, driverMode)
            spinnerMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, UNIVERSAL_MODES).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            spinnerBandwidth.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, UNIVERSAL_BANDWIDTHS).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            container.addView(row)
            rowSpinners.add(Pair(spinnerMode, spinnerBandwidth))
        }
    }

    private fun loadMappingIntoUi() {
        val mapping = if (radio != null && binding.switchCustomizeForRadio.isChecked)
            ParamMappingStore.getMapping(this, radio)
        else
            ParamMappingStore.getMapping(this, null)

        for (i in driverModes.indices) {
            val driverMode = driverModes[i]
            val (modeSpinner, bwSpinner) = rowSpinners[i]
            val universalMode = mapping.modeMap[driverMode] ?: driverMode
            val universalBw = mapping.bandwidthMap[driverMode] ?: if (driverMode == "NFM" || driverMode == "NAM") "Narrow" else "Wide"
            val modeIdx = UNIVERSAL_MODES.indexOf(universalMode).coerceAtLeast(0)
            val bwIdx = UNIVERSAL_BANDWIDTHS.indexOf(universalBw).coerceAtLeast(0)
            modeSpinner.setSelection(modeIdx)
            bwSpinner.setSelection(bwIdx)
        }
    }

    private fun resetToBuiltInDefault() {
        for (i in driverModes.indices) {
            val driverMode = driverModes[i]
            val (modeSpinner, bwSpinner) = rowSpinners[i]
            modeSpinner.setSelection(UNIVERSAL_MODES.indexOf(driverMode).coerceIn(0, UNIVERSAL_MODES.lastIndex))
            bwSpinner.setSelection(
                if (driverMode == "NFM" || driverMode == "NAM") UNIVERSAL_BANDWIDTHS.indexOf("Narrow")
                else UNIVERSAL_BANDWIDTHS.indexOf("Wide")
            )
        }
        Toast.makeText(this, "Reset to built-in default (not saved yet). Tap Save to apply.", Toast.LENGTH_SHORT).show()
    }

    private fun saveFromUi() {
        val modeMap = mutableMapOf<String, String>()
        val bandwidthMap = mutableMapOf<String, String>()
        for (i in driverModes.indices) {
            val driverMode = driverModes[i]
            val (modeSpinner, bwSpinner) = rowSpinners[i]
            modeMap[driverMode] = UNIVERSAL_MODES[modeSpinner.selectedItemPosition]
            bandwidthMap[driverMode] = UNIVERSAL_BANDWIDTHS[bwSpinner.selectedItemPosition]
        }
        val mapping = ParamMapping(modeMap = modeMap, bandwidthMap = bandwidthMap)

        if (radio != null && binding.switchCustomizeForRadio.isChecked) {
            ParamMappingStore.saveForModel(this, radio!!, mapping)
            Toast.makeText(this, "Saved mapping for ${radio!!.vendor} ${radio!!.model}", Toast.LENGTH_SHORT).show()
        } else {
            ParamMappingStore.saveDefault(this, mapping)
            if (radio != null) {
                ParamMappingStore.saveForModel(this, radio!!, null)
            }
            Toast.makeText(this, "Saved as global default", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    companion object {
        const val EXTRA_VENDOR = "vendor"
        const val EXTRA_MODEL = "model"
        const val EXTRA_BAUD_RATE = "baud_rate"

        private val UNIVERSAL_MODES = listOf("FM", "NFM", "AM", "WFM", "NAM", "USB", "LSB", "DV")
        private val UNIVERSAL_BANDWIDTHS = listOf("Wide", "Narrow")
        private val DEFAULT_DRIVER_MODES = listOf("FM", "NFM", "AM")

        fun intent(context: android.content.Context, radio: RadioInfo?): android.content.Intent {
            val i = android.content.Intent(context, ParamMappingActivity::class.java)
            radio?.let {
                i.putExtra(EXTRA_VENDOR, it.vendor)
                i.putExtra(EXTRA_MODEL, it.model)
                i.putExtra(EXTRA_BAUD_RATE, it.baudRate)
            }
            return i
        }
    }
}
