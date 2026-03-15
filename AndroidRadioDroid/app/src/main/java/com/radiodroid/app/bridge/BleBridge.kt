package com.radiodroid.app.bridge

import android.net.LocalServerSocket
import android.net.LocalSocket
import com.radiodroid.app.bluetooth.BleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream

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
 * TODO: Full relay loop with proper lifecycle and cancellation.
 */
class BleBridge(private val bleManager: BleManager) {

    private val socketName = "radiodroid_ble"
    private var serverSocket: LocalServerSocket? = null
    private var client: LocalSocket? = null

    /**
     * Opens a LocalServerSocket and starts the BLE↔socket relay.
     * @return Path string to pass to Python: "ble://radiodroid_ble"
     */
    fun openSocketBridge(): String {
        serverSocket = LocalServerSocket(socketName)
        CoroutineScope(Dispatchers.IO).launch {
            val client = serverSocket!!.accept()
            this@BleBridge.client = client
            startRelay(client.inputStream, client.outputStream)
        }
        return "ble://$socketName"
    }

    private fun startRelay(fromPython: InputStream, toPython: OutputStream) {
        val radioStream = bleManager.getStream() ?: return

        // Python → BLE radio
        CoroutineScope(Dispatchers.IO).launch {
            val buf = ByteArray(512)
            while (true) {
                val n = fromPython.read(buf)
                if (n < 0) break
                radioStream.output.write(buf, 0, n)
                radioStream.output.flush()
            }
        }

        // BLE radio → Python
        CoroutineScope(Dispatchers.IO).launch {
            val buf = ByteArray(512)
            while (true) {
                val n = radioStream.input.read(buf)
                if (n < 0) break
                toPython.write(buf, 0, n)
                toPython.flush()
            }
        }
    }

    fun close() {
        client?.close()
        serverSocket?.close()
    }
}
