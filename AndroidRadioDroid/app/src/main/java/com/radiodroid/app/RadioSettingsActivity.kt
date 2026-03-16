package com.radiodroid.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.radiodroid.app.EepromHolder
import com.radiodroid.app.databinding.ActivityRadioSettingsBinding
import com.radiodroid.app.databinding.ItemRadioSettingBinding
import com.radiodroid.app.databinding.ItemRadioSettingGroupHeaderBinding
import com.radiodroid.app.model.RadioInfo
import com.radiodroid.app.bridge.ChirpBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Dynamic radio settings screen built from the CHIRP driver's get_settings() tree.
 * For clone-mode radios with an in-memory EEPROM dump, uses that dump (no connection).
 * Otherwise requires the radio to be connected.
 */
class RadioSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRadioSettingsBinding
    private var radio: RadioInfo? = null
    private var port: String? = null
    /** When true, we work off EepromHolder.eeprom; Save updates in-memory dump only (no sync_out). */
    private var useMmap: Boolean = false
    private var settingsList: List<JSONObject> = emptyList()
    /** Path -> binding for each setting row (used on save). */
    private val pathToBinding = mutableMapOf<String, ItemRadioSettingBinding>()
    /** Which group paths are expanded. Default: all expanded. */
    private val expandedGroupPaths = mutableSetOf<String>()
    /** (view, groupPath, isHeader) for expand/collapse visibility. */
    private val rowViews = mutableListOf<Triple<View, String, Boolean>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityRadioSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(
                left = systemBars.left,
                top = systemBars.top,
                right = systemBars.right,
                bottom = systemBars.bottom
            )
            insets
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        radio = intent.getStringExtra(EXTRA_VENDOR)?.let { vendor ->
            intent.getStringExtra(EXTRA_MODEL)?.let { model ->
                val baud = intent.getIntExtra(EXTRA_BAUD_RATE, 9600)
                RadioInfo(vendor = vendor, model = model, baudRate = baud)
            }
        }
        port = intent.getStringExtra(EXTRA_PORT)

        if (radio == null) {
            Toast.makeText(this, R.string.radio_settings_error, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        // Port required only for non-clone or when no in-memory EEPROM
        if (port.isNullOrBlank()) {
            val eep = EepromHolder.eeprom
            if (eep == null || eep.isEmpty()) {
                Toast.makeText(this, R.string.radio_settings_error, Toast.LENGTH_SHORT).show()
                finish()
                return
            }
        }

        binding.btnCancel.setOnClickListener { finish() }
        binding.btnSave.setOnClickListener { saveAndFinish() }
        binding.btnSave.isEnabled = false

        loadSettings()
    }

    private fun loadSettings() {
        binding.progressBar.visibility = View.VISIBLE
        binding.scrollContent.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val r = radio!!
                val eep = EepromHolder.eeprom
                useMmap = eep != null && eep.isNotEmpty() && ChirpBridge.isCloneModeRadio(r)
                Log.w(TAG, "loadSettings: useMmap=$useMmap eepromSize=${eep?.size ?: 0}")
                val json = withContext(Dispatchers.IO) {
                    if (useMmap && eep != null && eep.isNotEmpty()) {
                        val b64 = Base64.encodeToString(eep, Base64.NO_WRAP)
                        ChirpBridge.getRadioSettingsFromMmap(r, b64)
                    } else {
                        val p = port
                        if (!p.isNullOrBlank())
                            ChirpBridge.getRadioSettings(r, p)
                        else
                            throw IllegalStateException("No in-memory EEPROM and no connection")
                    }
                }
                val root = JSONObject(json)
                val arr = root.optJSONArray("settings") ?: JSONArray()
                settingsList = (0 until arr.length()).map { arr.getJSONObject(it) }
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.scrollContent.visibility = View.VISIBLE
                    buildForm()
                    binding.btnSave.isEnabled = true
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(
                        this@RadioSettingsActivity,
                        getString(R.string.radio_settings_error) + " " + e.message,
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            }
        }
    }

    /**
     * Build a display list that respects the driver's tree: emit group headers for each path
     * prefix in order, then the setting. Paths are like "Basic Settings/squelch" or "root/Band Plan/Plan 1/start".
     */
    private fun buildDisplayList(): List<DisplayRow> {
        val result = mutableListOf<DisplayRow>()
        val emittedGroups = mutableSetOf<String>()
        for (i in settingsList.indices) {
            val obj = settingsList[i]
            var path = obj.optString("path", "").trim()
            if (path.startsWith("root/")) path = path.removePrefix("root/")
            val parts = path.split("/").filter { it.isNotBlank() }
            if (parts.isEmpty()) continue
            // Emit group headers for each prefix (parent groups first)
            for (depth in 0 until parts.size - 1) {
                val groupPath = parts.subList(0, depth + 1).joinToString("/")
                if (groupPath !in emittedGroups) {
                    emittedGroups.add(groupPath)
                    expandedGroupPaths.add(groupPath)
                    result.add(DisplayRow.GroupHeader(groupPath, parts[depth]))
                }
            }
            result.add(DisplayRow.Setting(obj))
        }
        return result
    }

    private fun buildForm() {
        val displayList = buildDisplayList()
        binding.containerSettings.removeAllViews()
        pathToBinding.clear()
        rowViews.clear()
        for (row in displayList) {
            when (row) {
                is DisplayRow.GroupHeader -> {
                    val headerBinding = ItemRadioSettingGroupHeaderBinding.inflate(layoutInflater, binding.containerSettings, false)
                    val depth = row.groupPath.count { it == '/' }
                    (headerBinding.root.layoutParams as? android.widget.LinearLayout.LayoutParams)?.marginStart = (depth * 16 * resources.displayMetrics.density).toInt()
                    headerBinding.labelGroup.text = row.displayName
                    val groupPath = row.groupPath
                    val isExpanded = groupPath in expandedGroupPaths
                    headerBinding.iconExpand.setImageResource(
                        if (isExpanded) R.drawable.ic_expand_more else R.drawable.ic_chevron_right
                    )
                    headerBinding.rowGroupHeader.setOnClickListener {
                        if (groupPath in expandedGroupPaths) expandedGroupPaths.remove(groupPath)
                        else expandedGroupPaths.add(groupPath)
                        updateGroupVisibility()
                    }
                    binding.containerSettings.addView(headerBinding.root)
                    rowViews.add(Triple(headerBinding.root, groupPath, true))
                }
                is DisplayRow.Setting -> {
                    val obj = row.entry
                    val path = obj.optString("path", "")
                    val name = obj.optString("name", path)
                    val type = obj.optString("type", "string")
                    val readOnly = obj.optBoolean("readOnly", false)
                    val parts = path.trim().removePrefix("root/").split("/").filter { it.isNotBlank() }
                    val parentGroupPath = if (parts.size > 1) parts.subList(0, parts.size - 1).joinToString("/") else ""
                    val depth = parentGroupPath.count { it == '/' } + (if (parentGroupPath.isNotEmpty()) 1 else 0)

                    val rowBinding = ItemRadioSettingBinding.inflate(layoutInflater, binding.containerSettings, false)
                    (rowBinding.root.layoutParams as? android.widget.LinearLayout.LayoutParams)?.marginStart = (depth * 16 * resources.displayMetrics.density).toInt()
                    rowBinding.labelSetting.text = name
                    rowBinding.wrapEdit.visibility = View.GONE
                    rowBinding.wrapSwitch.visibility = View.GONE
                    rowBinding.spinnerValue.visibility = View.GONE
                    when (type) {
                        "int" -> {
                            rowBinding.wrapEdit.visibility = View.VISIBLE
                            rowBinding.editValue.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                            rowBinding.editValue.setText(obj.optInt("value", 0).toString())
                            rowBinding.editValue.isEnabled = !readOnly
                        }
                        "float" -> {
                            rowBinding.wrapEdit.visibility = View.VISIBLE
                            rowBinding.editValue.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                            rowBinding.editValue.setText(obj.optDouble("value", 0.0).toString())
                            rowBinding.editValue.isEnabled = !readOnly
                        }
                        "bool" -> {
                            rowBinding.wrapSwitch.visibility = View.VISIBLE
                            rowBinding.switchValue.isChecked = obj.optBoolean("value", false)
                            rowBinding.switchValue.isEnabled = !readOnly
                        }
                        "list" -> {
                            rowBinding.spinnerValue.visibility = View.VISIBLE
                            val options = mutableListOf<String>()
                            val optArr = obj.optJSONArray("options")
                            if (optArr != null) {
                                for (j in 0 until optArr.length()) options.add(optArr.optString(j, ""))
                            }
                            val current = obj.optString("value", "")
                            rowBinding.spinnerValue.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
                            val idx = options.indexOf(current).coerceAtLeast(0)
                            rowBinding.spinnerValue.setSelection(idx)
                            rowBinding.spinnerValue.isEnabled = !readOnly
                        }
                        else -> {
                            rowBinding.wrapEdit.visibility = View.VISIBLE
                            rowBinding.editValue.inputType = android.text.InputType.TYPE_CLASS_TEXT
                            rowBinding.editValue.setText(obj.optString("value", ""))
                            rowBinding.editValue.isEnabled = !readOnly
                        }
                    }
                    binding.containerSettings.addView(rowBinding.root)
                    pathToBinding[path] = rowBinding
                    rowViews.add(Triple(rowBinding.root, parentGroupPath, false))
                }
            }
        }
        updateGroupVisibility()
    }

    private fun updateGroupVisibility() {
        for ((view, groupPath, isHeader) in rowViews) {
            if (isHeader) {
                val isExpanded = groupPath in expandedGroupPaths
                view.findViewById<android.widget.ImageView>(R.id.iconExpand)?.setImageResource(
                    if (isExpanded) R.drawable.ic_expand_more else R.drawable.ic_chevron_right
                )
                view.visibility = View.VISIBLE
            } else {
                // Setting row: visible only if every ancestor group is expanded
                var visible = true
                var current: String? = groupPath
                while (!current.isNullOrEmpty()) {
                    if (current !in expandedGroupPaths) {
                        visible = false
                        break
                    }
                    current = current.substringBeforeLast("/").takeIf { it != current }
                }
                view.visibility = if (visible) View.VISIBLE else View.GONE
            }
        }
    }

    private sealed class DisplayRow {
        data class GroupHeader(val groupPath: String, val displayName: String) : DisplayRow()
        data class Setting(val entry: JSONObject) : DisplayRow()
    }

    private fun saveAndFinish() {
        val r = radio ?: return
        // Port required only for non-clone (live) save; clone mode uses in-memory EEPROM
        if (!useMmap && port.isNullOrBlank()) return
        val arr = JSONArray()
        for (i in settingsList.indices) {
            val obj = settingsList[i]
            val path = obj.optString("path", "")
            val type = obj.optString("type", "string")
            val readOnly = obj.optBoolean("readOnly", false)
            if (readOnly) {
                arr.put(JSONObject().apply {
                    put("path", path)
                    put("value", obj.opt("value"))
                })
                continue
            }
            val rowBinding = pathToBinding[path] ?: continue
            val value: Any = when (type) {
                "int" -> rowBinding.editValue.text?.toString()?.toIntOrNull() ?: 0
                "float" -> rowBinding.editValue.text?.toString()?.toDoubleOrNull() ?: 0.0
                "bool" -> rowBinding.switchValue.isChecked
                "list" -> {
                    val options = (rowBinding.spinnerValue.adapter as? ArrayAdapter<*>)?.let { a ->
                        (0 until a.count).map { a.getItem(it).toString() }
                    } ?: emptyList()
                    options.getOrNull(rowBinding.spinnerValue.selectedItemPosition) ?: ""
                }
                else -> rowBinding.editValue.text?.toString() ?: ""
            }
            arr.put(JSONObject().apply {
                put("path", path)
                put("value", value)
            })
        }
        val json = JSONObject().apply { put("settings", arr) }.toString()
        binding.btnSave.isEnabled = false
        lifecycleScope.launch {
            try {
                if (useMmap) {
                    // Clone mode: only update in-memory EEPROM; save to radio is done from main screen only
                    val eep = EepromHolder.eeprom ?: throw IllegalStateException("No EEPROM in memory")
                    Log.w(TAG, "saveAndFinish: useMmap branch, eepromSize=${eep.size} jsonLen=${json.length}")
                    val b64 = Base64.encodeToString(eep, Base64.NO_WRAP)
                    val response = withContext(Dispatchers.IO) {
                        ChirpBridge.setRadioSettingsToMmap(r, b64, json)
                    }
                    val newB64: String
                    val appliedCount: Int
                    val lcdApplied: Int?
                    val structLcd: Int?
                    val bufLcd: Int?
                    if (response.trimStart().startsWith("{")) {
                        val obj = org.json.JSONObject(response)
                        newB64 = obj.getString("eepromBase64")
                        appliedCount = obj.optInt("appliedCount", -1)
                        lcdApplied = if (obj.has("lcdBrightnessApplied")) obj.getInt("lcdBrightnessApplied") else null
                        structLcd = if (obj.has("structLcdBrightness")) obj.getInt("structLcdBrightness") else null
                        bufLcd = if (obj.has("bufferLcdBrightness")) obj.getInt("bufferLcdBrightness") else null
                    } else {
                        newB64 = response
                        appliedCount = -1
                        lcdApplied = null
                        structLcd = null
                        bufLcd = null
                    }
                    val newEeprom = Base64.decode(newB64, Base64.NO_WRAP)
                    EepromHolder.eeprom = newEeprom
                    Log.w(TAG, "saveAndFinish: newEepromSize=${newEeprom.size} sameAsBefore=${newEeprom.contentEquals(eep)} appliedCount=$appliedCount lcdApplied=$lcdApplied structLcd=$structLcd bufLcd=$bufLcd")
                    runOnUiThread {
                        Toast.makeText(this@RadioSettingsActivity, R.string.radio_settings_saved, Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK)
                        finish()
                    }
                } else {
                    val p = port!!  // guarded by saveAndFinish(): port non-blank when !useMmap
                    withContext(Dispatchers.IO) {
                        ChirpBridge.setRadioSettings(r, p, json)
                    }
                    runOnUiThread {
                        Toast.makeText(this@RadioSettingsActivity, R.string.radio_settings_saved, Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK)
                        finish()
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    binding.btnSave.isEnabled = true
                    Toast.makeText(this@RadioSettingsActivity, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    companion object {
        private const val TAG = "RadioSettings"
        private const val EXTRA_VENDOR = "vendor"
        private const val EXTRA_MODEL = "model"
        private const val EXTRA_BAUD_RATE = "baud_rate"
        private const val EXTRA_PORT = "port"

        fun intent(context: Context, radio: RadioInfo, port: String): Intent =
            Intent(context, RadioSettingsActivity::class.java).apply {
                putExtra(EXTRA_VENDOR, radio.vendor)
                putExtra(EXTRA_MODEL, radio.model)
                putExtra(EXTRA_BAUD_RATE, radio.baudRate)
                putExtra(EXTRA_PORT, port)
            }
    }
}
