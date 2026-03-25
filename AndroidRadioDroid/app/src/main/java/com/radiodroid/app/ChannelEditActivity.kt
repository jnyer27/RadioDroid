package com.radiodroid.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.EditText
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.radiodroid.app.ui.applyEdgeToEdgeInsets
import androidx.lifecycle.lifecycleScope
import com.radiodroid.app.bridge.ChirpBridge
import com.radiodroid.app.databinding.ActivityChannelEditBinding
import com.radiodroid.app.databinding.ItemRadioSettingBinding
import com.radiodroid.app.model.ChannelExtraSetting
import com.radiodroid.app.model.RadioFeatures
import com.radiodroid.app.radio.EepromConstants
import com.radiodroid.app.radio.EepromParser
import com.radiodroid.app.radio.Protocol
import com.radiodroid.app.radio.Channel
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChannelEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChannelEditBinding
    private var channelNumber: Int = 1
    private var channel: Channel? = null
    private var eeprom: ByteArray? = null

    // ── Feature-driven lists (populated from CHIRP driver in onCreate) ─────────
    private lateinit var features: RadioFeatures
    /** Internal CHIRP duplex values matching each spinner position. */
    private var duplexValues: List<String> = emptyList()
    /** Driver-defined power level strings (empty = radio has no power levels). */
    private var powerLevels: List<String> = emptyList()
    /** Modulation modes supported by the selected radio driver. */
    private var modeList: List<String> = emptyList()

    /** Dynamic extra-param name -> EditText (fallback when no schema). */
    private val extraParamEditTexts = mutableMapOf<String, EditText>()
    /** Schema-driven extra: Spinner for list type. */
    private val extraSchemaSpinners = mutableMapOf<String, Spinner>()
    /** Schema-driven extra: Switch for bool type. */
    private val extraSchemaSwitches = mutableMapOf<String, SwitchCompat>()
    /** Schema-driven extra: EditText for int/float/string. */
    private val extraSchemaEditTexts = mutableMapOf<String, EditText>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityChannelEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.root.applyEdgeToEdgeInsets()

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        channelNumber = intent.getIntExtra(EXTRA_CHANNEL_NUMBER, 1)
        eeprom = EepromHolder.eeprom ?: ByteArray(0)
        if (EepromHolder.channels.isEmpty()) {
            Toast.makeText(this, "No radio memory loaded — download from radio first", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        channel = EepromParser.parseChannel(eeprom!!, channelNumber)

        binding.toolbar.title = getString(R.string.edit_channel) + " $channelNumber"

        // ── Feature-driven spinners (values come from the CHIRP driver) ────────
        features     = EepromHolder.radioFeatures
        duplexValues = features.validDuplexes
        modeList     = features.validModes
        powerLevels  = features.validPowerLevels

        // Duplex: labels come from the driver's own value set
        binding.spinnerDuplex.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            duplexValues.map { features.duplexLabel(it) }
        )

        // Power: hide entire section if the driver has no discrete power levels
        if (powerLevels.isNotEmpty()) {
            binding.spinnerPower.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item, powerLevels
            )
        } else {
            binding.textLabelPower.visibility    = View.GONE
            binding.rowPower.visibility          = View.GONE
            binding.textPowerCapWarning.visibility = View.GONE
        }

        // Mode: whatever the driver declares as valid
        binding.spinnerMode.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, modeList
        )

        // Bandwidth: expressed as FM vs NFM in CHIRP mode — hide the separate field
        binding.textLabelBandwidth.visibility = View.GONE
        binding.rowBandwidth.visibility       = View.GONE

        // Tone sections: show only what the driver supports
        val toneVis   = if (features.hasTone)                        View.VISIBLE else View.GONE
        val rxToneVis = if (features.hasRxCtcss || features.hasDtcs) View.VISIBLE else View.GONE
        binding.textLabelTxTone.visibility = toneVis
        binding.rowTxTone.visibility       = toneVis
        binding.textLabelRxTone.visibility = rxToneVis
        binding.rowRxTone.visibility       = rxToneVis

        // Driver-specific section: show when this radio exposes Memory.extra (schema or data)
        val hasDriverSpecific = EepromHolder.channelExtraSchema.isNotEmpty() ||
            EepromHolder.extraParamNames.isNotEmpty() ||
            EepromHolder.channels.any { it.extra.isNotEmpty() } ||
            EepromHolder.groupLabels.any { it.isNotBlank() }
        binding.sectionDriverSpecific.visibility = if (hasDriverSpecific) View.VISIBLE else View.GONE

        // Enforce driver name-length limit (0 = driver didn't declare one → cap at 16)
        val maxNameLen = if (features.validNameLength > 0) features.validNameLength else 16
        binding.editName.filters = arrayOf(InputFilter.LengthFilter(maxNameLen))

        // ── Flat tone spinners (one per side, no adapter swapping) ─────────────
        val toneAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, EepromConstants.TONE_LABELS)
        binding.spinnerTxTone.adapter = toneAdapter
        // RX gets its own adapter instance so the two spinners are independent
        val toneAdapterRx = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, EepromConstants.TONE_LABELS)
        binding.spinnerRxTone.adapter = toneAdapterRx

        // ── Populate all fields ────────────────────────────────────────────────
        channel?.let { c ->
            if (c.empty) {
                binding.editFreqRx.setText("")
                binding.editOffset.setText("")
                binding.editName.setText("")
                binding.spinnerDuplex.setSelection(0)
                binding.spinnerPower.setSelection(0)
                binding.spinnerMode.setSelection(0)
            } else {
                binding.editFreqRx.setText(
                    getString(R.string.format_frequency_mhz_four, c.freqRxHz / 1_000_000.0),
                )
                binding.editOffset.setText(
                    when (c.duplex) {
                        "+", "-" -> getString(
                            R.string.format_offset_khz_string,
                            (c.offsetHz / 1000).toInt(),
                        )
                        "split" -> getString(
                            R.string.format_frequency_mhz_four,
                            c.freqTxHz / 1_000_000.0,
                        )
                        else -> ""
                    },
                )
                binding.editName.setText(c.name)
                binding.spinnerDuplex.setSelection(
                    duplexValues.indexOf(c.duplex).coerceAtLeast(0))
                if (powerLevels.isNotEmpty()) {
                    binding.spinnerPower.setSelection(
                        powerLevels.indexOf(c.power).coerceAtLeast(0))
                }
                binding.spinnerMode.setSelection(
                    modeList.indexOf(c.mode).coerceAtLeast(0))
            }

            // Tone spinners — always populate regardless of empty flag.
            // setSelection() on a flat list never changes the adapter, so there is no
            // post-layout adapter-swap race condition.
            binding.spinnerTxTone.setSelection(
                EepromConstants.toneToIndex(c.txToneMode, c.txToneVal, c.txTonePolarity)
            )
            binding.spinnerRxTone.setSelection(
                EepromConstants.toneToIndex(c.rxToneMode, c.rxToneVal, c.rxTonePolarity)
            )

            // Dynamic extra params (Memory.extra) — typed UI from schema when available
            if (EepromHolder.channelExtraSchema.isNotEmpty()) {
                ensureExtraParamRows()
                EepromHolder.channelExtraSchema.forEach { setting ->
                    val current = c.extra[setting.name] ?: setting.value
                    when (setting.type) {
                        "list" -> extraSchemaSpinners[setting.name]?.let { spinner ->
                            val opts = setting.options ?: emptyList()
                            val idx = opts.indexOf(current).coerceIn(0, opts.size - 1)
                            if (idx >= 0 && idx < spinner.adapter?.count ?: 0) spinner.setSelection(idx)
                        }
                        "bool" -> extraSchemaSwitches[setting.name]?.isChecked =
                            current.equals("True", ignoreCase = true)
                        else -> extraSchemaEditTexts[setting.name]?.setText(current)
                    }
                }
            } else if (EepromHolder.extraParamNames.isNotEmpty()) {
                ensureExtraParamRows()
                EepromHolder.extraParamNames.forEach { name ->
                    extraParamEditTexts[name]?.setText(c.extra[name] ?: "")
                }
            }

            updateBusyLockState()

        }

        // ── Busy Lock ↔ Duplex live coupling ──────────────────────────────────
        // Busy Lock is incompatible with repeater/split operation: the radio does
        // not allow Busy Lock when a TX offset is configured.  Disable + clear the
        // switch automatically whenever the user selects a duplex value, and
        // re-enable it when they select Off (simplex).
        binding.spinnerDuplex.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateBusyLockState()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        updateBusyLockState()   // evaluate initial state after population

        binding.btnCancel.setOnClickListener { finish() }
        binding.btnDone.setOnClickListener { saveAndFinish() }

        HelpSystem.init(this)
        setupHelpButtons()
    }

    /** Populate containerExtraParams from schema (typed UI) or extraParamNames (EditText fallback). */
    private fun ensureExtraParamRows() {
        val schema = EepromHolder.channelExtraSchema
        if (schema.isNotEmpty()) {
            if (extraSchemaSpinners.isNotEmpty() || extraSchemaEditTexts.isNotEmpty()) return // already built
            binding.containerExtraParams.removeAllViews()
            extraParamEditTexts.clear()
            extraSchemaSpinners.clear()
            extraSchemaSwitches.clear()
            extraSchemaEditTexts.clear()
            schema.forEach { setting ->
                val rowBinding = ItemRadioSettingBinding.inflate(layoutInflater, binding.containerExtraParams, false)
                rowBinding.labelSetting.text = setting.name
                rowBinding.wrapEdit.visibility = View.GONE
                rowBinding.wrapSwitch.visibility = View.GONE
                rowBinding.spinnerValue.visibility = View.GONE
                when (setting.type) {
                    "list" -> {
                        val options = setting.options ?: emptyList()
                        if (options.isNotEmpty()) {
                            rowBinding.spinnerValue.visibility = View.VISIBLE
                            rowBinding.spinnerValue.adapter = ArrayAdapter(
                                this, android.R.layout.simple_spinner_dropdown_item, options
                            )
                            rowBinding.spinnerValue.isEnabled = !setting.readOnly
                            extraSchemaSpinners[setting.name] = rowBinding.spinnerValue
                        } else {
                            rowBinding.wrapEdit.visibility = View.VISIBLE
                            rowBinding.editValue.inputType = android.text.InputType.TYPE_CLASS_TEXT
                            rowBinding.editValue.isEnabled = !setting.readOnly
                            extraSchemaEditTexts[setting.name] = rowBinding.editValue
                        }
                    }
                    "bool" -> {
                        rowBinding.wrapSwitch.visibility = View.VISIBLE
                        rowBinding.switchValue.isEnabled = !setting.readOnly
                        extraSchemaSwitches[setting.name] = rowBinding.switchValue
                    }
                    "int" -> {
                        rowBinding.wrapEdit.visibility = View.VISIBLE
                        rowBinding.editValue.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                            android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                        rowBinding.editValue.isEnabled = !setting.readOnly
                        extraSchemaEditTexts[setting.name] = rowBinding.editValue
                    }
                    "float" -> {
                        rowBinding.wrapEdit.visibility = View.VISIBLE
                        rowBinding.editValue.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                        rowBinding.editValue.isEnabled = !setting.readOnly
                        extraSchemaEditTexts[setting.name] = rowBinding.editValue
                    }
                    else -> {
                        rowBinding.wrapEdit.visibility = View.VISIBLE
                        rowBinding.editValue.inputType = android.text.InputType.TYPE_CLASS_TEXT
                        setting.maxLength?.let { rowBinding.editValue.filters = arrayOf(InputFilter.LengthFilter(it)) }
                        rowBinding.editValue.isEnabled = !setting.readOnly
                        extraSchemaEditTexts[setting.name] = rowBinding.editValue
                    }
                }
                binding.containerExtraParams.addView(rowBinding.root)
            }
            return
        }
        val names = EepromHolder.extraParamNames
        if (names.isEmpty()) return
        if (extraParamEditTexts.size == names.size) return // already built
        binding.containerExtraParams.removeAllViews()
        extraParamEditTexts.clear()
        extraSchemaSpinners.clear()
        extraSchemaSwitches.clear()
        extraSchemaEditTexts.clear()
        names.forEach { name ->
            val row = layoutInflater.inflate(R.layout.item_extra_param, binding.containerExtraParams, false)
            (row as? ViewGroup)?.getChildAt(0)?.let { if (it is TextInputLayout) it.hint = name }
            val edit = row.findViewById<EditText>(R.id.editExtraParam)
            extraParamEditTexts[name] = edit
            binding.containerExtraParams.addView(row)
        }
    }

    private fun setupHelpButtons() {
        mapOf(
            binding.helpFreqRx    to "freq_rx",
            binding.helpOffset    to "freq_tx_offset",
            binding.helpName      to "channel_name",
            binding.helpDuplex    to "duplex",
            binding.helpPower     to "tx_power",
            binding.helpMode      to "modulation",
            binding.helpBandwidth to "bandwidth",
            binding.helpTxTone    to "tx_tone",
            binding.helpRxTone    to "rx_tone",
        ).forEach { (btn, key) ->
            btn.setOnClickListener { HelpSystem.show(this, key) }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Power cap advisory
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Shows or hides the power cap advisory below the power spinner.
     *
     * The warning is shown (non-blocking) when the picker position corresponds to
     * a raw power byte that exceeds the applicable VHF or UHF cap from
     * [EepromHolder.tuneSettings]. The spinner selection is never restricted —
     * the radio enforces the cap at TX time; the stored byte is unchanged.
     */
    private fun updatePowerCapWarning(pickerPosition: Int) {
        val ch = channel ?: run {
            binding.textPowerCapWarning.visibility = View.GONE
            return
        }
        // Only meaningful for non-empty channels with a known frequency
        if (ch.freqRxHz <= 0) {
            binding.textPowerCapWarning.visibility = View.GONE
            return
        }

        val powerStr = EepromConstants.POWERLEVEL_LIST.getOrNull(pickerPosition) ?: "N/T"
        val rawPower = powerStr.toIntOrNull() ?: 0   // "N/T" → 0, treated as no-TX

        val ts       = EepromHolder.tuneSettings
        val isVhf    = ch.freqRxHz < EepromConstants.VHF_UHF_BOUNDARY_HZ
        val cap      = if (isVhf) ts.maxPowerSettingVHF else ts.maxPowerSettingUHF
        val bandLabel = if (isVhf) "VHF" else "UHF"

        if (rawPower > 0 && rawPower > cap) {
            val capWatts = EepromConstants.powerToWatts(cap.toString())
            binding.textPowerCapWarning.text = getString(
                R.string.channel_edit_power_cap_warning,
                bandLabel,
                cap,
                capWatts,
            )
            binding.textPowerCapWarning.visibility = View.VISIBLE
        } else {
            binding.textPowerCapWarning.visibility = View.GONE
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BCL-style extras / duplex rule (Memory.extra only — not chirp_common.Memory fields)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Known CHIRP driver keys for busy channel lock / BCL exposed as [Memory.extra].
     * (Universal [chirp_common.Memory] has no busy-lock attribute.)
     */
    private fun matchesBusyLockExtraKey(name: String): Boolean {
        val n = name.trim().lowercase().replace(" ", "_")
        return n == "busylock" || n == "busy_lock" ||
            n == "bcl" || n == "bclo" || n == "bclb" ||
            n == "busychannellockout" || n == "busy_lockout"
    }

    private fun busyLockControlledByDriverExtras(): Boolean =
        EepromHolder.channelExtraSchema.any { matchesBusyLockExtraKey(it.name) } ||
            EepromHolder.extraParamNames.any { matchesBusyLockExtraKey(it) }

    /** Extra keys that map to busy lock for the current radio (schema + fallback names). */
    private fun busyLockExtraKeys(): List<String> = buildList {
        EepromHolder.channelExtraSchema.forEach { if (matchesBusyLockExtraKey(it.name)) add(it.name) }
        EepromHolder.extraParamNames.forEach { if (matchesBusyLockExtraKey(it)) add(it) }
    }.distinct()

    /**
     * Busy lock is incompatible with repeater/split (+, -, split). For bool extras we disable
     * the switch; for free-text extras we set "False" and disable while an offset is selected.
     */
    private fun updateBusyLockState() {
        val duplex    = duplexValues.getOrNull(binding.spinnerDuplex.selectedItemPosition) ?: ""
        val hasOffset = duplex != "" && duplex != "off"

        if (!busyLockControlledByDriverExtras()) return

        busyLockExtraKeys().forEach { key ->
            extraSchemaSwitches[key]?.let { sw ->
                if (hasOffset) {
                    sw.isChecked = false
                    sw.isEnabled = false
                } else {
                    sw.isEnabled = true
                }
            }
            extraParamEditTexts[key]?.let { edit ->
                if (hasOffset) {
                    edit.setText(getString(R.string.chirp_bool_false))
                    edit.isEnabled = false
                } else {
                    edit.isEnabled = true
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Save
    // ─────────────────────────────────────────────────────────────────────────

    private fun saveAndFinish() {
        val eep = eeprom ?: return
        val c   = channel ?: return

        val freqRxStr    = binding.editFreqRx.text?.toString()?.trim()
        val duplexStr    = duplexValues.getOrNull(binding.spinnerDuplex.selectedItemPosition) ?: ""
        val maxLen       = if (features.validNameLength > 0) features.validNameLength else 16
        val nameStr      = (binding.editName.text?.toString() ?: "").take(maxLen)
        // When powerLevels is empty (power section hidden) preserve the channel's
        // existing power value so it doesn't get cleared to "" (which displays as "?").
        val powerStr     = if (powerLevels.isNotEmpty())
            powerLevels.getOrNull(binding.spinnerPower.selectedItemPosition) ?: ""
        else
            channel?.power ?: ""
        val modeStr      = modeList.getOrNull(binding.spinnerMode.selectedItemPosition) ?: "FM"

        val empty = freqRxStr.isNullOrBlank()
        if (empty) {
            c.empty      = true
            c.freqRxHz   = 0
            c.freqTxHz   = 0
            c.offsetHz   = 0
            c.duplex     = ""
            c.name       = ""
            c.power      = powerLevels.firstOrNull() ?: ""
            c.mode       = modeList.firstOrNull() ?: "FM"
        } else {
            val freqRxMhz = freqRxStr.toDoubleOrNull() ?: 0.0
            val freqRxHz  = (freqRxMhz * 1_000_000).toLong()
            if (freqRxHz <= 0) {
                Toast.makeText(this, "Invalid frequency", Toast.LENGTH_SHORT).show()
                return
            }
            val offsetStr = binding.editOffset.text?.toString()?.trim()
            var offsetHz  = 0L
            var txHz      = freqRxHz
            when (duplexStr) {
                "+" -> {
                    offsetHz = (offsetStr?.toLongOrNull() ?: 0) * 1000
                    txHz     = freqRxHz + offsetHz
                }
                "-" -> {
                    offsetHz = (offsetStr?.toLongOrNull() ?: 0) * 1000
                    txHz     = freqRxHz - offsetHz
                }
                "split" -> {
                    val txMhz = offsetStr?.toDoubleOrNull() ?: freqRxMhz
                    txHz      = (txMhz * 1_000_000).toLong()
                    offsetHz  = txHz
                }
                else -> { }
            }
            c.empty    = false
            c.freqRxHz = freqRxHz
            c.freqTxHz = txHz
            c.offsetHz = kotlin.math.abs(freqRxHz - txHz)
            c.duplex   = duplexStr
            c.name     = nameStr
            c.power    = powerStr
            c.mode     = modeStr
        }

        // Tone — always saved regardless of empty flag so tones survive frequency edits
        val (txMode, txVal, txPol) = EepromConstants.indexToTone(binding.spinnerTxTone.selectedItemPosition)
        val (rxMode, rxVal, rxPol) = EepromConstants.indexToTone(binding.spinnerRxTone.selectedItemPosition)
        c.txToneMode     = txMode
        c.txToneVal      = txVal
        c.txTonePolarity = txPol
        c.rxToneMode     = rxMode
        c.rxToneVal      = rxVal
        c.rxTonePolarity = rxPol

        // Dynamic extra (Memory.extra) — from schema-driven UI or fallback EditTexts
        if (EepromHolder.channelExtraSchema.isNotEmpty() &&
            (extraSchemaSpinners.isNotEmpty() || extraSchemaSwitches.isNotEmpty() || extraSchemaEditTexts.isNotEmpty())) {
            val extra = mutableMapOf<String, String>()
            EepromHolder.channelExtraSchema.forEach { setting ->
                val value = when (setting.type) {
                    "list" -> extraSchemaSpinners[setting.name]?.let { spinner ->
                        (spinner.selectedItem as? String) ?: ""
                    } ?: ""
                    "bool" -> if (extraSchemaSwitches[setting.name]?.isChecked == true) "True" else "False"
                    else -> extraSchemaEditTexts[setting.name]?.text?.toString()?.trim() ?: ""
                }
                extra[setting.name] = value
            }
            c.extra = extra
        } else if (extraParamEditTexts.isNotEmpty()) {
            c.extra = extraParamEditTexts.mapValues { it.value.text?.toString()?.trim() ?: "" }
        }

        // BCL-style extras: force False when duplex implies TX offset (matches NICFW driver rule).
        val hasOffset = duplexStr == "+" || duplexStr == "-" ||
            duplexStr.equals("split", ignoreCase = true)
        if (hasOffset && busyLockExtraKeys().isNotEmpty()) {
            val merged = c.extra.toMutableMap()
            busyLockExtraKeys().forEach { key -> merged[key] = "False" }
            c.extra = merged
        }

        c.driverMode = c.mode
        c.bandwidth = if (c.empty) {
            "Wide"
        } else {
            c.extra["bandwidth"]?.trim()?.takeIf { it.isNotEmpty() }
                ?: if (c.mode == "NFM" || c.mode == "NAM") "Narrow" else "Wide"
        }

        val radio = EepromHolder.selectedRadio
        if (eep.isNotEmpty() && radio != null) {
            lifecycleScope.launch {
                var isClone = false
                try {
                    isClone = withContext(Dispatchers.IO) { ChirpBridge.isCloneModeRadio(radio) }
                    if (isClone && !c.empty) {
                        val b64 = Base64.encodeToString(eep, Base64.NO_WRAP)
                        val problems = withContext(Dispatchers.IO) {
                            ChirpBridge.validateChannel(radio, b64, c)
                        }
                        val errors = problems.filter { it.kind == "error" }
                        if (errors.isNotEmpty()) {
                            AlertDialog.Builder(this@ChannelEditActivity)
                                .setTitle("Cannot save channel")
                                .setMessage(errors.joinToString("\n\n") { it.text })
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                            return@launch
                        }
                    }
                    EepromParser.writeChannel(eep, c)
                    if (isClone) {
                        val b64 = Base64.encodeToString(eep, Base64.NO_WRAP)
                        val newEep = ChirpBridge.applyChannelToMmap(radio, b64, c)
                        EepromHolder.eeprom = newEep
                    } else {
                        EepromHolder.eeprom = eep
                    }
                } catch (e: Throwable) {
                    Toast.makeText(
                        this@ChannelEditActivity,
                        e.message ?: "Save failed",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                setResult(RESULT_OK)
                finish()
            }
        } else {
            EepromParser.writeChannel(eep, c)
            EepromHolder.eeprom = eep
            setResult(RESULT_OK)
            finish()
        }
    }

    companion object {
        private const val EXTRA_CHANNEL_NUMBER = "channel_number"

        fun intent(context: Context, channelNumber: Int, eeprom: ByteArray): Intent {
            // Only update EepromHolder.eeprom when actual bytes are provided.
            // Passing ByteArray(0) (the call-site convention when EEPROM is already
            // in EepromHolder) must NOT overwrite the live clone EEPROM, otherwise
            // Export Raw EEPROM greyed out after every channel open and subsequent
            // backups would omit eeprom_base64, breaking channel-extra spinners.
            if (eeprom.isNotEmpty()) EepromHolder.eeprom = eeprom
            return Intent(context, ChannelEditActivity::class.java).putExtra(EXTRA_CHANNEL_NUMBER, channelNumber)
        }
    }
}
