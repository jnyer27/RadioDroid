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
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.radiodroid.app.bridge.ChirpBridge
import com.radiodroid.app.databinding.ActivityChannelEditBinding
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

    /** Dynamic extra-param name -> EditText for driver-specific channel fields. */
    private val extraParamEditTexts = mutableMapOf<String, EditText>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityChannelEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(
                left   = systemBars.left,
                top    = systemBars.top,
                right  = systemBars.right,
                bottom = systemBars.bottom
            )
            insets
        }

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

        // Driver-specific section: show when this radio uses group slots or Memory.extra params
        val hasDriverGroups = EepromHolder.groupLabels.any { it.isNotBlank() } ||
            EepromHolder.channels.any { ch ->
                listOf(ch.group1, ch.group2, ch.group3, ch.group4).any { it != "None" }
            } ||
            EepromHolder.extraParamNames.isNotEmpty()
        binding.sectionDriverSpecific.visibility = if (hasDriverGroups) View.VISIBLE else View.GONE

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
                binding.editFreqRx.setText("%.4f".format(c.freqRxHz / 1_000_000.0))
                binding.editOffset.setText(when (c.duplex) {
                    "+", "-" -> (c.offsetHz / 1000).toString()
                    "split"  -> (c.freqTxHz / 1_000_000.0).toString()
                    else     -> ""
                })
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

            // Dynamic extra params (Memory.extra) — includes Group 1–4 when driver exposes them
            if (EepromHolder.extraParamNames.isNotEmpty()) {
                ensureExtraParamRows()
                EepromHolder.extraParamNames.forEach { name ->
                    extraParamEditTexts[name]?.setText(c.extra[name] ?: "")
                }
            }

            // Busy Lock — populate first, then enforce the duplex rule
            binding.switchBusyLock.isChecked = c.busyLock
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

    /** Populate containerExtraParams with one row per [EepromHolder.extraParamNames]. */
    private fun ensureExtraParamRows() {
        val names = EepromHolder.extraParamNames
        if (names.isEmpty()) return
        if (extraParamEditTexts.size == names.size) return // already built
        binding.containerExtraParams.removeAllViews()
        extraParamEditTexts.clear()
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
            binding.helpBusyLock  to "busy_lock",
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
            binding.textPowerCapWarning.text =
                "⚠ Exceeds $bandLabel cap ($cap ≈ $capWatts) — radio will clamp to cap at TX time"
            binding.textPowerCapWarning.visibility = View.VISIBLE
        } else {
            binding.textPowerCapWarning.visibility = View.GONE
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Busy Lock / Duplex rule
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Enforces the radio rule: Busy Lock is incompatible with a repeater/split offset.
     * When a duplex value (+, -, split) is present the switch is unchecked and disabled
     * so the user cannot accidentally enable it.  Clears back to enabled when simplex.
     */
    private fun updateBusyLockState() {
        // Busy Lock is incompatible with any duplex that adds a TX offset.
        // Simplex ("") and TX-off ("off") are the only values where it is allowed.
        val duplex    = duplexValues.getOrNull(binding.spinnerDuplex.selectedItemPosition) ?: ""
        val hasOffset = duplex != "" && duplex != "off"
        if (hasOffset) {
            binding.switchBusyLock.isChecked = false
            binding.switchBusyLock.isEnabled = false
        } else {
            binding.switchBusyLock.isEnabled = true
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
            c.bandwidth  = "Wide"
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
        // Driver mode for upload; bandwidth in sync with display mode
        c.driverMode = c.mode
        c.bandwidth  = if (c.mode == "NFM" || c.mode == "NAM") "Narrow" else "Wide"

        // Tone — always saved regardless of empty flag so tones survive frequency edits
        val (txMode, txVal, txPol) = EepromConstants.indexToTone(binding.spinnerTxTone.selectedItemPosition)
        val (rxMode, rxVal, rxPol) = EepromConstants.indexToTone(binding.spinnerRxTone.selectedItemPosition)
        c.txToneMode     = txMode
        c.txToneVal      = txVal
        c.txTonePolarity = txPol
        c.rxToneMode     = rxMode
        c.rxToneVal      = rxVal
        c.rxTonePolarity = rxPol

        // Dynamic extra (Memory.extra) — includes Group 1–4 when driver exposes them
        if (extraParamEditTexts.isNotEmpty()) {
            c.extra = extraParamEditTexts.mapValues { it.value.text?.toString()?.trim() ?: "" }
            // Sync group fields from extra so upload has correct group1–4
            c.group1 = c.extra["Group 1"] ?: c.extra["group1"] ?: c.group1
            c.group2 = c.extra["Group 2"] ?: c.extra["group2"] ?: c.group2
            c.group3 = c.extra["Group 3"] ?: c.extra["group3"] ?: c.group3
            c.group4 = c.extra["Group 4"] ?: c.extra["group4"] ?: c.group4
        }

        // Busy Lock — force off when a repeater/split offset is present (radio rule)
        val hasOffset = duplexStr == "+" || duplexStr == "-" ||
                        duplexStr.equals("split", ignoreCase = true)
        c.busyLock = if (hasOffset) false else binding.switchBusyLock.isChecked

        EepromParser.writeChannel(eep, c)

        // Clone mode: apply this channel edit to the raw EEPROM so upload_mmap and Save EEPROM dump stay in sync
        val radio = EepromHolder.selectedRadio
        if (eep != null && eep.isNotEmpty() && radio != null) {
            lifecycleScope.launch {
                try {
                    val isClone = withContext(Dispatchers.IO) { ChirpBridge.isCloneModeRadio(radio) }
                    if (isClone) {
                        val b64 = Base64.encodeToString(eep, Base64.NO_WRAP)
                        val newEep = ChirpBridge.applyChannelToMmap(radio, b64, c)
                        EepromHolder.eeprom = newEep
                    } else {
                        EepromHolder.eeprom = eep
                    }
                } catch (_: Throwable) {
                    EepromHolder.eeprom = eep
                }
                setResult(RESULT_OK)
                finish()
            }
        } else {
            EepromHolder.eeprom = eep
            setResult(RESULT_OK)
            finish()
        }
    }

    companion object {
        private const val EXTRA_CHANNEL_NUMBER = "channel_number"

        fun intent(context: Context, channelNumber: Int, eeprom: ByteArray): Intent {
            EepromHolder.eeprom = eeprom
            return Intent(context, ChannelEditActivity::class.java).putExtra(EXTRA_CHANNEL_NUMBER, channelNumber)
        }
    }
}
