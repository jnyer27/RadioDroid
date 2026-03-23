package com.radiodroid.app.bridge

import android.net.LocalServerSocket
import android.net.LocalSocket
import com.radiodroid.app.bluetooth.BleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Bridges BLE I/O (from BleManager) to a LocalServerSocket so that
 * the Python serial shim can connect to it as if it were a serial port.
 *
 * Flow:
 *   BleManager (Kotlin, BLE GATT) ↔ LocalServerSocket ↔ AndroidSerial (Python)
 *
 * The Python side connects to the socket path returned by [openSocketBridge].
 * Bytes written by CHIRP driver → AndroidSerial.write() → socket → BleManager.write()
 * Bytes from radio → BleManager notification → socket → AndroidSerial.read() → CHIRP driver
 *
 * Accept loop: each download/settings/upload opens a new AndroidSerial(), does its work,
 * then closes. We accept() in a loop so the next Python connection gets a fresh relay
 * without the user having to disconnect and reconnect BLE.
 *
 * Each [BleBridge] uses a **unique** abstract LocalSocket name. A fixed name caused
 * `IOException: Address already in use` on BLE reconnect: the previous
 * [LocalServerSocket] was not always released before the next bind.
 */
class BleBridge(private val bleManager: BleManager) {

    /** Unique per bridge instance — `ble://` + this name is passed to Python [AndroidSerial]. */
    private val socketName = "rdble_" + UUID.randomUUID().toString().replace("-", "")
    private var serverSocket: LocalServerSocket? = null
    private var client: LocalSocket? = null

    /**
     * Opens a LocalServerSocket and starts the BLE↔socket relay.
     * Accepts connections in a loop so Python can reconnect between operations
     * (e.g. download then settings or upload) without disconnecting BLE.
     * @return Path string to pass to Python, e.g. `ble://rdble_<uuid>`
     */
    fun openSocketBridge(): String {
        serverSocket = LocalServerSocket(socketName)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                while (serverSocket != null) {
                    val accepted = serverSocket!!.accept()
                    client = accepted
                    startRelay(accepted.inputStream, accepted.outputStream)
                    // Previous client closed; loop to accept next (e.g. settings or upload)
                    client = null
                }
            } catch (_: Exception) {
                // Server socket closed (normal on disconnect)
            }
        }
        return "ble://$socketName"
    }

    private fun startRelay(fromPython: InputStream, toPython: OutputStream) {
        val radioStream = bleManager.getStream() ?: return

        // Python → BLE radio
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val buf = ByteArray(512)
                while (true) {
                    val n = fromPython.read(buf)
                    if (n < 0) break
                    radioStream.output.write(buf, 0, n)
                    radioStream.output.flush()
                }
            } catch (_: Exception) {
                // BLE disconnected or write failed — relay ends silently
            }
        }

        // BLE radio → Python
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val buf = ByteArray(512)
                while (true) {
                    val n = radioStream.input.read(buf)
                    if (n < 0) break
                    toPython.write(buf, 0, n)
                    toPython.flush()
                }
            } catch (_: Exception) {
                // BLE disconnected or read failed — relay ends silently
            }
        }
    }

    fun close() {
        client?.close()
        client = null
        serverSocket?.close()
        serverSocket = null
    }
}
