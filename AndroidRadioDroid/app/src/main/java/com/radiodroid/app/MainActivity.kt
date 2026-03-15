package com.radiodroid.app

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.radiodroid.app.bluetooth.BleManager
import com.radiodroid.app.bluetooth.BtSerialManager
import com.radiodroid.app.databinding.ActivityMainBinding
import android.text.Editable
import android.text.TextWatcher
import com.radiodroid.app.bridge.ChirpBridge
import com.radiodroid.app.model.RadioInfo
import com.radiodroid.app.radio.Channel
import com.radiodroid.app.radio.ChirpCsvExporter
import com.radiodroid.app.radio.ChirpCsvImporter
import com.radiodroid.app.radio.EepromConstants
import com.radiodroid.app.radio.EepromParser
import com.radiodroid.app.radio.Protocol
import com.radiodroid.app.radio.RadioStream
import kotlinx.coroutines.Dispatchers
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

    // BLE — primary connection method for nicFW TD-H3
    private lateinit var bleManager: BleManager

    /** Whichever connection (BLE or SPP) is currently active. Null when disconnected. */
    private var activeStream: RadioStream? = null
    private var activeDeviceName: String? = null

    /** Currently selected radio model (set by RadioSelectActivity). */
    private var selectedRadio: RadioInfo? = null

    /** Serial port string for the active connection ("android://radiodroid_ble" etc.). */
    private var activePort: String? = null

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

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        setSupportActionBar(binding.toolbar)
        btManager = BtSerialManager(this)
        bleManager = BleManager(this)

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
        binding.btnSetGroups.setOnClickListener     { setGroupsSelected() }
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
                binding.btnSelectAllMatches.visibility =
                    if (searchQuery.isNotEmpty()) View.VISIBLE else View.GONE
                applyFilter()
            }
        })
        binding.btnClearSearch.setOnClickListener {
            searchQuery = ""
            binding.searchEditText.setText("")
            binding.searchBar.visibility = View.GONE
            binding.btnSelectAllMatches.visibility = View.GONE
            applyFilter()
        }
        binding.btnSelectAllMatches.setOnClickListener {
            if (channelList.isNotEmpty()) adapter.selectAllVisible()
        }
    }

    /** Toggles the search bar open/closed and resets the query when closing. */
    private fun toggleSearchBar() {
        if (binding.searchBar.visibility == View.VISIBLE) {
            searchQuery = ""
            binding.searchEditText.setText("")
            binding.searchBar.visibility = View.GONE
            binding.btnSelectAllMatches.visibility = View.GONE
            applyFilter()
        } else {
            binding.searchBar.visibility = View.VISIBLE
            binding.searchEditText.requestFocus()
        }
    }

    /**
     * Submits the filtered (or full) channel list to the adapter based on [searchQuery].
     * Matches are case-insensitive on channel name, RX frequency (MHz), or any resolved
     * group label.
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
            // Match RX frequency displayed as MHz string (e.g. "462.5625")
            if (ch.displayFreq().contains(q)) return@filter true
            // Match group letter directly OR its resolved label
            listOf(ch.group1, ch.group2, ch.group3, ch.group4).any { letter ->
                if (letter == "None") return@any false
                if (letter.lowercase().contains(q)) return@any true
                val idx   = EepromConstants.GROUP_LETTERS.indexOf(letter)
                val label = labels.getOrNull(idx)?.trim() ?: ""
                label.lowercase().contains(q)
            }
        }
        adapter.submitList(filtered)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CHIRP CSV export
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Asks the user to name the export file, then generates a CHIRP-compatible CSV
     * from all selected non-empty channels and triggers the Android share sheet.
     */
    private fun exportSelectedChannels() {
        val selected = adapter.selectedChannelNumbers
        val channels = channelList
            .filter { it.number in selected && !it.empty }
            .sortedBy { it.number }

        if (channels.isEmpty()) {
            Toast.makeText(this, "No non-empty channels selected", Toast.LENGTH_SHORT).show()
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
        menu.findItem(R.id.action_import_chirp)?.isEnabled           = hasChannels
        menu.findItem(R.id.action_import_chirp_clipboard)?.isEnabled = hasChannels
        menu.findItem(R.id.action_sort_by_group)?.isEnabled          = hasChannels
        menu.findItem(R.id.action_edit_group_labels)?.isEnabled      = hasChannels
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
            R.id.action_sort_by_group -> {
                startActivity(Intent(this, ChannelSortActivity::class.java))
                true
            }
            R.id.action_edit_group_labels -> {
                startActivity(Intent(this, GroupLabelEditActivity::class.java))
                true
            }
            R.id.action_select_radio -> {
                startActivity(Intent(this, RadioSelectActivity::class.java))
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

    private fun isAnyConnected() = activeStream != null

    private fun disconnectAll() {
        bleManager.disconnect()
        btManager.disconnect()
        activeStream = null
        activeDeviceName = null
    }

    /**
     * Shows the top-level connection picker: BLE scan (primary) or Classic SPP (fallback).
     */
    private fun showConnectPicker() {
        AlertDialog.Builder(this)
            .setTitle("Connect to radio")
            .setItems(arrayOf("Scan for Radio  (BLE)", "Paired Devices  (Classic BT)")) { _, which ->
                when (which) {
                    0 -> startBleScan()
                    1 -> showPairedDevicePicker()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
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
                        activeStream = stream
                        activeDeviceName = bleManager.deviceName()
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
        val connected = isAnyConnected()
        binding.statusText.text = if (connected) {
            getString(R.string.status_connected, activeDeviceName ?: "Radio")
        } else {
            getString(R.string.status_disconnected)
        }
        binding.btnConnect.text =
            if (connected) getString(R.string.disconnect) else getString(R.string.connect)
        binding.btnLoad.isEnabled = connected
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

        // Pre-select the current power of the first non-empty selected channel
        val firstNonEmpty = channels.firstOrNull { it.number in selected && !it.empty }
        val defaultIdx = EepromConstants.POWERLEVEL_LIST
            .indexOf(firstNonEmpty?.power ?: "1")
            .coerceAtLeast(1)           // fallback to "1" watt if not found

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

        // NumberPicker shows the full value string (incl. 3-digit levels) in a
        // scrollable wheel — avoids the truncation that occurs with a Spinner dropdown.
        val picker = android.widget.NumberPicker(this).apply {
            minValue = 0
            maxValue = EepromConstants.POWERLEVEL_LIST.size - 1
            displayedValues = EepromConstants.POWERLEVEL_LIST.toTypedArray()
            value = defaultIdx
            wrapSelectorWheel = false
        }

        // Advisory TextView — hidden until the picker exceeds the effective cap
        val capPx = (12 * resources.displayMetrics.density).toInt()
        val advisoryView = android.widget.TextView(this).apply {
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#E65100"))
            setPadding(capPx, capPx / 2, capPx, 0)
            visibility = android.view.View.GONE
        }

        // Helper to refresh the advisory whenever the picker value changes
        fun refreshAdvisory(pickerPosition: Int) {
            val powerStr = EepromConstants.POWERLEVEL_LIST.getOrNull(pickerPosition) ?: "N/T"
            val raw = powerStr.toIntOrNull() ?: 0
            if (raw > 0 && raw > effectiveCap) {
                val capWatts = EepromConstants.powerToWatts(effectiveCap.toString())
                val bandDesc = when {
                    hasVhf && hasUhf ->
                        if (vhfCap <= uhfCap) "VHF cap ($vhfCap ≈ $capWatts)"
                        else                  "UHF cap ($uhfCap ≈ $capWatts)"
                    hasVhf -> "VHF cap ($vhfCap ≈ $capWatts)"
                    else   -> "UHF cap ($uhfCap ≈ $capWatts)"
                }
                advisoryView.text = "⚠ Exceeds $bandDesc — radio will clamp at TX time"
                advisoryView.visibility = android.view.View.VISIBLE
            } else {
                advisoryView.visibility = android.view.View.GONE
            }
        }

        picker.setOnValueChangedListener { _, _, newVal -> refreshAdvisory(newVal) }
        refreshAdvisory(defaultIdx)   // evaluate immediately for the pre-seeded value

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
        wrapper.addView(
            advisoryView,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        AlertDialog.Builder(this)
            .setTitle("Set TX Power — ${selected.size} selected")
            .setView(wrapper)
            .setPositiveButton("Apply") { _, _ ->
                val powerStr = EepromConstants.POWERLEVEL_LIST
                    .getOrNull(picker.value) ?: "1"
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
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Shows a group-slot picker and applies the chosen assignments to every
     * non-empty selected channel in one operation.
     *
     * Each of the four group slots has its own spinner.  The first item in each
     * spinner is "— Keep —": leaving it there leaves that slot unchanged on all
     * selected channels so the user can update only specific slots.
     *
     * Custom group label names (from [EepromHolder.groupLabels]) are appended to
     * each option so the user can see e.g. "A — GMRS" instead of just "A".
     */
    private fun setGroupsSelected() {
        val eep = eeprom ?: return
        val selected = adapter.selectedChannelNumbers
        if (selected.isEmpty()) return

        val channels = EepromParser.parseAllChannels(eep)

        // Build display labels: "A — Custom Name" when a custom label is set, else "A".
        val customLabels = EepromHolder.groupLabels   // List<String>, may be empty
        val groupDisplayOptions: List<String> = listOf("— Keep —", "None") +
            EepromConstants.GROUP_LETTERS.mapIndexed { i, letter ->
                val custom = customLabels.getOrNull(i)?.takeIf { it.isNotBlank() }
                if (custom != null) "$letter — $custom" else letter
            }
        val displayArray = groupDisplayOptions.toTypedArray()

        // Pre-seed each spinner to "— Keep —" (index 0).
        // Mapping: spinner position p → GROUPS_LIST[p-1] for p≥1; p=0 = no change.
        val slotNames = listOf("Group Slot 1", "Group Slot 2", "Group Slot 3", "Group Slot 4")
        val spinners = List(4) { _ ->
            android.widget.Spinner(this).apply {
                adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    displayArray
                )
                setSelection(0) // "— Keep —"
            }
        }

        // Dialog view: vertical list of label+spinner rows with consistent padding
        val hPx = (20 * resources.displayMetrics.density).toInt()
        val vPx = (6  * resources.displayMetrics.density).toInt()
        val minLabelW = (80 * resources.displayMetrics.density).toInt()

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(hPx, vPx, hPx, vPx)
        }
        slotNames.forEachIndexed { i, name ->
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, vPx, 0, vPx)
            }
            val label = android.widget.TextView(this).apply {
                text = name
                minWidth = minLabelW
            }
            row.addView(label)
            row.addView(
                spinners[i],
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            container.addView(row)
        }

        AlertDialog.Builder(this)
            .setTitle("Set Channel Groups — ${selected.size} selected")
            .setView(container)
            .setPositiveButton("Apply") { _, _ ->
                // Map each spinner position back to a GROUPS_LIST value (or null = keep).
                val newGroups: List<String?> = spinners.map { sp ->
                    val pos = sp.selectedItemPosition
                    if (pos == 0) null else EepromConstants.GROUPS_LIST[pos - 1]
                }

                if (newGroups.all { it == null }) {
                    Toast.makeText(this, "No group slots changed", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                var count = 0
                for (ch in channels) {
                    if (ch.number in selected && !ch.empty) {
                        EepromParser.writeChannel(
                            eep, ch.copy(
                                group1 = newGroups[0] ?: ch.group1,
                                group2 = newGroups[1] ?: ch.group2,
                                group3 = newGroups[2] ?: ch.group3,
                                group4 = newGroups[3] ?: ch.group4
                            )
                        )
                        count++
                    }
                }
                eeprom = eep
                EepromHolder.eeprom = eep
                channelList = EepromParser.parseAllChannels(eep)
                dragWorkList = channelList.toMutableList()
                adapter.submitList(channelList)
                // Keep selection active — user may want to chain other bulk actions

                val changed = newGroups.mapIndexedNotNull { i, v ->
                    if (v != null) "Slot ${i + 1}=$v" else null
                }.joinToString(", ")
                Toast.makeText(
                    this@MainActivity,
                    "Groups ($changed) set on $count channel(s)",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─────────────────────────────────────────────────────────────────────────

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
        binding.progressText.text = getString(R.string.cloning, 0, 100)
        binding.btnLoad.isEnabled = false
        binding.btnSave.isEnabled = false
        lifecycleScope.launch {
            try {
                val channels = ChirpBridge.download(radio, port)
                eeprom = ByteArray(0)   // non-null sentinel
                EepromHolder.eeprom    = eeprom
                EepromHolder.channels  = channels.toMutableList()
                refreshChannelList()
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.progressBar.isIndeterminate = false
                    binding.progressText.visibility = View.GONE
                    updateConnectionUi()
                    invalidateOptionsMenu()
                    Toast.makeText(
                        this@MainActivity,
                        "Downloaded ${channels.size} channels from ${radio.vendor} ${radio.model}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.progressBar.isIndeterminate = false
                    binding.progressText.visibility = View.GONE
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
        binding.progressText.text = "Uploading to radio…"
        binding.btnSave.isEnabled = false
        lifecycleScope.launch {
            try {
                ChirpBridge.upload(radio, port, EepromHolder.channels.toList())
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.progressBar.isIndeterminate = false
                    binding.progressText.visibility = View.GONE
                    updateConnectionUi()
                    Toast.makeText(this@MainActivity, "Saved to radio", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.progressBar.isIndeterminate = false
                    binding.progressText.visibility = View.GONE
                    updateConnectionUi()
                    Toast.makeText(this@MainActivity, "Upload failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
