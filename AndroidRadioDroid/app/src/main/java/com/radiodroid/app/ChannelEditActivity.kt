package com.radiodroid.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import com.radiodroid.app.radio.EepromConstants
import com.radiodroid.app.radio.EepromParser
import com.radiodroid.app.radio.Protocol
import com.radiodroid.app.radio.Channel

class ChannelEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChannelEditBinding
    private var channelNumber: Int = 1
    private var channel: Channel? = null
    private var eeprom: ByteArray? = null

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

        // ── Existing spinners ──────────────────────────────────────────────────
        binding.spinnerPower.adapter    = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, EepromConstants.POWERLEVEL_LIST)
        binding.spinnerMode.adapter     = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, EepromConstants.MODULATION_LIST)
        binding.spinnerBandwidth.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, EepromConstants.BANDWIDTH_LIST)
        binding.spinnerDuplex.adapter   = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, EepromConstants.DUPLEX_LABELS)

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
                binding.spinnerMode.setSelection(1)
                binding.spinnerBandwidth.setSelection(0)
            } else {
                binding.editFreqRx.setText("%.4f".format(c.freqRxHz / 1_000_000.0))
                binding.editOffset.setText(when (c.duplex) {
                    "+", "-" -> (c.offsetHz / 1000).toString()
                    "split"  -> (c.freqTxHz / 1_000_000.0).toString()
                    else     -> ""
                })
                binding.editName.setText(c.name)
                binding.spinnerDuplex.setSelection(
                    EepromConstants.DUPLEX_VALUES.indexOf(c.duplex).coerceAtLeast(0))
                val powerIdx = EepromConstants.POWERLEVEL_LIST.indexOf(c.power).coerceAtLeast(0)
                binding.spinnerPower.setSelection(powerIdx)
                val modeIdx = EepromConstants.MODULATION_LIST.indexOf(c.mode).coerceAtLeast(0)
                binding.spinnerMode.setSelection(modeIdx)
                binding.spinnerBandwidth.setSelection(if (c.bandwidth == "Narrow") 1 else 0)
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

        // ── Power cap advisory ─────────────────────────────────────────────
        // Show a non-blocking warning when the selected power exceeds the radio's
        // VHF/UHF cap (Tune Settings at 0x1DFB). The spinner is not restricted —
        // the radio silently clamps at TX time but the stored byte is unchanged.
        binding.spinnerPower.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) = updatePowerCapWarning(position)
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        // Evaluate once now for the initially-selected power value
        channel?.let { c ->
            if (!c.empty) {
                val powerIdx = EepromConstants.POWERLEVEL_LIST.indexOf(c.power).coerceAtLeast(0)
                updatePowerCapWarning(powerIdx)
            }
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
        val hasOffset = binding.spinnerDuplex.selectedItemPosition > 0
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

        val freqRxStr  = binding.editFreqRx.text?.toString()?.trim()
        val duplexStr  = EepromConstants.DUPLEX_VALUES.getOrNull(binding.spinnerDuplex.selectedItemPosition) ?: ""
        val nameStr    = (binding.editName.text?.toString() ?: "").take(12)
        val powerStr   = EepromConstants.POWERLEVEL_LIST.getOrNull(binding.spinnerPower.selectedItemPosition) ?: "1"
        val modeStr    = EepromConstants.MODULATION_LIST.getOrNull(binding.spinnerMode.selectedItemPosition) ?: "FM"
        val bandwidthStr = EepromConstants.BANDWIDTH_LIST.getOrNull(binding.spinnerBandwidth.selectedItemPosition) ?: "Wide"

        val empty = freqRxStr.isNullOrBlank()
        if (empty) {
            c.empty      = true
            c.freqRxHz   = 0
            c.freqTxHz   = 0
            c.offsetHz   = 0
            c.duplex     = ""
            c.name       = ""
            c.power      = "1"
            c.mode       = "FM"
            c.bandwidth  = "Wide"
        } else {
            val freqRxMhz = freqRxStr.toDoubleOrNull() ?: 0.0
            val freqRxHz  = (freqRxMhz * 1_000_000).toLong()
            // Validate that the frequency falls inside a known VHF or UHF range.
            // RadioDroid does not yet download band plans from the radio.
            val freqInRange =
                freqRxHz in EepromConstants.VHF_LOW..EepromConstants.VHF_HIGH ||
                freqRxHz in EepromConstants.UHF_LOW..EepromConstants.UHF_HIGH
            if (!freqInRange) {
                Toast.makeText(this, "Frequency out of range", Toast.LENGTH_SHORT).show()
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
            c.empty     = false
            c.freqRxHz  = freqRxHz
            c.freqTxHz  = txHz
            c.offsetHz  = kotlin.math.abs(freqRxHz - txHz)
            c.duplex    = duplexStr
            c.name      = nameStr
            c.power     = powerStr
            c.mode      = modeStr
            c.bandwidth = bandwidthStr
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

        // Groups — always saved
        c.group1 = EepromConstants.GROUPS_LIST.getOrNull(binding.spinnerGroup1.selectedItemPosition) ?: "None"
        c.group2 = EepromConstants.GROUPS_LIST.getOrNull(binding.spinnerGroup2.selectedItemPosition) ?: "None"
        c.group3 = EepromConstants.GROUPS_LIST.getOrNull(binding.spinnerGroup3.selectedItemPosition) ?: "None"
        c.group4 = EepromConstants.GROUPS_LIST.getOrNull(binding.spinnerGroup4.selectedItemPosition) ?: "None"

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
