package com.radiodroid.app.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
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
import java.util.concurrent.atomic.AtomicReference

/**
 * BLE manager that discovers and connects to the TD-H3 nicFW radio.
 *
 * The radio exposes a Bluetooth LE UART service (UUID 0xff00) with one notify
 * characteristic (data from radio → phone) and one write characteristic (phone → radio).
 * This matches the nicFWRemoteBT reference implementation by nicsure.
 *
 * After connecting, the existing [com.radiodroid.app.radio.Protocol] EEPROM commands
 * (0x45 enter, 0x46 exit, 0x30 read, 0x31 write) are sent over [BleRadioStream] exactly
 * as they would be over a classic serial link — no protocol changes required.
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

    // ─────────────────────────────────────────────────────────────────────────
    // Scanning
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Start a BLE scan. Results arrive on the main thread via [onFound].
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
        // No service-UUID filter — mirrors nicFWRemoteBT (radio may not advertise the UUID)
        scanner.startScan(null, settings, cb)
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
     * Sequence: connectGatt → requestMtu(512) → discoverServices →
     * find FF00 service characteristics → enable notifications → [onResult] callback.
     *
     * [onResult] is always called exactly once, from an arbitrary thread.
     * Wrap with runOnUiThread in the Activity if UI updates are needed.
     */
    fun connect(device: BluetoothDevice, onResult: (Result<RadioStream>) -> Unit) {
        disconnect()
        val stream = BleRadioStream()
        var resultDelivered = false

        fun deliver(r: Result<RadioStream>) {
            if (!resultDelivered) {
                resultDelivered = true
                onResult(r)
            }
        }

        val gattCallback = object : BluetoothGattCallback() {

            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> g.requestMtu(512)
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        stream.close()
                        deliver(Result.failure(IOException("Disconnected (status=$status)")))
                    }
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                stream.chunkSize = (mtu - 3).coerceAtLeast(20)
                g.discoverServices()
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    return deliver(Result.failure(IOException("Service discovery failed (status=$status)")))
                }
                val svc = g.getService(SERVICE_UUID)
                    ?: return deliver(
                        Result.failure(IOException("FF00 service not found — is this a nicFW radio?"))
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
                    return deliver(Result.failure(IOException("Required BLE characteristics not found on FF00 service")))
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
        /** BLE UART service exposed by nicFW radio firmware. */
        val SERVICE_UUID: UUID = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")

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

    fun markReady() { wasReady = true }

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
            g.writeCharacteristic(wc, data, writeType) == 0 // 0 = BluetoothStatusCodes.SUCCESS
        } else {
            wc.value = data
            wc.writeType = writeType
            g.writeCharacteristic(wc)
        }
    }
}
