package com.radiodroid.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.radiodroid.app.databinding.ActivityChannelEditBinding
import com.radiodroid.app.model.RadioFeatures
import com.radiodroid.app.radio.EepromConstants
import com.radiodroid.app.radio.EepromParser
import com.radiodroid.app.radio.Protocol
import com.radiodroid.app.radio.Channel

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

        // Mode: whatever the driver declares as valid
        binding.spinnerMode.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, modeList
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

        // Groups are a nicFW-specific concept — not part of the CHIRP data model
        binding.textLabelGroups.visibility = View.GONE
        binding.rowGroups1.visibility      = View.GONE
        binding.rowGroups2.visibility      = View.GONE

        // Enforce driver name-length limit (0 = driver didn't declare one → cap at 16)
        val maxNameLen = if (features.validNameLength > 0) features.validNameLength else 16
        binding.editName.filters = arrayOf(InputFilter.LengthFilter(maxNameLen))

        // ── Flat tone spinners (one per side, no adapter swapping) ─────────────
        val toneAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, EepromConstants.TONE_LABELS)
        binding.spinnerTxTone.adapter = toneAdapter
        // RX gets its own adapter instance so the two spinners are independent
        val toneAdapterRx = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, EepromConstants.TONE_LABELS)
        binding.spinnerRxTone.adapter = toneAdapterRx

        // ── Group spinners (Group 1–4, each: None / A – Label … O – Label) ──────
        // Build spinner items that show the live label alongside each letter,
        // e.g. "A – All", "B – MURS", "G – GMRS". Falls back to just the letter
        // when the label is blank or the EEPROM hasn't been loaded yet.
        val parsedLabels = EepromHolder.groupLabels
        val groupSpinnerItems: List<String> = buildList {
            add("None")
            EepromConstants.GROUP_LETTERS.forEachIndexed { i, letter ->
                val label = parsedLabels.getOrNull(i)?.trim() ?: ""
                add(if (label.isEmpty()) letter else "$letter – $label")
            }
        }
        val groupAdapter = { ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, groupSpinnerItems) }
        binding.spinnerGroup1.adapter = groupAdapter()
        binding.spinnerGroup2.adapter = groupAdapter()
        binding.spinnerGroup3.adapter = groupAdapter()
        binding.spinnerGroup4.adapter = groupAdapter()

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

            // Group spinners
            binding.spinnerGroup1.setSelection(EepromConstants.GROUPS_LIST.indexOf(c.group1).coerceAtLeast(0))
            binding.spinnerGroup2.setSelection(EepromConstants.GROUPS_LIST.indexOf(c.group2).coerceAtLeast(0))
            binding.spinnerGroup3.setSelection(EepromConstants.GROUPS_LIST.indexOf(c.group3).coerceAtLeast(0))
            binding.spinnerGroup4.setSelection(EepromConstants.GROUPS_LIST.indexOf(c.group4).coerceAtLeast(0))

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
            binding.helpGroups    to "groups",
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
        val powerStr     = powerLevels.getOrNull(binding.spinnerPower.selectedItemPosition) ?: ""
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

        // Groups — nicFW-specific, not part of the CHIRP data model; section is hidden

        // Busy Lock — force off when a repeater/split offset is present (radio rule)
        val hasOffset = duplexStr == "+" || duplexStr == "-" ||
                        duplexStr.equals("split", ignoreCase = true)
        c.busyLock = if (hasOffset) false else binding.switchBusyLock.isChecked

        EepromParser.writeChannel(eep, c)
        EepromHolder.eeprom = eep
        setResult(RESULT_OK)
        finish()
    }

    companion object {
        private const val EXTRA_CHANNEL_NUMBER = "channel_number"

        fun intent(context: Context, channelNumber: Int, eeprom: ByteArray): Intent {
            EepromHolder.eeprom = eeprom
            return Intent(context, ChannelEditActivity::class.java).putExtra(EXTRA_CHANNEL_NUMBER, channelNumber)
        }
    }
}
