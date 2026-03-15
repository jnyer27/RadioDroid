package com.radiodroid.app.bridge

import android.content.Context
import android.hardware.usb.UsbManager
import android.net.LocalServerSocket
import android.net.LocalSocket
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Opens a USB serial port (via usb-serial-for-android) and relays bytes
 * through a LocalServerSocket so the Python serial_shim can read/write it
 * as an "android://radiodroid_usb" socket.
 *
 * This keeps all USB Host API calls in Kotlin and lets CHIRP Python drivers
 * communicate through the same socket interface used by BLE.
 */
class UsbSerialBridge(private val context: Context) {

    private val socketName = "radiodroid_usb"
    private var serverSocket: LocalServerSocket? = null
    private var client: LocalSocket? = null
    private var usbPort: UsbSerialPort? = null

    /** Returns all currently connected USB serial devices. */
    fun listDrivers(): List<UsbSerialDriver> {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return UsbSerialProber.getDefaultProber().findAllDrivers(manager)
    }

    /**
     * Opens [driver] at [baudRate], starts the LocalSocket relay.
     * @return "android://radiodroid_usb" — pass this to ChirpBridge.download()
     */
    fun openSocketBridge(driver: UsbSerialDriver, baudRate: Int): String {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val connection = manager.openDevice(driver.device)
            ?: error("USB permission not granted for ${driver.device.deviceName}")

        val port = driver.ports[0]
        port.open(connection)
        port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        usbPort = port

        serverSocket = LocalServerSocket(socketName)

        CoroutineScope(Dispatchers.IO).launch {
            val client = serverSocket!!.accept()
            this@UsbSerialBridge.client = client
            startRelay(port, client)
        }

        return "android://$socketName"
    }

    private fun startRelay(port: UsbSerialPort, socket: LocalSocket) {
        val toSocket  = socket.outputStream
        val fromSocket = socket.inputStream

        // USB RX → socket (radio → Python)
        CoroutineScope(Dispatchers.IO).launch {
            val buf = ByteArray(512)
            while (true) {
                val n = try {
                    port.read(buf, 200)
                } catch (e: Exception) { break }
                if (n > 0) {
                    toSocket.write(buf, 0, n)
                    toSocket.flush()
                }
            }
        }

        // Socket → USB TX (Python → radio)
        CoroutineScope(Dispatchers.IO).launch {
            val buf = ByteArray(512)
            while (true) {
                val n = try {
                    fromSocket.read(buf)
                } catch (e: Exception) { break }
                if (n < 0) break
                port.write(buf.copyOf(n), 200)
            }
        }
    }

    fun close() {
        client?.close()
        serverSocket?.close()
        usbPort?.close()
    }
}
