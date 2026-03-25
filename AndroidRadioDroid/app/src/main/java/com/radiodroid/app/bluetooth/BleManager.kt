package com.radiodroid.app.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.radiodroid.app.radio.RadioStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * BLE manager for transparent serial-over-BLE to CHIRP-compatible radios via common
 * Chinese BLE-to-serial dongles (Baofeng, TIDRADIO, HM-10 clones, Nordic UART, etc.).
 *
 * Supports multiple UART service UUIDs; scan filters advertise packets that list one of
 * those services. After connect, the first matching service is used; TX/RX characteristics
 * are resolved by capability (notify/indicate vs write).
 *
 * Default ATT payload is **20 bytes** until MTU negotiation succeeds; many cheap adapters
 * disconnect if 512-byte MTU is requested, so we negotiate a moderate MTU and fall back
 * to 20-byte chunks on failure or timeout.
 *
 * [com.radiodroid.app.radio.Protocol] EEPROM traffic is unchanged vs USB serial.
 */
@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    private val adapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var scanCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var currentStream: BleRadioStream? = null
    private var connectedDevice: BluetoothDevice? = null
    private val seenAddresses = mutableSetOf<String>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Incremented on every [connect] attempt so a delayed [STATE_DISCONNECTED] from a
     * previous GATT (e.g. radio reboot or switching devices) cannot run link-lost logic
     * after a newer connection is already up — that race cleared the new session and
     * caused crashes on reconnect.
     */
    private val connectGeneration = AtomicInteger(0)

    /** Invoked on the main thread when an established link drops unexpectedly (not replaced by a newer [connect]). */
    var onLinkLost: (() -> Unit)? = null

    /** `true` if the device has a default Bluetooth adapter. */
    fun isBluetoothHardwarePresent(): Boolean = adapter != null

    /** `true` if [adapter] exists and is powered on (LE scan and GATT require this). */
    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    // ─────────────────────────────────────────────────────────────────────────
    // Scanning
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Start a BLE scan filtered by [SUPPORTED_UART_SERVICE_UUIDS] so only adapters that
     * advertise a known UART service appear. Results arrive on the main thread via [onFound].
     * Deduplicates by device address so [onFound] is called at most once per device.
     *
     * @param onFound  called for each newly-seen device: (device, rssiDbm)
     * @param onError  called on scan failure with a BLE error code
     */
    fun startScan(onFound: (BluetoothDevice, Int) -> Unit, onError: (Int) -> Unit) {
        val scanner = adapter?.bluetoothLeScanner ?: run { onError(-1); return }
        stopScan()
        seenAddresses.clear()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device
                if (seenAddresses.add(dev.address)) {
                    onFound(dev, result.rssi)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                onError(errorCode)
            }
        }
        scanCallback = cb
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val filters = SUPPORTED_UART_SERVICE_UUIDS.map { uuid ->
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(uuid))
                .build()
        }
        scanner.startScan(filters, settings, cb)
    }

    /** Stop an in-progress BLE scan. Safe to call when not scanning. */
    fun stopScan() {
        scanCallback?.let { adapter?.bluetoothLeScanner?.stopScan(it) }
        scanCallback = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Connecting
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Connect to [device] via BLE GATT.
     *
     * Sequence: connectGatt → default chunk 20 → requestMtu (moderate size) →
     * discoverServices (with timeout fallback if MTU callback never fires) →
     * pick first supported UART service → enable notifications → [onResult] callback.
     *
     * [onResult] is always called exactly once, from an arbitrary thread.
     * Wrap with runOnUiThread in the Activity if UI updates are needed.
     */
    fun connect(device: BluetoothDevice, onResult: (Result<RadioStream>) -> Unit) {
        disconnect()
        // disconnect() already bumped [connectGeneration]; this value identifies this attempt.
        val generation = connectGeneration.get()
        val stream = BleRadioStream()
        var resultDelivered = false

        fun deliver(r: Result<RadioStream>) {
            if (!resultDelivered) {
                resultDelivered = true
                onResult(r)
            }
        }

        val discoverLock = AtomicBoolean(false)
        val mtuFallbackRef = AtomicReference<Runnable?>(null)

        fun cancelMtuFallback() {
            mtuFallbackRef.getAndSet(null)?.let { mainHandler.removeCallbacks(it) }
        }

        val gattCallback = object : BluetoothGattCallback() {

            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        // Safe default: many adapters fail or drop link on aggressive MTU (e.g. 512).
                        stream.chunkSize = DEFAULT_ATT_CHUNK_BYTES
                        discoverLock.set(false)
                        cancelMtuFallback()
                        val fallback = Runnable {
                            if (discoverLock.compareAndSet(false, true)) {
                                stream.chunkSize = DEFAULT_ATT_CHUNK_BYTES
                                g.discoverServices()
                            }
                        }
                        mtuFallbackRef.set(fallback)
                        mainHandler.postDelayed(fallback, MTU_FALLBACK_DELAY_MS)
                        g.requestMtu(PREFERRED_MTU_BYTES)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        cancelMtuFallback()
                        stream.close()
                        deliver(Result.failure(IOException("Disconnected (status=$status)")))
                    }
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                cancelMtuFallback()
                stream.chunkSize = if (status == BluetoothGatt.GATT_SUCCESS) {
                    (mtu - 3).coerceIn(DEFAULT_ATT_CHUNK_BYTES, MAX_CHUNK_BYTES)
                } else {
                    DEFAULT_ATT_CHUNK_BYTES
                }
                if (discoverLock.compareAndSet(false, true)) {
                    g.discoverServices()
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    return deliver(Result.failure(IOException("Service discovery failed (status=$status)")))
                }
                val svc = SUPPORTED_UART_SERVICE_UUIDS.firstNotNullOfOrNull { uuid -> g.getService(uuid) }
                    ?: return deliver(
                        Result.failure(
                            IOException(
                                "No supported UART service found. Expected one of: " +
                                    SUPPORTED_UART_SERVICE_UUIDS.joinToString { it.toString() }
                            )
                        )
                    )

                var notifyChar: BluetoothGattCharacteristic? = null
                var writeChar: BluetoothGattCharacteristic? = null

                for (c in svc.characteristics) {
                    val p = c.properties
                    if (notifyChar == null &&
                        p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                                BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    ) {
                        notifyChar = c
                    }
                    if (writeChar == null &&
                        p and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                    ) {
                        writeChar = c
                    }
                }

                if (notifyChar == null || writeChar == null) {
                    return deliver(
                        Result.failure(
                            IOException(
                                "Required notify + write characteristics not found on service ${svc.uuid}"
                            )
                        )
                    )
                }

                // Prefer WRITE_TYPE_DEFAULT (with ACK) when available
                val noResp = writeChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0
                stream.init(g, writeChar, noResp)

                // Enable notifications on the notify characteristic
                g.setCharacteristicNotification(notifyChar, true)
                val cccd = notifyChar.getDescriptor(CCCD_UUID)
                if (cccd != null) {
                    writeDescriptorCompat(g, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    // No CCCD — notifications may still work; proceed
                    stream.markReady()
                    deliver(Result.success(stream))
                }
            }

            override fun onDescriptorWrite(
                g: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                stream.markReady()
                deliver(Result.success(stream))
            }

            // API < 33
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    stream.feed(characteristic.value ?: return)
                }
            }

            // API 33+
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                stream.feed(value)
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                stream.onWriteDone(status == BluetoothGatt.GATT_SUCCESS)
            }
        }

        connectedDevice = device
        currentStream = stream
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        // Invalidate any pending STATE_DISCONNECTED from the session being torn down so it
        // cannot fire [onLinkLost] or clear state after a new [connect] (or idle disconnect).
        connectGeneration.incrementAndGet()
        currentStream?.close()
        currentStream = null
        connectedDevice = null
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
    }

    fun isConnected(): Boolean = currentStream?.isOpen == true

    fun getStream(): RadioStream? = currentStream

    /** Returns the device name, falling back to its MAC address. */
    fun deviceName(): String? =
        connectedDevice?.let { d -> d.name?.takeIf { it.isNotBlank() } ?: d.address }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun writeDescriptorCompat(g: BluetoothGatt, d: BluetoothGattDescriptor, v: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, v)
        } else {
            d.value = v
            g.writeDescriptor(d)
        }
    }

    companion object {
        /**
         * Known BLE UART / serial bridge service UUIDs (cheap dongles, Nordic UART, nicFW, etc.).
         * Order: try discovery in this order via [firstNotNullOfOrNull].
         */
        val SUPPORTED_UART_SERVICE_UUIDS: List<UUID> = listOf(
            // TI / HM-10 — common Baofeng-clone adapters
            UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"),
            // Nordic Semiconductor UART
            UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
            // Microchip / ISSC
            UUID.fromString("49535343-fe7d-4ae5-8fa9-9fafd205e455"),
            // NICFW / TD-H3
            UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb"),
        )

        @Deprecated("Use SUPPORTED_UART_SERVICE_UUIDS; nicFW service is included in the list.")
        val SERVICE_UUID: UUID = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")

        /** Default ATT payload (MTU 23 → 20 bytes) until negotiation succeeds. */
        private const val DEFAULT_ATT_CHUNK_BYTES = 20

        /**
         * Requested MTU in bytes (not chunk size). Avoids 512 on flaky adapters;
         * stack may negotiate lower; on failure we keep [DEFAULT_ATT_CHUNK_BYTES].
         */
        private const val PREFERRED_MTU_BYTES = 247

        private const val MAX_CHUNK_BYTES = 512

        /** If [onMtuChanged] never fires, discover services with 20-byte chunks. */
        private const val MTU_FALLBACK_DELAY_MS = 800L

        /** Client Characteristic Configuration Descriptor — used to enable notifications. */
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// BleRadioStream — RadioStream over BLE characteristics
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Implements [RadioStream] over two BLE characteristics (notify + write).
 *
 * **Input**: BLE notifications are pushed by [feed] into a [LinkedBlockingQueue].
 * [read] blocks up to [readTimeoutMs] for each read, matching the original
 * BluetoothSocket blocking behaviour expected by [com.radiodroid.app.radio.Protocol].
 *
 * **Output**: Calls to [write] accumulate in an internal buffer; [flush] sends the
 * buffer as one (or more chunked) BLE writes. This preserves EEPROM command framing —
 * Protocol.kt builds a complete command with multiple write() calls then flush().
 */
class BleRadioStream : RadioStream {

    private val rxQueue = LinkedBlockingQueue<Int>(16384)
    private val txBuf = ByteArrayOutputStream()

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var writeNoResponse = false

    private val openFlag = AtomicBoolean(false)
    private val writeLatch = AtomicReference<CountDownLatch?>(null)

    /** Updated by BleManager after MTU negotiation (MTU − 3 bytes). */
    var chunkSize: Int = 20
    @Volatile
    var wasReady: Boolean = false
        private set

    override var readTimeoutMs: Int = 500

    // ── Lifecycle ────────────────────────────────────────────────────────────

    fun init(gatt: BluetoothGatt, writeChar: BluetoothGattCharacteristic, noResponse: Boolean) {
        this.gatt = gatt
        this.writeChar = writeChar
        this.writeNoResponse = noResponse
        openFlag.set(true)
    }

    fun markReady() {
        wasReady = true
    }

    val isOpen: Boolean get() = openFlag.get()

    override fun close() {
        openFlag.set(false)
        rxQueue.clear()
        txBuf.reset()
        // Unblock any thread waiting on a write ACK
        writeLatch.getAndSet(null)?.countDown()
    }

    // ── GATT callbacks (called by BleManager) ────────────────────────────────

    fun feed(data: ByteArray) {
        for (b in data) rxQueue.offer(b.toInt() and 0xFF)
    }

    fun onWriteDone(success: Boolean) {
        writeLatch.get()?.countDown()
    }

    // ── Input stream ─────────────────────────────────────────────────────────

    override val input: InputStream = object : InputStream() {

        override fun read(): Int {
            if (!openFlag.get()) return -1
            return rxQueue.poll(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!openFlag.get()) return -1
            // Block until the first byte arrives (up to readTimeoutMs)
            val first = rxQueue.poll(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return -1
            b[off] = first.toByte()
            var count = 1
            // Drain any bytes already in the queue (up to 5ms wait each)
            while (count < len) {
                val v = rxQueue.poll(5L, TimeUnit.MILLISECONDS) ?: break
                b[off + count++] = v.toByte()
            }
            return count
        }
    }

    // ── Output stream (buffered; flush() sends) ───────────────────────────────

    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) { txBuf.write(b) }
        override fun write(b: ByteArray) { txBuf.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { txBuf.write(b, off, len) }

        override fun flush() {
            val data = txBuf.toByteArray()
            txBuf.reset()
            if (data.isNotEmpty() && openFlag.get()) {
                sendAll(data)
            }
        }
    }

    // ── BLE write implementation ──────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun sendAll(data: ByteArray) {
        val g = gatt ?: throw IOException("GATT not available")
        val wc = writeChar ?: throw IOException("Write characteristic not available")
        var pos = 0
        while (pos < data.size) {
            if (!openFlag.get()) throw IOException("BLE stream closed")
            val end = minOf(pos + chunkSize, data.size)
            val chunk = data.copyOfRange(pos, end)
            if (writeNoResponse) {
                sendChunkNoResp(g, wc, chunk)
                Thread.sleep(10) // small delay so radio UART buffer can drain
            } else {
                sendChunkWithAck(g, wc, chunk)
            }
            pos = end
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendChunkWithAck(g: BluetoothGatt, wc: BluetoothGattCharacteristic, chunk: ByteArray) {
        val latch = CountDownLatch(1)
        writeLatch.set(latch)
        val ok = writeCharCompat(g, wc, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        if (!ok) {
            writeLatch.set(null)
            throw IOException("BLE write failed")
        }
        if (!latch.await(500L, TimeUnit.MILLISECONDS)) {
            writeLatch.set(null)
            throw IOException("BLE write ACK timeout")
        }
        writeLatch.set(null)
    }

    @SuppressLint("MissingPermission")
    private fun sendChunkNoResp(g: BluetoothGatt, wc: BluetoothGattCharacteristic, chunk: ByteArray) {
        if (!writeCharCompat(g, wc, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)) {
            throw IOException("BLE write (no-response) failed")
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun writeCharCompat(
        g: BluetoothGatt,
        wc: BluetoothGattCharacteristic,
        data: ByteArray,
        writeType: Int
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(wc, data, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            wc.value = data
            wc.writeType = writeType
            g.writeCharacteristic(wc)
        }
    }
}
