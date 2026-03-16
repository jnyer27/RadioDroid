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
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * Opens a USB serial port (via usb-serial-for-android) and relays bytes
 * through a pair of LocalServerSockets so the Python serial_shim can read,
 * write, and dynamically reconfigure it as if it were a real serial.Serial.
 *
 * Two sockets are created per connection:
 *
 *  "android://radiodroid_usb"       — data channel (raw bytes to/from radio)
 *  "android://radiodroid_usb_ctrl"  — control channel (out-of-band commands)
 *
 * Control protocol (line-oriented, Python → Kotlin):
 *   BAUD:<baud>\n   — reconfigure USB port to <baud> bps, Kotlin replies "OK\n"
 *
 * Why this matters:
 *   Many CHIRP drivers (e.g. th_uv88.py / Retevis RT85) call
 *       radio.pipe.baudrate = 57600
 *       radio.pipe.timeout  = 2
 *   inside _do_ident() before sending ANY data.  Without the control channel,
 *   the USB port stays at the initial baud rate (e.g. 9600) while the driver
 *   tries to communicate at 57600 — resulting in garbled data and the CHIRP
 *   error "not the amount of data we want".
 */
class UsbSerialBridge(private val context: Context) {

    private val socketName     = "radiodroid_usb"
    private val ctrlSocketName = "radiodroid_usb_ctrl"

    private var serverSocket:     LocalServerSocket? = null
    private var ctrlServerSocket: LocalServerSocket? = null
    private var client:     LocalSocket? = null
    private var ctrlClient: LocalSocket? = null
    private var usbPort:    UsbSerialPort? = null

    // ── Public API ────────────────────────────────────────────────────────────

    /** Returns all currently attached USB serial devices. */
    fun listDrivers(): List<UsbSerialDriver> {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return UsbSerialProber.getDefaultProber().findAllDrivers(manager)
    }

    /**
     * Opens [driver] at [baudRate], starts the LocalSocket relay.
     *
     * @return "android://radiodroid_usb" — pass this to [ChirpBridge.download].
     *
     * Note: [baudRate] is the *initial* rate from [RadioInfo.baudRate].  The
     * CHIRP driver will typically override it via radio.pipe.baudrate inside
     * its own sync_in() — the control channel handles that reconfiguration.
     */
    fun openSocketBridge(driver: UsbSerialDriver, baudRate: Int): String {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val connection = manager.openDevice(driver.device)
            ?: error("USB permission not granted for ${driver.device.deviceName}")

        val port = driver.ports[0]
        port.open(connection)
        port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        usbPort = port

        // Create both server sockets before returning so Python can connect
        // to either in any order — connections queue in the backlog.
        serverSocket     = LocalServerSocket(socketName)
        ctrlServerSocket = LocalServerSocket(ctrlSocketName)

        CoroutineScope(Dispatchers.IO).launch {
            // Accept data and control connections concurrently — Python may
            // connect to them in either order; async lets both proceed in parallel.
            //
            // IMPORTANT: wrap the entire accept/await block in try-catch.
            // If close() is called before Python connects, serverSocket.close()
            // unblocks accept() with a SocketException.  That exception propagates
            // through dataDeferred.await() into this launch block.  Without a
            // try-catch here the unstructured CoroutineScope has no exception
            // handler and the exception crashes the app via the default thread
            // uncaught exception handler.
            try {
                val dataDeferred = async { serverSocket!!.accept() }
                val ctrlDeferred = async { ctrlServerSocket!!.accept() }

                val dataSocket = dataDeferred.await()
                val ctrlSocket = ctrlDeferred.await()

                client     = dataSocket
                ctrlClient = ctrlSocket

                startRelay(port, dataSocket, ctrlSocket)
            } catch (_: Exception) {
                // Server socket was closed before Python connected (normal on
                // disconnect or reconnect before sync_in() runs).  Nothing to relay.
            }
        }

        return "android://$socketName"
    }

    // ── Relay ─────────────────────────────────────────────────────────────────

    private fun startRelay(
        port:       UsbSerialPort,
        dataSocket: LocalSocket,
        ctrlSocket: LocalSocket,
    ) {
        val toSocket   = dataSocket.outputStream
        val fromSocket = dataSocket.inputStream

        // USB RX → socket  (radio → Python)
        CoroutineScope(Dispatchers.IO).launch {
            val buf = ByteArray(512)
            while (true) {
                val n = try {
                    // Short read timeout (50 ms) keeps the loop responsive;
                    // returns 0 if nothing arrived — loop continues immediately.
                    port.read(buf, 50)
                } catch (e: Exception) { break }
                if (n > 0) {
                    try {
                        toSocket.write(buf, 0, n)
                        toSocket.flush()
                    } catch (e: Exception) { break }
                }
            }
        }

        // Socket → USB TX  (Python → radio)
        CoroutineScope(Dispatchers.IO).launch {
            val buf = ByteArray(512)
            while (true) {
                val n = try {
                    fromSocket.read(buf)
                } catch (e: Exception) { break }
                if (n < 0) break
                try {
                    port.write(buf.copyOf(n), 500)
                } catch (e: Exception) { break }
            }
        }

        // Control channel  (Python → Kotlin commands)
        // Currently handles: BAUD:<baud>\n
        CoroutineScope(Dispatchers.IO).launch {
            val reader = ctrlSocket.inputStream.bufferedReader()
            val writer = ctrlSocket.outputStream.bufferedWriter()
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    when {
                        line.startsWith("BAUD:") -> {
                            val baud = line.removePrefix("BAUD:").trim().toIntOrNull()
                            if (baud != null && baud > 0) {
                                try {
                                    port.setParameters(
                                        baud, 8,
                                        UsbSerialPort.STOPBITS_1,
                                        UsbSerialPort.PARITY_NONE,
                                    )
                                } catch (e: Exception) {
                                    // Log but continue — reply OK so Python doesn't hang
                                }
                            }
                            // ACK: Python blocks on this until baud is set
                            writer.write("OK\n")
                            writer.flush()
                        }
                        // Future commands: PARITY, STOPBITS, etc.
                    }
                }
            } catch (_: Exception) {
                // Control channel closed (normal on disconnect)
            }
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    fun close() {
        ctrlClient?.close();     ctrlClient     = null
        ctrlServerSocket?.close(); ctrlServerSocket = null
        client?.close();         client         = null
        serverSocket?.close();   serverSocket   = null
        usbPort?.close();        usbPort        = null
    }
}
