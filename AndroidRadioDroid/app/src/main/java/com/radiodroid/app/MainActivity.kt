package com.radiodroid.app

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.radiodroid.app.ui.applyEdgeToEdgeInsets
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.radiodroid.app.bluetooth.BleManager
import com.radiodroid.app.bluetooth.BtSerialManager
import android.util.Base64
import com.radiodroid.app.bridge.BleBridge
import com.radiodroid.app.bridge.ChirpBridge
import com.radiodroid.app.bridge.DownloadResult
import com.radiodroid.app.bridge.UsbSerialBridge
import com.radiodroid.app.databinding.ActivityMainBinding
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import com.radiodroid.app.model.ChannelExtraSetting
import com.radiodroid.app.model.RadioInfo
import com.radiodroid.app.radio.Channel
import com.radiodroid.app.radio.ChirpCsvExporter
import com.radiodroid.app.radio.ChirpCsvImporter
import com.radiodroid.app.radio.EepromConstants
import com.radiodroid.app.radio.EepromParser
import com.radiodroid.app.radio.Protocol
import com.radiodroid.app.radio.RadioStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Classic Bluetooth SPP (fallback for older radios / already-paired devices)
    private lateinit var btManager: BtSerialManager

    // BLE — primary wireless connection method
    private lateinit var bleManager: BleManager

    // USB OTG serial bridge
    private lateinit var usbBridge: UsbSerialBridge

    // BLE → LocalSocket relay (created after BLE connects, before Python talks to it)
    private var bleBridge: BleBridge? = null

    /** Whichever BLE/SPP stream is active. Null when disconnected. */
    private var activeStream: RadioStream? = null

    /** Rotates status text during radio download/upload (CHIRP bridge has no byte progress). */
    private var transferStatusJob: Job? = null

    private val downloadStatusMessages: IntArray by lazy {
        intArrayOf(
            R.string.radio_transfer_download_1,
            R.string.radio_transfer_download_2,
            R.string.radio_transfer_download_3,
        )
    }
    private val uploadStatusMessages: IntArray by lazy {
        intArrayOf(
            R.string.radio_transfer_upload_1,
            R.string.radio_transfer_upload_2,
            R.string.radio_transfer_upload_3,
        )
    }
    private val exportBackupStatusMessages: IntArray by lazy {
        intArrayOf(
            R.string.export_backup_1,
            R.string.export_backup_2,
            R.string.export_backup_3,
        )
    }
    private var activeDeviceName: String? = null

    /** Currently selected radio model (set by RadioSelectActivity or restored from prefs). */
    private var selectedRadio: RadioInfo? = null

    /** Serial port string for the active connection (`android://…` or `ble://rdble_<uuid>`). */
    private var activePort: String? = null

    /** Pending USB permission BroadcastReceiver — unregistered after one use. */
    private var usbPermissionReceiver: BroadcastReceiver? = null

    private var eeprom: ByteArray? = null
    private var channelList: List<Channel> = emptyList()

    /** Current search query (empty = no filter). */
    private var searchQuery: String = ""

    /**
     * Working copy of the channel list used by [ItemTouchHelper] during drag-to-reorder.
     * Kept in sync with [channelList] outside of drag operations.
     */
    private var dragWorkList: MutableList<Channel> = mutableListOf()

    /** ItemTouchHelper that powers drag-to-reorder of channel cards. */
    private lateinit var touchHelper: ItemTouchHelper

    // ─── Adapter ──────────────────────────────────────────────────────────────

    private val adapter = ChannelAdapter(
        onChannelClick = { channel ->
            if (EepromHolder.channels.isNotEmpty()) {
                // Pass a zero-length sentinel byte array; ChannelEditActivity uses
                // EepromHolder.channels (populated by ChirpBridge) not raw EEPROM bytes.
                startActivity(ChannelEditActivity.intent(this, channel.number, ByteArray(0)))
            }
        },
        onLongClick = { channel -> enterSelectionMode(channel) },
        onSelectionChanged = { count -> updateSelectionBar(count) },
        onDragStart  = { vh -> touchHelper.startDrag(vh) }
    )

    // ─── BLE scan state ───────────────────────────────────────────────────────
    private var scanDialog: AlertDialog? = null
    private val scanDevices = mutableListOf<BluetoothDevice>()
    private val scanDeviceRssi = mutableListOf<Int>()
    private lateinit var scanListAdapter: ArrayAdapter<String>
    private val scanTimeoutHandler = Handler(Looper.getMainLooper())
    private val stopScanRunnable = Runnable { stopBleScan() }

    // ─── Permission launcher ──────────────────────────────────────────────────
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.any { !it }) {
            Toast.makeText(this, "Bluetooth permission required", Toast.LENGTH_SHORT).show()
        }
    }

    // ─── Radio select launcher ────────────────────────────────────────────────
    /**
     * Receives the radio model chosen in [RadioSelectActivity].
     * Extracts vendor / model / baudRate and stores in [selectedRadio], then
     * updates the connection status bar so the user can see the active selection.
     */
    private val radioSelectLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data     = result.data ?: return@registerForActivityResult
        val vendor   = data.getStringExtra(RadioSelectActivity.EXTRA_VENDOR)    ?: return@registerForActivityResult
        val model    = data.getStringExtra(RadioSelectActivity.EXTRA_MODEL)     ?: return@registerForActivityResult
        val baudRate = data.getIntExtra(RadioSelectActivity.EXTRA_BAUD_RATE, 9600)
        selectedRadio = RadioInfo(vendor, model, baudRate)
        EepromHolder.selectedRadio = selectedRadio
        updateConnectionUi()
        invalidateOptionsMenu()
        Toast.makeText(this, "Radio: $vendor $model", Toast.LENGTH_SHORT).show()
        // Fetch driver capabilities in the background so ChannelEditActivity
        // can adapt its UI to exactly what this radio supports.
        lifecycleScope.launch {
            try {
                EepromHolder.radioFeatures = ChirpBridge.getRadioFeatures(selectedRadio!!)
            } catch (_: Throwable) {
                // Catch Throwable (not just Exception) — Chaquopy may surface
                // driver import failures as java.lang.Error subclasses.
                EepromHolder.radioFeatures = com.radiodroid.app.model.RadioFeatures.DEFAULT
            }
        }
    }

    // ─── Customize main screen launcher ───────────────────────────────────────
    /** When user saves slot choices, refresh the channel list so the two slots update. */
    private val customizeMainScreenLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) adapter.notifyDataSetChanged()
    }

    // ─── CHIRP CSV import ─────────────────────────────────────────────────────
    /**
     * Launches the system file picker for a CSV file, reads the content on the IO
     * dispatcher, then delegates to [processChirpCsv] for validation and launch.
     */
    private val csvPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val csvText = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } ?: ""
            }
            if (csvText.isBlank()) {
                Toast.makeText(this@MainActivity, "Could not read file", Toast.LENGTH_SHORT).show()
                return@launch
            }
            processChirpCsv(csvText)
        }
    }

    // ─── Radio backup import / export ─────────────────────────────────────────
    /**
     * Launches the system file picker for a RadioDroid backup (.json) or legacy
     * raw EEPROM dump (.img/.bin). File content is auto-detected on read.
     */
    private val backupPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
            if (bytes == null || bytes.isEmpty()) {
                Toast.makeText(this@MainActivity, "Could not read file", Toast.LENGTH_SHORT).show()
                return@launch
            }
            importRadioBackup(bytes)
        }
    }

    /**
     * Imports a RadioDroid backup file or a legacy raw EEPROM dump.
     *
     * Detection: if the file starts with '{' it is treated as a JSON backup;
     * otherwise it is treated as a raw EEPROM binary (legacy .img/.bin).
     *
     * JSON backup handling:
     * - Clone radios with an "eeprom_base64" key: the image is loaded via
     *   [ChirpBridge.loadFromEeprom]. If the file also contains a **settings** array,
     *   those path+value pairs are applied into the mmap via
     *   [ChirpBridge.setRadioSettingsToMmap] so the restored image matches the JSON
     *   (export always writes both; previously settings in the file were ignored).
     * - Non-clone radios (or clone backups without eeprom_base64): channels are parsed
     *   directly from the "channels" array; settings JSON is stored in
     *   [EepromHolder.pendingSettingsJson] and applied on next Save to Radio.
     */
    /**
     * Overlays per-slot `extra` objects from a backup JSON `channels` array onto
     * [EepromHolder.channels] (used after [ChirpBridge.loadFromEeprom] for clone backups).
     */
    private fun mergeBackupChannelExtrasFromJson(json: org.json.JSONObject) {
        val arr = json.optJSONArray("channels") ?: return
        val list = EepromHolder.channels
        for (i in 0 until minOf(arr.length(), list.size)) {
            val o = arr.optJSONObject(i) ?: continue
            val extraObj = o.optJSONObject("extra") ?: continue
            val merged = list[i].extra.toMutableMap()
            val keys = extraObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                merged[key] = extraObj.optString(key, "")
            }
            list[i].extra = merged
        }
    }

    private fun importRadioBackup(bytes: ByteArray) {
        val radio = selectedRadio ?: run {
            Toast.makeText(this, "Select a radio model first (⋮ → Select Radio Model)", Toast.LENGTH_LONG).show()
            return
        }
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = true
        binding.progressText.visibility = View.VISIBLE
        startTransferStatusHints(downloadStatusMessages)
        binding.btnLoad.isEnabled = false
        binding.btnSave.isEnabled = false
        lifecycleScope.launch {
            try {
                EepromHolder.selectedRadio = radio
                val isJson = bytes.isNotEmpty() && bytes[0] == '{'.code.toByte()
                if (isJson) {
                    val json = org.json.JSONObject(String(bytes, Charsets.UTF_8))
                    val eepromB64 = json.optString("eeprom_base64", "").ifBlank { null }
                    // Extract only path+value pairs from backup settings — schema always
                    // comes from the driver at load time, never from the backup file.
                    val settingsJsonStr: String? = json.optJSONObject("settings")
                        ?.optJSONArray("settings")
                        ?.let { arr ->
                            val minimal = org.json.JSONArray()
                            for (i in 0 until arr.length()) {
                                val item = arr.getJSONObject(i)
                                minimal.put(org.json.JSONObject().apply {
                                    put("path", item.optString("path"))
                                    put("value", item.opt("value"))
                                })
                            }
                            org.json.JSONObject().apply { put("settings", minimal) }.toString()
                        }

                    val isClone = ChirpBridge.isCloneModeRadio(radio)
                    if (eepromB64 != null && isClone) {
                        // Clone backup: start from file image, merge in JSON settings (if any),
                        // then decode channels from the final bytes.
                        var workingB64 = eepromB64
                        val settingsArr = try {
                            settingsJsonStr?.let { org.json.JSONObject(it).optJSONArray("settings") }
                        } catch (_: Exception) {
                            null
                        }
                        if (settingsArr != null && settingsArr.length() > 0 && settingsJsonStr != null) {
                            val mmapResponse = ChirpBridge.setRadioSettingsToMmap(
                                radio,
                                workingB64,
                                settingsJsonStr
                            )
                            workingB64 = parseSetMmapResponseEepromBase64(mmapResponse)
                        }
                        val eepBytes = Base64.decode(workingB64, Base64.NO_WRAP)
                        val result = ChirpBridge.loadFromEeprom(radio, workingB64)
                        EepromHolder.channels = result.channels.toMutableList()
                        // Backup JSON may carry Memory.extra (e.g. b_lock) that is missing or
                        // stale in eeprom_base64; merge so import matches the file and mmap sync
                        // can encode those values into the clone image.
                        mergeBackupChannelExtrasFromJson(json)
                        val syncedEep = withContext(Dispatchers.IO) {
                            ChirpBridge.syncCloneMmapToChannelList(
                                radio,
                                eepBytes,
                                EepromHolder.channels.toList()
                            )
                        }
                        val finalB64 =
                            Base64.encodeToString(syncedEep, Base64.NO_WRAP)
                        eeprom = syncedEep
                        EepromHolder.eeprom = syncedEep
                        EepromHolder.extraParamNames = EepromHolder.channels
                            .firstOrNull { it.extra.isNotEmpty() }
                            ?.extra?.keys?.toList() ?: emptyList()
                        EepromHolder.channelExtraSchema =
                            ChirpBridge.getChannelExtraSchema(radio, finalB64)
                        EepromHolder.pendingSettingsJson = null
                    } else {
                        // Non-clone or clone backup without EEPROM: load channels + settings.
                        // Capture any existing in-memory EEPROM *before* clearing it — clone-mode
                        // drivers (e.g. NICFW H3) need a loaded EEPROM to call get_memory() and
                        // return the channel-extra schema (field names, types, options for spinners).
                        // Using the previous EEPROM for schema introspection only is safe because
                        // the schema structure is static for a given driver/model.
                        val schemaEepromB64 = EepromHolder.eeprom?.takeIf { it.isNotEmpty() }
                            ?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
                        val arr = json.optJSONArray("channels")
                        val channels = if (arr != null) {
                            (0 until arr.length()).map { i ->
                                Channel.fromJson(i + 1, arr.getJSONObject(i))
                            }.toMutableList()
                        } else mutableListOf()
                        EepromHolder.eeprom = null
                        eeprom = ByteArray(0)
                        EepromHolder.channels = channels
                        EepromHolder.extraParamNames = channels
                            .firstOrNull { it.extra.isNotEmpty() }
                            ?.extra?.keys?.toList() ?: emptyList()
                        EepromHolder.channelExtraSchema = ChirpBridge.getChannelExtraSchema(radio, schemaEepromB64)
                        EepromHolder.pendingSettingsJson = settingsJsonStr
                    }
                } else {
                    // Legacy raw EEPROM binary
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    val result = ChirpBridge.loadFromEeprom(radio, b64)
                    eeprom = bytes
                    EepromHolder.eeprom = bytes
                    EepromHolder.channels = result.channels.toMutableList()
                    EepromHolder.extraParamNames = result.channels
                        .firstOrNull { it.extra.isNotEmpty() }
                        ?.extra?.keys?.toList() ?: emptyList()
                    EepromHolder.channelExtraSchema = ChirpBridge.getChannelExtraSchema(radio, b64)
                    EepromHolder.pendingSettingsJson = null
                }
                val channelCount = EepromHolder.channels.size
                refreshChannelList()
                runOnUiThread {
                    hideRadioTransferProgress()
                    updateConnectionUi()
                    invalidateOptionsMenu()
                    Toast.makeText(
                        this@MainActivity,
                        "Imported $channelCount channels.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    hideRadioTransferProgress()
                    Toast.makeText(this@MainActivity, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Keeps only `path` and `value` for each entry under `settings` so backup JSON stays
     * small (no per-setting `options` arrays from CHIRP list types). Matches the reduction
     * already applied in [importRadioBackup]; the driver supplies schema on load.
     */
    private fun minimizeSettingsForBackup(settingsRoot: org.json.JSONObject): org.json.JSONObject {
        val arr = settingsRoot.optJSONArray("settings") ?: return settingsRoot
        val minimal = org.json.JSONArray()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            minimal.put(org.json.JSONObject().apply {
                put("path", item.optString("path"))
                put("value", item.opt("value"))
            })
        }
        return org.json.JSONObject().apply { put("settings", minimal) }
    }

    /** Same shape as [RadioSettingsActivity] save path: JSON object or raw base64 string. */
    private fun parseSetMmapResponseEepromBase64(response: String): String =
        if (response.trimStart().startsWith("{")) {
            org.json.JSONObject(response).getString("eepromBase64")
        } else {
            response.trim()
        }

    /**
     * Filesystem-safe `Vendor_Model` prefix for export filenames (non-alphanumeric → `_`).
     */
    private fun sanitizedVendorModelFilePrefix(radio: RadioInfo?): String {
        fun segment(raw: String?, fallback: String): String =
            (raw ?: "")
                .replace(Regex("[^a-zA-Z0-9]"), "_")
                .trim('_')
                .ifBlank { fallback }
        val v = segment(radio?.vendor, "radio")
        val m = segment(radio?.model, "model")
        return "${v}_$m"
    }

    /**
     * For clone-mode radios, rewrites [EepromHolder.eeprom] by applying every slot in
     * [EepromHolder.channels] through the CHIRP driver so the raw image matches the list.
     * [EepromParser.writeChannel] only updates the list, not bytes — without this, backup
     * and upload could use a stale mmap after bulk edits or CSV import.
     */
    private suspend fun syncCloneEepromIfNeeded(radio: RadioInfo) {
        val eep = EepromHolder.eeprom ?: return
        if (eep.isEmpty()) return
        val synced = withContext(Dispatchers.IO) {
            ChirpBridge.syncCloneMmapToChannelList(radio, eep, EepromHolder.channels.toList())
        }
        EepromHolder.eeprom = synced
        eeprom = synced
    }

    /** Fire-and-forget [syncCloneEepromIfNeeded] after UI bulk channel operations. */
    private fun syncCloneMmapAfterChannelListEdits() {
        val radio = selectedRadio ?: EepromHolder.selectedRadio ?: return
        lifecycleScope.launch {
            try {
                syncCloneEepromIfNeeded(radio)
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "EEPROM image sync failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Exports a RadioDroid backup JSON containing channels, settings, and (for clone-mode
     * radios) the raw EEPROM bytes. The file can be re-imported on any device running
     * RadioDroid with the same radio model selected.
     *
     * Settings are written in **minimal** form (`path` + `value` only) so files are not
     * bloated by CHIRP UI metadata such as long `options` lists.
     */
    private fun exportRadioBackup() {
        val radio = selectedRadio ?: run {
            Toast.makeText(this, "Select a radio model first (⋮ → Select Radio Model)", Toast.LENGTH_LONG).show()
            return
        }
        if (EepromHolder.channels.isEmpty()) {
            Toast.makeText(this, "No channels loaded to export", Toast.LENGTH_SHORT).show()
            return
        }
        binding.progressBar.visibility = View.VISIBLE
        binding.progressText.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = true
        startTransferStatusHints(exportBackupStatusMessages)
        lifecycleScope.launch {
            try {
                syncCloneEepromIfNeeded(radio)

                val obj = org.json.JSONObject()
                obj.put("vendor", radio.vendor)
                obj.put("model", radio.model)

                // Channels
                val channelsArr = org.json.JSONArray()
                EepromHolder.channels.forEach { ch -> channelsArr.put(Channel.toBackupJson(ch)) }
                obj.put("channels", channelsArr)

                // Settings + optional EEPROM
                val eep = EepromHolder.eeprom
                val hasEeprom = eep != null && eep.isNotEmpty()
                if (hasEeprom) {
                    val b64 = Base64.encodeToString(eep, Base64.NO_WRAP)
                    obj.put("eeprom_base64", b64)
                    val settingsJson = withContext(Dispatchers.IO) {
                        ChirpBridge.getRadioSettingsFromMmap(radio, b64)
                    }
                    obj.put(
                        "settings",
                        minimizeSettingsForBackup(org.json.JSONObject(settingsJson))
                    )
                } else {
                    val pending = EepromHolder.pendingSettingsJson
                    if (pending != null) {
                        obj.put(
                            "settings",
                            minimizeSettingsForBackup(org.json.JSONObject(pending))
                        )
                    }
                }

                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val prefix = sanitizedVendorModelFilePrefix(radio)
                val fileName = "${prefix}_radiodroid_backup_$ts.json"
                val dir = getExternalFilesDir(null) ?: filesDir
                val file = File(dir, fileName)
                file.writeText(obj.toString(2))
                val uri = FileProvider.getUriForFile(this@MainActivity, "${packageName}.fileprovider", file)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "RadioDroid backup — $fileName")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Export Radio Backup"))
            } catch (t: Throwable) {
                Log.e(TAG, "exportRadioBackup", t)
                Toast.makeText(
                    this@MainActivity,
                    "Export failed: ${t.message ?: t.javaClass.simpleName}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                hideRadioTransferProgress()
            }
        }
    }

    /**
     * Exports the raw EEPROM bytes as a binary .img file (clone-mode radios only).
     * Useful for flashing with external tools or as a low-level backup.
     * For a portable backup that includes settings and works on all radios, use
     * [exportRadioBackup] instead.
     */
    private fun exportRawEeprom() {
        if (EepromHolder.eeprom == null || EepromHolder.eeprom!!.isEmpty()) {
            Toast.makeText(this, "No EEPROM to export", Toast.LENGTH_SHORT).show()
            return
        }
        binding.progressBar.visibility = View.VISIBLE
        binding.progressText.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = true
        startTransferStatusHints(exportBackupStatusMessages)
        lifecycleScope.launch {
            try {
                selectedRadio?.let { syncCloneEepromIfNeeded(it) }
                val eep = EepromHolder.eeprom
                if (eep == null || eep.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No EEPROM to export", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val prefix = sanitizedVendorModelFilePrefix(selectedRadio)
                val fileName = "${prefix}_eeprom_$ts.img"
                val dir = getExternalFilesDir(null) ?: filesDir
                val file = File(dir, fileName)
                withContext(Dispatchers.IO) { file.writeBytes(eep) }
                val uri = FileProvider.getUriForFile(this@MainActivity, "${packageName}.fileprovider", file)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "EEPROM dump — $fileName")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Export Raw EEPROM"))
                Toast.makeText(this@MainActivity, "Raw EEPROM exported as $fileName", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                hideRadioTransferProgress()
            }
        }
    }

    /**
     * Validates [csvText] with [ChirpCsvImporter] and, if valid, launches
     * [ChirpImportActivity] with the full preview and group-assignment UI.
     * Called by both the file-picker and clipboard import paths.
     */
    private fun processChirpCsv(csvText: String) {
        if (csvText.isBlank()) {
            Toast.makeText(this, "No CSV content to import", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                val commentsList = withContext(Dispatchers.IO) { extractComments(csvText) }
                val entries = ChirpCsvImporter.parse(csvText)
                if (entries.isEmpty()) {
                    Toast.makeText(
                        this@MainActivity,
                        "No valid CHIRP channels found",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                val intent = Intent(this@MainActivity, ChirpImportActivity::class.java).apply {
                    putExtra(ChirpImportActivity.EXTRA_CSV_TEXT, csvText)
                    putExtra(ChirpImportActivity.EXTRA_COMMENTS, ArrayList(commentsList))
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "Failed to parse CSV: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Extracts the optional "Comment" column from each data row in the CSV so the
     * import preview can display location descriptions (e.g. "Baltimore, Pigtown").
     * Returns a list parallel to the parsed [ChirpCsvImporter.ChirpEntry] list.
     */
    private fun extractComments(csvText: String): List<String> {
        val lines = csvText.lines()
        val headerIdx = lines.indexOfFirst { it.trimStart().startsWith("Location", ignoreCase = true) }
        if (headerIdx < 0) return emptyList()

        val headers = lines[headerIdx].split(",").map { it.trim().lowercase().trim('"') }
        val commentIdx = headers.indexOf("comment")
        if (commentIdx < 0) return emptyList()

        val result = mutableListOf<String>()
        for (i in (headerIdx + 1) until lines.size) {
            val row = lines[i].trim()
            if (row.isEmpty()) continue
            // Quick split — comments may be quoted
            val cols = buildList {
                var inQ = false; val cur = StringBuilder()
                for (c in row) when {
                    c == '"' -> inQ = !inQ
                    c == ',' && !inQ -> { add(cur.toString()); cur.clear() }
                    else -> cur.append(c)
                }
                add(cur.toString())
            }
            result += if (commentIdx in cols.indices) cols[commentIdx].trim().trim('"') else ""
        }
        return result
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.root.applyEdgeToEdgeInsets()

        setSupportActionBar(binding.toolbar)
        btManager  = BtSerialManager(this)
        bleManager = BleManager(this)
        bleManager.onLinkLost = linkLost@{
            // Runs on main thread (posted from GATT callback). Clear BLE bridge so the next
            // connect gets a fresh LocalServerSocket; avoids stale Python↔BLE relay after reboot.
            if (isFinishing) return@linkLost
            bleBridge?.close()
            bleBridge = null
            activePort = null
            activeStream = null
            updateConnectionUi()
            Toast.makeText(this, R.string.ble_connection_lost, Toast.LENGTH_LONG).show()
        }
        usbBridge  = UsbSerialBridge(this)

        // Restore last selected radio so the user doesn't have to re-pick on every launch
        val prefs = getSharedPreferences(RadioSelectActivity.PREFS_FILE, MODE_PRIVATE)
        val lastVendor = prefs.getString(RadioSelectActivity.PREF_VENDOR, null)
        val lastModel  = prefs.getString(RadioSelectActivity.PREF_MODEL,  null)
        if (lastVendor != null && lastModel != null) {
            val lastBaud = prefs.getInt(RadioSelectActivity.PREF_BAUD, 9600)
            selectedRadio = RadioInfo(lastVendor, lastModel, lastBaud)
            // Fetch driver features so the channel editor can show/hide and
            // populate spinners correctly on first launch (when radioSelectLauncher
            // never fires because RadioSelectActivity started us directly).
            lifecycleScope.launch {
                try {
                    EepromHolder.radioFeatures = ChirpBridge.getRadioFeatures(selectedRadio!!)
                } catch (_: Throwable) {
                    EepromHolder.radioFeatures = com.radiodroid.app.model.RadioFeatures.DEFAULT
                }
            }
        }

        binding.recyclerChannels.layoutManager = LinearLayoutManager(this)
        binding.recyclerChannels.adapter = adapter

        setupTouchHelper()
        setupSelectionBar()
        setupSearch()

        requestBluetoothPermissions()

        binding.btnConnect.setOnClickListener {
            if (isAnyConnected()) {
                disconnectAll()
                updateConnectionUi()
            } else {
                showConnectPicker()
            }
        }

        binding.btnLoad.setOnClickListener { loadFromRadio() }
        binding.btnSave.setOnClickListener { showSaveConfirm() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Drag-to-reorder setup
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupTouchHelper() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            // Drag is started only from the explicit drag handle touch
            override fun isLongPressDragEnabled() = false

            override fun onMove(
                rv: RecyclerView,
                from: RecyclerView.ViewHolder,
                to: RecyclerView.ViewHolder
            ): Boolean {
                val f = from.bindingAdapterPosition
                val t = to.bindingAdapterPosition
                if (f < 0 || t < 0 ||
                    f >= dragWorkList.size || t >= dragWorkList.size) return false
                Collections.swap(dragWorkList, f, t)
                adapter.notifyItemMoved(f, t)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                // No swipe actions
            }

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                // Finger lifted — commit the drag order to EEPROM
                applyDragReorder()
            }
        }

        touchHelper = ItemTouchHelper(callback)
        touchHelper.attachToRecyclerView(binding.recyclerChannels)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // In-app selection bar (replaces ActionMode/CAB)
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupSelectionBar() {
        binding.btnMoveUp.setOnClickListener        { moveSelectedUp() }
        binding.btnMoveDown.setOnClickListener      { moveSelectedDown() }
        binding.btnMoveTo.setOnClickListener        { moveSelectedToPosition() }
        binding.btnSetTxPower.setOnClickListener    { setTxPowerSelected() }
        binding.btnSetGroups.setOnClickListener     { setRadioSpecificExtraSelected() }
        binding.btnExportCsv.setOnClickListener     { exportSelectedChannels() }
        binding.btnClearSelected.setOnClickListener { clearSelectedChannels() }
        binding.btnSelectionDone.setOnClickListener {
            adapter.exitSelectionMode()
            // onSelectionChanged(0) will be called → hides the bar
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Search / filter
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupSearch() {
        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString() ?: ""
                syncSearchSelectAllVisibility()
                applyFilter()
            }
        })
        binding.btnClearSearch.setOnClickListener {
            searchQuery = ""
            binding.searchEditText.setText("")
            binding.searchBar.visibility = View.GONE
            syncSearchSelectAllVisibility()
            applyFilter()
        }
        binding.btnSelectAllMatches.setOnClickListener {
            if (channelList.isNotEmpty()) adapter.selectAllVisible()
        }
    }

    /** "Select all" is available whenever the channel search bar is open (full list or filtered). */
    private fun syncSearchSelectAllVisibility() {
        binding.btnSelectAllMatches.visibility =
            if (binding.searchBar.visibility == View.VISIBLE) View.VISIBLE else View.GONE
    }

    /** Toggles the search bar open/closed and resets the query when closing. */
    private fun toggleSearchBar() {
        if (binding.searchBar.visibility == View.VISIBLE) {
            searchQuery = ""
            binding.searchEditText.setText("")
            binding.searchBar.visibility = View.GONE
            syncSearchSelectAllVisibility()
            applyFilter()
        } else {
            binding.searchBar.visibility = View.VISIBLE
            syncSearchSelectAllVisibility()
            binding.searchEditText.requestFocus()
        }
    }

    /**
     * Submits the filtered (or full) channel list to the adapter based on [searchQuery].
     * Matches are case-insensitive on channel name, RX frequency (MHz), radio-specific
     * [Channel.extra] keys/values, or group letter / resolved label for values that are
     * single letters A–O (same labels as EEPROM group names).
     */
    private fun applyFilter() {
        if (searchQuery.isBlank()) {
            adapter.submitList(channelList.toList())
            return
        }
        val q = searchQuery.trim().lowercase()
        val labels = EepromHolder.groupLabels
        val filtered = channelList.filter { ch ->
            if (ch.empty) return@filter false
            if (ch.name.lowercase().contains(q)) return@filter true
            if (ch.displayFreq().contains(q)) return@filter true
            for ((k, vRaw) in ch.extra) {
                if (k.lowercase().contains(q)) return@filter true
                val v = vRaw.trim()
                if (v.lowercase().contains(q)) return@filter true
                val letterIdx = EepromConstants.GROUP_LETTERS.indexOf(v)
                if (letterIdx >= 0) {
                    val label = labels.getOrNull(letterIdx)?.trim() ?: ""
                    if (label.lowercase().contains(q)) return@filter true
                }
            }
            false
        }
        adapter.submitList(filtered)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CHIRP CSV export
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Asks the user to name the export file, then generates a CHIRP-compatible CSV
     * from selected non-empty channels (or all non-empty if none selected) and shares.
     */
    private fun exportSelectedChannels() {
        val selected = adapter.selectedChannelNumbers
        val channels = if (selected.isEmpty()) {
            channelList.filter { !it.empty }.sortedBy { it.number }
        } else {
            channelList.filter { it.number in selected && !it.empty }.sortedBy { it.number }
        }

        if (channels.isEmpty()) {
            Toast.makeText(this, "No non-empty channels to export", Toast.LENGTH_SHORT).show()
            return
        }

        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val defaultName = "chirp_export_$ts"

        val input = android.widget.EditText(this).apply {
            setText(defaultName)
            selectAll()
            val px = (16 * resources.displayMetrics.density).toInt()
            setPadding(px, px / 2, px, px / 2)
        }

        AlertDialog.Builder(this)
            .setTitle("Export CHIRP CSV")
            .setMessage("${channels.size} channel(s) will be exported.\nFile name:")
            .setView(input)
            .setPositiveButton("Export & Share") { _, _ ->
                val raw  = input.text.toString().trim().ifEmpty { defaultName }
                val name = raw.replace(Regex("[^a-zA-Z0-9_\\-.()]"), "_")
                doExportCsv(channels, name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doExportCsv(channels: List<Channel>, fileName: String) {
        val csv  = ChirpCsvExporter.export(channels)
        val dir  = getExternalFilesDir(null) ?: filesDir
        val file = File(dir, "$fileName.csv")
        file.writeText(csv)

        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "CHIRP CSV — $fileName")
            putExtra(Intent.EXTRA_TEXT,
                "CHIRP CSV export: $fileName.csv (${channels.size} channels)")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share CHIRP CSV"))
    }

    /**
     * Shows/hides the selection bar and keeps the count label up to date.
     * Called from [ChannelAdapter.onSelectionChanged] on every selection change.
     */
    private fun updateSelectionBar(count: Int) {
        if (count == 0) {
            binding.selectionBar.visibility = View.GONE
        } else {
            binding.selectionBar.visibility = View.VISIBLE
            binding.selectionCount.text = "$count selected"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Options menu
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val hasChannels = EepromHolder.channels.isNotEmpty()
        val hasCloneEeprom = EepromHolder.eeprom != null && EepromHolder.eeprom!!.isNotEmpty()
        menu.findItem(R.id.action_import_chirp)?.isEnabled           = hasChannels
        menu.findItem(R.id.action_import_chirp_clipboard)?.isEnabled = hasChannels
        menu.findItem(R.id.action_search_repeaterbook)?.isEnabled    = hasChannels
        menu.findItem(R.id.action_radio_settings)?.isEnabled          =
            EepromHolder.radioFeatures.hasSettings && selectedRadio != null &&
            (activePort != null || hasCloneEeprom)
        menu.findItem(R.id.action_import_backup)?.isEnabled           = selectedRadio != null
        menu.findItem(R.id.action_export_backup)?.isEnabled           = hasChannels
        menu.findItem(R.id.action_export_raw_eeprom)?.isEnabled       = hasCloneEeprom
        menu.findItem(R.id.action_export_csv)?.isEnabled             = hasChannels
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_search -> {
                toggleSearchBar()
                true
            }
            R.id.action_import_chirp -> {
                // Open system file picker — accept CSV and plain-text MIME types
                csvPickerLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*"))
                true
            }
            R.id.action_import_chirp_clipboard -> {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val csvText = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                if (csvText.isBlank()) {
                    Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                    return true
                }
                processChirpCsv(csvText)
                true
            }
            R.id.action_search_repeaterbook -> {
                startActivity(Intent(this, ChirpRepeaterBookSearchActivity::class.java))
                true
            }
            R.id.action_select_radio -> {
                radioSelectLauncher.launch(Intent(this, RadioSelectActivity::class.java))
                true
            }
            R.id.action_customize_main_screen -> {
                customizeMainScreenLauncher.launch(Intent(this, MainDisplayCustomizeActivity::class.java))
                true
            }
            R.id.action_radio_settings -> {
                val r = selectedRadio
                val p = activePort ?: ""
                if (r != null && (p.isNotBlank() || (EepromHolder.eeprom != null && EepromHolder.eeprom!!.isNotEmpty())))
                    startActivity(RadioSettingsActivity.intent(this, r, p))
                true
            }
            R.id.action_import_backup -> {
                backupPickerLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
                true
            }
            R.id.action_export_backup -> {
                exportRadioBackup()
                true
            }
            R.id.action_export_raw_eeprom -> {
                exportRawEeprom()
                true
            }
            R.id.action_export_csv -> {
                exportSelectedChannels()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        updateConnectionUi()
        // Refresh channel list if a download has already occurred (e.g. after
        // returning from ChannelEditActivity or ChirpImportActivity).
        if (EepromHolder.channels.isNotEmpty()) {
            eeprom = EepromHolder.eeprom ?: ByteArray(0)
            refreshChannelList()
        }
        invalidateOptionsMenu()
    }

    override fun onDestroy() {
        super.onDestroy()
        scanTimeoutHandler.removeCallbacks(stopScanRunnable)
        bleManager.stopScan()
        bleManager.disconnect()
        bleBridge?.close()
        usbBridge.close()
        usbPermissionReceiver?.let { runCatching { unregisterReceiver(it) } }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Permissions
    // ─────────────────────────────────────────────────────────────────────────

    private fun requestBluetoothPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+  — neverForLocation flag means no location permission needed
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT))
                perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN))
                perms.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            // Android < 12 — ACCESS_FINE_LOCATION is required for BLE scanning
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION))
                perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (perms.isNotEmpty()) permissionLauncher.launch(perms.toTypedArray())
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    // ─────────────────────────────────────────────────────────────────────────
    // Connection management
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * True when any transport is active:
     *  - [activePort] is set  → USB OTG or BLE (via BleBridge LocalSocket)
     *  - [activeStream] only  → Classic SPP (no Python CHIRP port yet; download
     *                           will fail gracefully if no port)
     */
    private fun isAnyConnected() = activePort != null || activeStream != null

    private fun disconnectAll() {
        bleManager.disconnect()
        btManager.disconnect()
        usbBridge.close()
        bleBridge?.close()
        bleBridge     = null
        activeStream  = null
        activePort    = null
        activeDeviceName = null
    }

    /**
     * Shows the top-level connection picker:
     *  - BLE scan (primary for most radios)
     *  - USB OTG via usb-serial-for-android  ← new Phase 1 path
     *  - Classic SPP (fallback for legacy paired devices)
     */
    private fun showConnectPicker() {
        AlertDialog.Builder(this)
            .setTitle("Connect to radio")
            .setItems(arrayOf(
                "Scan for Radio  (BLE)",
                "USB OTG  (cable)",
                "Paired Devices  (Classic BT)"
            )) { _, which ->
                when (which) {
                    0 -> startBleScan()
                    1 -> startUsbConnection()
                    2 -> showPairedDevicePicker()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // USB OTG connection flow
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Enumerates USB serial devices via [UsbSerialBridge.listDrivers()].
     * If a device is found, requests USB permission then opens the LocalSocket relay.
     */
    private fun startUsbConnection() {
        val drivers = usbBridge.listDrivers()
        when {
            drivers.isEmpty() -> Toast.makeText(
                this,
                "No USB serial device found — connect radio via OTG cable and try again.",
                Toast.LENGTH_LONG
            ).show()

            drivers.size == 1 -> requestUsbPermission(drivers[0])

            else -> {
                // Multiple USB devices — let the user pick
                val names = drivers.map { it.device.deviceName }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Select USB device")
                    .setItems(names) { _, which -> requestUsbPermission(drivers[which]) }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
        }
    }

    /**
     * Requests Android USB host permission for [driver].
     * If already granted, opens the bridge immediately.
     * Otherwise registers a one-shot BroadcastReceiver for the result.
     */
    private fun requestUsbPermission(driver: UsbSerialDriver) {
        val manager = getSystemService(USB_SERVICE) as UsbManager
        if (manager.hasPermission(driver.device)) {
            openUsbBridge(driver)
            return
        }
        // One-shot receiver — unregisters itself after the permission dialog resolves
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                unregisterReceiver(this)
                usbPermissionReceiver = null
                runOnUiThread {
                    if (granted) {
                        openUsbBridge(driver)
                    } else {
                        Toast.makeText(this@MainActivity, "USB permission denied", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        usbPermissionReceiver = receiver
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE else 0
        val permIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
        // API 34+ requires RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED.
        // USB permission results come from the system, so NOT_EXPORTED is correct.
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        manager.requestPermission(driver.device, permIntent)
    }

    /**
     * Opens [driver] at the selected radio's baud rate and starts the LocalSocket
     * relay. [UsbSerialBridge.openSocketBridge] returns e.g. `android://rdusb_<uuid>`
     * (control socket: same base + `_ctrl`); that URL is stored in [activePort] for Python.
     */
    private fun openUsbBridge(driver: UsbSerialDriver) {
        val radio = selectedRadio ?: run {
            Toast.makeText(this,
                "Select a radio model first (⋮ → Select Radio Model…)", Toast.LENGTH_LONG).show()
            return
        }
        try {
            usbBridge.close()          // clean up any prior open
            val port = usbBridge.openSocketBridge(driver, radio.baudRate)
            activePort       = port
            activeDeviceName = "USB: ${driver.device.deviceName}"
            updateConnectionUi()
            Toast.makeText(this, "USB connected — ${driver.device.deviceName}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "USB open failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BLE scan flow
    // ─────────────────────────────────────────────────────────────────────────

    private fun startBleScan() {
        scanDevices.clear()
        scanDeviceRssi.clear()

        scanListAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        scanListAdapter.add("Scanning…  (up to 8 seconds)")

        scanDialog = AlertDialog.Builder(this)
            .setTitle("Select Radio  (BLE)")
            .setAdapter(scanListAdapter) { _, which ->
                if (which < scanDevices.size) {
                    scanTimeoutHandler.removeCallbacks(stopScanRunnable)
                    bleManager.stopScan()
                    scanDialog?.dismiss()
                    scanDialog = null
                    connectBleDevice(scanDevices[which])
                }
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                scanTimeoutHandler.removeCallbacks(stopScanRunnable)
                bleManager.stopScan()
            }
            .create()
        scanDialog?.show()

        bleManager.startScan(
            onFound = { device, rssi -> runOnUiThread { addScanResult(device, rssi) } },
            onError = { code ->
                runOnUiThread {
                    scanDialog?.dismiss()
                    scanDialog = null
                    Toast.makeText(
                        this,
                        "BLE scan failed (code $code). Check Bluetooth is on and permissions are granted.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )

        // Auto-stop scan after 8 seconds
        scanTimeoutHandler.postDelayed(stopScanRunnable, 8_000)
    }

    @Suppress("MissingPermission")
    private fun addScanResult(device: BluetoothDevice, rssi: Int) {
        scanDevices.add(device)
        scanDeviceRssi.add(rssi)
        scanListAdapter.clear()
        scanDevices.forEachIndexed { i, d ->
            val name = d.name?.takeIf { it.isNotBlank() } ?: d.address
            scanListAdapter.add("$name  (${scanDeviceRssi[i]} dBm)")
        }
        scanListAdapter.notifyDataSetChanged()
    }

    private fun stopBleScan() {
        bleManager.stopScan()
        if (scanDevices.isEmpty()) {
            scanDialog?.dismiss()
            scanDialog = null
            Toast.makeText(
                this,
                "No BLE devices found. Make sure the radio is powered on and in range.",
                Toast.LENGTH_LONG
            ).show()
        }
        // If devices were found the dialog stays open for the user to pick
    }

    @Suppress("MissingPermission")
    private fun connectBleDevice(device: BluetoothDevice) {
        val label = device.name?.takeIf { it.isNotBlank() } ?: device.address
        Toast.makeText(this, "Connecting to $label…", Toast.LENGTH_SHORT).show()

        bleManager.connect(device) { result ->
            runOnUiThread {
                result.fold(
                    onSuccess = { stream ->
                        activeStream     = stream
                        activeDeviceName = bleManager.deviceName()
                        // Open a LocalSocket relay so Python's AndroidSerial can reach the BLE stream
                        bleBridge?.close()
                        val bridge = BleBridge(bleManager)
                        bleBridge  = bridge
                        activePort = bridge.openSocketBridge()
                        updateConnectionUi()
                        Toast.makeText(this, "Connected via BLE", Toast.LENGTH_SHORT).show()
                    },
                    onFailure = { e ->
                        updateConnectionUi()
                        Toast.makeText(this, "BLE connect failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Classic SPP flow (fallback)
    // ─────────────────────────────────────────────────────────────────────────

    private fun showPairedDevicePicker() {
        val devices = btManager.pairedDevices()
        if (devices.isEmpty()) {
            Toast.makeText(
                this,
                "No paired devices. Pair the radio in System Bluetooth settings first.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        @Suppress("MissingPermission")
        val names = devices.map { it.name ?: it.address }
        AlertDialog.Builder(this)
            .setTitle("Select radio  (Classic BT)")
            .setItems(names.toTypedArray()) { _, which ->
                connectSppDevice(devices[which])
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun connectSppDevice(device: BluetoothDevice) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { btManager.connect(device) }
            runOnUiThread {
                result.fold(
                    onSuccess = { stream ->
                        activeStream = stream
                        activeDeviceName = btManager.connectedDeviceName()
                        updateConnectionUi()
                        Toast.makeText(this@MainActivity, "Connected via SPP", Toast.LENGTH_SHORT).show()
                    },
                    onFailure = { e ->
                        Toast.makeText(this@MainActivity, "SPP connect failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun updateConnectionUi() {
        val connected  = isAnyConnected()
        val radioLabel = selectedRadio?.displayName

        binding.statusText.text = when {
            connected && radioLabel != null ->
                getString(R.string.status_connected, "$radioLabel via ${activeDeviceName ?: "radio"}")
            connected ->
                getString(R.string.status_connected, activeDeviceName ?: "radio")
            radioLabel != null ->
                "$radioLabel — not connected"
            else ->
                getString(R.string.status_disconnected)
        }
        binding.btnConnect.text =
            if (connected) getString(R.string.disconnect) else getString(R.string.connect)
        // Load requires an active port AND a selected radio; save requires channels loaded
        binding.btnLoad.isEnabled = connected && selectedRadio != null
        binding.btnSave.isEnabled = connected && EepromHolder.channels.isNotEmpty()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Channel selection — in-app bar replaces the system ActionMode/CAB
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Enters multi-select mode for [channel] as the first selection and reveals
     * the in-app selection bar at the bottom of the screen.
     */
    private fun enterSelectionMode(channel: Channel) {
        adapter.enterSelectionMode(channel.number)
        // updateSelectionBar() is called automatically via onSelectionChanged callback
    }

    /**
     * Moves every selected channel up by one slot, keeping contiguous groups
     * together as a unit.
     *
     * Algorithm: build a [BooleanArray] of which positions are selected, then
     * scan top-to-bottom for contiguous selected blocks.  For each block that
     * has a free (non-selected) slot immediately above it, rotate that slot to
     * just below the block — equivalent to the whole block sliding up by one.
     * Uses [Collections.rotate] on a sub-list view for an O(n) in-place move.
     */
    private fun moveSelectedUp() {
        val eep = eeprom ?: return
        val selected = adapter.selectedChannelNumbers
        if (selected.isEmpty()) return

        val channels = EepromParser.parseAllChannels(eep).toMutableList()

        // Parallel boolean array — true if channels[i] is currently selected.
        val sel = BooleanArray(channels.size) { i -> (i + 1) in selected }

        var i = 0
        while (i < channels.size) {
            if (sel[i]) {
                // Find end of this contiguous selected block
                var j = i
                while (j + 1 < channels.size && sel[j + 1]) j++

                // Block = [i..j].  Can we move it up?
                if (i > 0 && !sel[i - 1]) {
                    // Rotate [i-1 .. j] left by 1:
                    //   [above, blk0, blk1, …, blkN] → [blk0, blk1, …, blkN, above]
                    Collections.rotate(channels.subList(i - 1, j + 1), -1)
                    sel[j]     = false   // "above" channel now sits at j
                    sel[i - 1] = true    // block now occupies [i-1 .. j-1]
                }
                i = j + 1
            } else {
                i++
            }
        }

        // Renumber and build new selected set
        channels.forEachIndexed { idx, ch -> channels[idx] = ch.copy(number = idx + 1) }
        val newSelected = channels.indices.filter { sel[it] }.map { it + 1 }.toSet()

        applyChannelReorder(eep, channels, newSelected)
    }

    /**
     * Moves every selected channel down by one slot, keeping contiguous groups
     * together as a unit.
     *
     * Mirror of [moveSelectedUp]: scans bottom-to-top for contiguous blocks and
     * rotates the slot immediately below each block to just above it.
     */
    private fun moveSelectedDown() {
        val eep = eeprom ?: return
        val selected = adapter.selectedChannelNumbers
        if (selected.isEmpty()) return

        val channels = EepromParser.parseAllChannels(eep).toMutableList()

        val sel = BooleanArray(channels.size) { i -> (i + 1) in selected }

        var j = channels.size - 1
        while (j >= 0) {
            if (sel[j]) {
                // Find start of this contiguous selected block
                var i = j
                while (i - 1 >= 0 && sel[i - 1]) i--

                // Block = [i..j].  Can we move it down?
                if (j < channels.size - 1 && !sel[j + 1]) {
                    // Rotate [i .. j+1] right by 1:
                    //   [blk0, blk1, …, blkN, below] → [below, blk0, blk1, …, blkN]
                    Collections.rotate(channels.subList(i, j + 2), 1)
                    sel[i]     = false   // "below" channel now sits at i
                    sel[j + 1] = true    // block now occupies [i+1 .. j+1]
                }
                j = i - 1
            } else {
                j--
            }
        }

        channels.forEachIndexed { idx, ch -> channels[idx] = ch.copy(number = idx + 1) }
        val newSelected = channels.indices.filter { sel[it] }.map { it + 1 }.toSet()

        applyChannelReorder(eep, channels, newSelected)
    }

    /**
     * Shows a Spinner dialog for the user to choose a target slot, then moves every
     * selected channel to that position as a contiguous block, preserving relative order.
     *
     * Algorithm:
     *  1. Split channels into [movingChannels] (selected) and [stayingChannels] (non-selected).
     *  2. [insertIdx] = (targetSlot - 1) clamped to stayingChannels.size so the block always
     *     fits within 1–198 even if the user picks a slot near the end.
     *  3. Splice: staying[0..insertIdx-1] + moving + staying[insertIdx..].
     *  4. Renumber 1–198 and commit via [applyChannelReorder].
     *  After the reorder the first moving channel lands at slot insertIdx + 1.
     */
    private fun moveSelectedToPosition() {
        val eep = eeprom ?: return
        val selected = adapter.selectedChannelNumbers
        if (selected.isEmpty()) return

        val channels = EepromParser.parseAllChannels(eep)
        val stayingChannels = channels.filter { it.number !in selected }

        if (stayingChannels.isEmpty()) {
            Toast.makeText(this, "All channels selected — nothing to move relative to",
                Toast.LENGTH_SHORT).show()
            return
        }

        // Spinner labels: "Ch N" for empty slots, "Ch N – Name" for named channels
        val slotLabels: List<String> = channels.map { ch ->
            if (ch.empty) "Ch ${ch.number}"
            else "Ch ${ch.number} – ${ch.name.ifBlank { "…" }}"
        }

        // Default: first selected slot (no-op — "start at X" where X is already where they are)
        val defaultIndex = (selected.min() - 1).coerceAtLeast(0)

        val dialogView = layoutInflater.inflate(R.layout.dialog_move_to_slot, null)
        val spinner   = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerTargetSlot)
        val hintText  = dialogView.findViewById<android.widget.TextView>(R.id.textMoveToHint)

        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            slotLabels
        )
        spinner.setSelection(defaultIndex)

        fun refreshHint(pos: Int) {
            val insertIdx = (pos).coerceAtMost(stayingChannels.size)
            val landingSlot = insertIdx + 1
            val endSlot     = insertIdx + selected.size
            hintText.text   = "${selected.size} channel(s) will occupy slot(s) $landingSlot–$endSlot"
        }
        refreshHint(defaultIndex)

        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                p: android.widget.AdapterView<*>?, v: android.view.View?,
                pos: Int, id: Long
            ) { refreshHint(pos) }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) = Unit
        }

        AlertDialog.Builder(this)
            .setTitle("Move ${selected.size} Channel(s) to Slot")
            .setView(dialogView)
            .setPositiveButton("Move") { _, _ ->
                val targetSlot = spinner.selectedItemPosition + 1
                val movingChannels = channels
                    .filter { it.number in selected }
                    .sortedBy { it.number }
                val insertIdx = (targetSlot - 1).coerceAtMost(stayingChannels.size)

                val newOrder: MutableList<Channel> = mutableListOf<Channel>().apply {
                    addAll(stayingChannels.subList(0, insertIdx))
                    addAll(movingChannels)
                    addAll(stayingChannels.subList(insertIdx, stayingChannels.size))
                }
                newOrder.forEachIndexed { idx, ch -> newOrder[idx] = ch.copy(number = idx + 1) }

                val newSelected = (1..movingChannels.size).map { insertIdx + it }.toSet()
                applyChannelReorder(eep, newOrder, newSelected)

                Toast.makeText(
                    this,
                    "Moved ${selected.size} channel(s) starting at slot ${insertIdx + 1}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Writes reordered channels back to the EEPROM, updates state, and refreshes the list. */
    private fun applyChannelReorder(
        eep: ByteArray,
        channels: MutableList<Channel>,
        newSelected: Set<Int>
    ) {
        for (ch in channels) EepromParser.writeChannel(eep, ch)
        eeprom = eep
        EepromHolder.eeprom = eep
        channelList = channels.toList()
        dragWorkList = channels.toMutableList()
        adapter.submitList(channelList)
        adapter.updateSelection(newSelected)
        syncCloneMmapAfterChannelListEdits()
        // updateSelectionBar called via onSelectionChanged
    }

    /**
     * Called by [ItemTouchHelper] after the user drops a dragged card.
     * Commits the drag order in [dragWorkList] to the EEPROM, renumbering
     * channels to match their new positions. The dragged channel stays selected.
     */
    private fun applyDragReorder() {
        val eep = eeprom ?: return
        if (dragWorkList.isEmpty()) return

        // Capture which original numbers were selected before renumbering
        val oldSelected = adapter.selectedChannelNumbers
        val newSelected = mutableSetOf<Int>()

        // Renumber channels in their new drag order (1-based) and track selection
        dragWorkList.forEachIndexed { idx, ch ->
            if (ch.number in oldSelected) newSelected.add(idx + 1)
            dragWorkList[idx] = ch.copy(number = idx + 1)
        }

        // Write the renumbered channels to EEPROM
        for (ch in dragWorkList) EepromParser.writeChannel(eep, ch)
        eeprom = eep
        EepromHolder.eeprom = eep
        channelList = dragWorkList.toList()

        // Update the ListAdapter — DiffUtil detects position changes
        adapter.submitList(channelList)
        adapter.updateSelection(newSelected)
        syncCloneMmapAfterChannelListEdits()
        // updateSelectionBar called via onSelectionChanged
    }

    /**
     * Prompts the user to confirm, then erases the data from each selected channel slot
     * (sets it to empty/unused). The slot numbers themselves are unchanged.
     */
    private fun clearSelectedChannels() {
        val eep = eeprom ?: return
        val selected = adapter.selectedChannelNumbers
        if (selected.isEmpty()) return

        AlertDialog.Builder(this)
            .setTitle("Clear Channels")
            .setMessage(
                "Clear ${selected.size} selected channel(s)?\n\n" +
                "This erases the stored data and marks the slot(s) as empty. " +
                "Slot numbers are not affected."
            )
            .setPositiveButton("Clear") { _, _ ->
                for (num in selected) {
                    EepromParser.writeChannel(eep, Channel(number = num, empty = true))
                }
                eeprom = eep
                EepromHolder.eeprom = eep
                channelList = EepromParser.parseAllChannels(eep)
                dragWorkList = channelList.toMutableList()
                adapter.submitList(channelList)
                adapter.exitSelectionMode()
                syncCloneMmapAfterChannelListEdits()
                // updateSelectionBar(0) called via onSelectionChanged → hides bar
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Shows a TX-power picker and applies the chosen level to every non-empty
     * selected channel in one operation.
     *
     * The spinner is pre-selected to the power of the first non-empty selection
     * so the user can see the current level at a glance.  After applying, the
     * selection remains active so the user can chain other bulk actions.
     */
    private fun setTxPowerSelected() {
        val eep = eeprom ?: return
        val selected = adapter.selectedChannelNumbers
        if (selected.isEmpty()) return

        val channels = EepromParser.parseAllChannels(eep)

        // Use the driver's actual power level names so the bulk picker matches
        // the channel editor exactly.  Fall back to the generic list when the
        // driver has no discrete power concept (features not yet loaded).
        val driverLevels = EepromHolder.radioFeatures.validPowerLevels
        val powerList = driverLevels.ifEmpty { EepromConstants.POWERLEVEL_LIST }

        // Pre-select the current power of the first non-empty selected channel
        val firstNonEmpty = channels.firstOrNull { it.number in selected && !it.empty }
        val defaultIdx = powerList
            .indexOf(firstNonEmpty?.power ?: "")
            .coerceAtLeast(0)

        // Determine applicable VHF/UHF cap(s) for the selected channels so we can
        // advise the user when the chosen value would exceed the radio's cap.
        val selectedNonEmpty = channels.filter { it.number in selected && !it.empty }
        val hasVhf = selectedNonEmpty.any { it.freqRxHz in 1 until EepromConstants.VHF_UHF_BOUNDARY_HZ }
        val hasUhf = selectedNonEmpty.any { it.freqRxHz >= EepromConstants.VHF_UHF_BOUNDARY_HZ }
        val ts     = EepromHolder.tuneSettings
        val vhfCap = ts.maxPowerSettingVHF
        val uhfCap = ts.maxPowerSettingUHF
        // Effective cap: the most restrictive cap across the bands present in the selection
        val effectiveCap = when {
            hasVhf && hasUhf -> minOf(vhfCap, uhfCap)
            hasVhf           -> vhfCap
            hasUhf           -> uhfCap
            else             -> 255
        }

        // NumberPicker shows the full value string in a scrollable wheel.
        val picker = android.widget.NumberPicker(this).apply {
            minValue = 0
            maxValue = powerList.size - 1
            displayedValues = powerList.toTypedArray()
            value = defaultIdx
            wrapSelectorWheel = false
        }

        // Centre the wheel horizontally inside the dialog with comfortable padding
        val wrapper = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val px = (16 * resources.displayMetrics.density).toInt()
            setPadding(px, px / 2, px, px / 2)
        }
        wrapper.addView(
            picker,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = android.view.Gravity.CENTER_HORIZONTAL }
        )

        AlertDialog.Builder(this)
            .setTitle("Set TX Power — ${selected.size} selected")
            .setView(wrapper)
            .setPositiveButton("Apply") { _, _ ->
                val powerStr = powerList.getOrNull(picker.value) ?: ""
                var count = 0
                for (ch in channels) {
                    if (ch.number in selected && !ch.empty) {
                        EepromParser.writeChannel(eep, ch.copy(power = powerStr))
                        count++
                    }
                }
                eeprom = eep
                EepromHolder.eeprom = eep
                channelList = EepromParser.parseAllChannels(eep)
                dragWorkList = channelList.toMutableList()
                adapter.submitList(channelList)
                // Keep selection active — user may want to move or clear next
                Toast.makeText(
                    this@MainActivity,
                    "TX power → $powerStr on $count channel(s)",
                    Toast.LENGTH_SHORT
                ).show()
                syncCloneMmapAfterChannelListEdits()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Bulk-edit one [Channel.extra] field on all non-empty selected channels.
     * Field list and types come from [EepromHolder.channelExtraSchema] (same as the channel editor).
     */
    private fun setRadioSpecificExtraSelected() {
        val eep = eeprom ?: return
        val selected = adapter.selectedChannelNumbers
        if (selected.isEmpty()) return

        val writable = EepromHolder.channelExtraSchema.filter { !it.readOnly }
        if (writable.isEmpty()) {
            Toast.makeText(this, R.string.bulk_extra_no_schema, Toast.LENGTH_LONG).show()
            return
        }
        val labels = writable.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.bulk_extra_pick_param, selected.size))
            .setItems(labels) { dialog, which ->
                dialog.dismiss()
                showBulkExtraValueDialog(writable[which], eep, selected)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showBulkExtraValueDialog(setting: ChannelExtraSetting, eep: ByteArray, selected: Set<Int>) {
        val channels = EepromParser.parseAllChannels(eep)
        val firstSample = channels.firstOrNull { it.number in selected && !it.empty }
        val currentVal = firstSample?.extra?.get(setting.name) ?: setting.value

        when (setting.type) {
            "list" -> {
                val opts = setting.options
                if (opts.isNullOrEmpty()) {
                    showBulkExtraTextFieldDialog(setting, eep, selected, currentVal, InputType.TYPE_CLASS_TEXT, null, null)
                    return
                }
                var chosenIdx = opts.indexOfFirst { it == currentVal }.let { if (it >= 0) it else 0 }
                AlertDialog.Builder(this)
                    .setTitle(setting.name)
                    .setSingleChoiceItems(opts.toTypedArray(), chosenIdx) { _, which ->
                        chosenIdx = which
                    }
                    .setPositiveButton(R.string.ok) { _, _ ->
                        applyBulkExtraToSelection(eep, selected, setting.name, opts[chosenIdx])
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            "bool" -> {
                val check = CheckBox(this).apply {
                    text = setting.name
                    isChecked = currentVal.equals("True", ignoreCase = true)
                }
                val pad = (20 * resources.displayMetrics.density).toInt()
                val wrap = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(pad, pad / 2, pad, 0)
                    addView(check)
                }
                AlertDialog.Builder(this)
                    .setTitle(setting.name)
                    .setView(wrap)
                    .setPositiveButton(R.string.ok) { _, _ ->
                        val v = if (check.isChecked) "True" else "False"
                        applyBulkExtraToSelection(eep, selected, setting.name, v)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            "int" -> {
                val min = setting.min
                val max = setting.max
                if (min != null && max != null) {
                    val initial = currentVal.toIntOrNull()?.coerceIn(min, max) ?: min
                    val np = NumberPicker(this).apply {
                        minValue = min
                        maxValue = max
                        value = initial
                        wrapSelectorWheel = false
                    }
                    val pad = (20 * resources.displayMetrics.density).toInt()
                    val wrap = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(pad, pad / 2, pad, 0)
                        gravity = android.view.Gravity.CENTER_HORIZONTAL
                        addView(np)
                    }
                    AlertDialog.Builder(this)
                        .setTitle(setting.name)
                        .setView(wrap)
                        .setPositiveButton(R.string.ok) { _, _ ->
                            applyBulkExtraToSelection(eep, selected, setting.name, np.value.toString())
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                } else {
                    showBulkExtraTextFieldDialog(
                        setting, eep, selected, currentVal,
                        InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED,
                        null,
                        "int",
                    )
                }
            }
            "float" -> showBulkExtraTextFieldDialog(
                setting, eep, selected, currentVal,
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED,
                null,
                "float",
            )
            else -> {
                val maxLen = setting.maxLength
                val filters: Array<InputFilter>? = if (maxLen != null && maxLen > 0) {
                    arrayOf<InputFilter>(InputFilter.LengthFilter(maxLen))
                } else null
                showBulkExtraTextFieldDialog(
                    setting, eep, selected, currentVal,
                    InputType.TYPE_CLASS_TEXT,
                    filters,
                    "string",
                )
            }
        }
    }

    /**
     * @param numberKind `int`, `float`, `string`, or null for no numeric check
     */
    private fun showBulkExtraTextFieldDialog(
        setting: ChannelExtraSetting,
        eep: ByteArray,
        selected: Set<Int>,
        initial: String,
        inputType: Int,
        filters: Array<InputFilter>?,
        numberKind: String?,
    ) {
        val edit = EditText(this).apply {
            setText(initial)
            this.inputType = inputType
            filters?.let { this.filters = it }
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(edit)
        }
        AlertDialog.Builder(this)
            .setTitle(setting.name)
            .setView(wrap)
            .setPositiveButton(R.string.ok) { _, _ ->
                val raw = edit.text?.toString()?.trim() ?: ""
                val value = when (numberKind) {
                    "int" -> {
                        val n = raw.toIntOrNull()
                        if (n == null) {
                            Toast.makeText(this, R.string.bulk_extra_invalid_number, Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                        n.toString()
                    }
                    "float" -> {
                        val n = raw.toDoubleOrNull()
                        if (n == null) {
                            Toast.makeText(this, R.string.bulk_extra_invalid_number, Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                        raw
                    }
                    else -> raw
                }
                applyBulkExtraToSelection(eep, selected, setting.name, value)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyBulkExtraToSelection(eep: ByteArray, selected: Set<Int>, paramName: String, value: String) {
        val radio = selectedRadio ?: EepromHolder.selectedRadio
        if (paramName.equals("bandwidth", ignoreCase = true) && radio != null && eep.isNotEmpty()) {
            lifecycleScope.launch {
                val isClone = withContext(Dispatchers.IO) { ChirpBridge.isCloneModeRadio(radio) }
                if (isClone) {
                    val b64 = Base64.encodeToString(eep, Base64.NO_WRAP)
                    val channels = EepromParser.parseAllChannels(eep)
                    val lines = mutableListOf<String>()
                    for (ch in channels) {
                        if (ch.number !in selected || ch.empty) continue
                        val merged = ch.copy(extra = ch.extra.toMutableMap().apply { put(paramName, value) })
                        val msgs = withContext(Dispatchers.IO) {
                            ChirpBridge.validateChannel(radio, b64, merged)
                        }
                        msgs.filter { it.kind == "error" }.forEach { m ->
                            lines.add("Channel ${ch.number}: ${m.text}")
                        }
                    }
                    if (lines.isNotEmpty()) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Cannot apply bandwidth")
                            .setMessage(lines.joinToString("\n\n"))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                        return@launch
                    }
                }
                applyBulkExtraToSelectionCommit(eep, selected, paramName, value)
            }
            return
        }
        applyBulkExtraToSelectionCommit(eep, selected, paramName, value)
    }

    private fun applyBulkExtraToSelectionCommit(eep: ByteArray, selected: Set<Int>, paramName: String, value: String) {
        val channels = EepromParser.parseAllChannels(eep)
        var count = 0
        for (ch in channels) {
            if (ch.number in selected && !ch.empty) {
                val newExtra = ch.extra.toMutableMap().apply { put(paramName, value) }
                EepromParser.writeChannel(eep, ch.copy(extra = newExtra))
                count++
            }
        }
        eeprom = eep
        EepromHolder.eeprom = eep
        channelList = EepromParser.parseAllChannels(eep)
        dragWorkList = channelList.toMutableList()
        adapter.submitList(channelList)
        Toast.makeText(
            this,
            getString(R.string.bulk_extra_applied, paramName, value, count),
            Toast.LENGTH_SHORT,
        ).show()
        syncCloneMmapAfterChannelListEdits()
    }

    // ─────────────────────────────────────────────────────────────────────────

    /** Cycles [progressText] while transfer runs; bridge does not report percent complete. */
    private fun startTransferStatusHints(messageResIds: IntArray) {
        stopTransferStatusHints()
        binding.progressText.setText(messageResIds[0])
        var index = 0
        transferStatusJob = lifecycleScope.launch {
            while (isActive) {
                delay(2_800)
                index = (index + 1) % messageResIds.size
                withContext(Dispatchers.Main) {
                    binding.progressText.setText(messageResIds[index])
                }
            }
        }
    }

    private fun stopTransferStatusHints() {
        transferStatusJob?.cancel()
        transferStatusJob = null
    }

    private fun hideRadioTransferProgress() {
        stopTransferStatusHints()
        binding.progressBar.visibility = View.GONE
        binding.progressBar.isIndeterminate = false
        binding.progressText.visibility = View.GONE
    }

    private fun loadFromRadio() {
        val radio = selectedRadio ?: run {
            Toast.makeText(this, "Select a radio model first (⋮ → Select Radio Model)", Toast.LENGTH_LONG).show()
            return
        }
        val port = activePort ?: run {
            Toast.makeText(this, "No active connection — connect to the radio first", Toast.LENGTH_SHORT).show()
            return
        }
        binding.progressBar.visibility = View.VISIBLE
        binding.progressText.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = true
        startTransferStatusHints(downloadStatusMessages)
        binding.btnLoad.isEnabled = false
        binding.btnSave.isEnabled = false
        lifecycleScope.launch {
            try {
                EepromHolder.selectedRadio = radio
                val result = ChirpBridge.download(radio, port)
                eeprom = if (result.eepromBase64 != null) {
                    Base64.decode(result.eepromBase64, Base64.NO_WRAP)
                } else {
                    ByteArray(0)   // non-clone sentinel
                }
                EepromHolder.eeprom    = eeprom
                EepromHolder.channels  = result.channels.toMutableList()
                EepromHolder.extraParamNames = result.channels
                    .firstOrNull { it.extra.isNotEmpty() }
                    ?.extra?.keys?.toList() ?: emptyList()
                EepromHolder.channelExtraSchema = ChirpBridge.getChannelExtraSchema(radio, result.eepromBase64)
                refreshChannelList()
                runOnUiThread {
                    hideRadioTransferProgress()
                    updateConnectionUi()
                    invalidateOptionsMenu()
                    val msg = if (result.isCloneMode)
                        "Downloaded ${result.channels.size} channels (EEPROM in memory). Use Radio Settings to edit; Save to radio from here when ready."
                    else
                        "Downloaded ${result.channels.size} channels from ${radio.vendor} ${radio.model}"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    hideRadioTransferProgress()
                    updateConnectionUi()
                    Toast.makeText(this@MainActivity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun refreshChannelList() {
        channelList  = EepromHolder.channels.toList()
        dragWorkList = channelList.toMutableList()
        applyFilter()   // respects current searchQuery; submits full list when blank
    }

    private fun showSaveConfirm() {
        if (EepromHolder.channels.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.save_confirm_title)
            .setMessage(getString(R.string.save_confirm_message))
            .setPositiveButton(R.string.ok) { _, _ -> saveToRadio() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun saveToRadio() {
        val radio = selectedRadio ?: run {
            Toast.makeText(this, "Select a radio model first", Toast.LENGTH_SHORT).show()
            return
        }
        val port = activePort ?: run {
            Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show()
            return
        }
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = true
        binding.progressText.visibility = View.VISIBLE
        startTransferStatusHints(uploadStatusMessages)
        binding.btnSave.isEnabled = false
        lifecycleScope.launch {
            try {
                val isClone = withContext(Dispatchers.IO) { ChirpBridge.isCloneModeRadio(radio) }
                var eep = EepromHolder.eeprom
                if (isClone && eep != null && eep.isNotEmpty()) {
                    eep = withContext(Dispatchers.IO) {
                        ChirpBridge.syncCloneMmapToChannelList(radio, eep!!, EepromHolder.channels.toList())
                    }
                    EepromHolder.eeprom = eep
                    this@MainActivity.eeprom = eep
                }
                val isCloneWithEeprom = isClone && eep != null && eep.isNotEmpty()
                if (isCloneWithEeprom) {
                    // Clone mode: mmap was just synced from the channel list, then settings in EEPROM
                    val b64 = Base64.encodeToString(eep!!, Base64.NO_WRAP)
                    ChirpBridge.uploadMmap(radio, port, b64)
                } else {
                    // Non-clone: upload channels, then apply any pending settings edits
                    ChirpBridge.upload(radio, port, EepromHolder.channels.toList())
                    val pendingSettings = EepromHolder.pendingSettingsJson
                    if (pendingSettings != null) {
                        ChirpBridge.setSettingsLive(radio, port, pendingSettings)
                        EepromHolder.pendingSettingsJson = null
                    }
                }
                runOnUiThread {
                    hideRadioTransferProgress()
                    updateConnectionUi()
                    Toast.makeText(this@MainActivity, "Saved to radio", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    hideRadioTransferProgress()
                    updateConnectionUi()
                    Toast.makeText(this@MainActivity, "Upload failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Constants
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "MainActivity"
        /** Broadcast action for USB host permission results. */
        private const val ACTION_USB_PERMISSION = "com.radiodroid.app.USB_PERMISSION"
    }
}
