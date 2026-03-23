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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Opens a USB serial port (via usb-serial-for-android) and relays bytes
 * through a pair of LocalServerSockets so the Python serial_shim can read,
 * write, and dynamically reconfigure it as if it were a real serial.Serial.
 *
 * Two sockets are created per connection (unique names each [openSocketBridge], same
 * pattern as [BleBridge] — fixed abstract names caused `EADDRINUSE` on quick reconnect):
 *
 *  `android://rdusb_<uuid>`       — data channel (raw bytes to/from radio)
 *  `android://rdusb_<uuid>_ctrl`  — control channel (out-of-band commands)
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
     * @return e.g. `android://rdusb_<uuid>` — pass this to [ChirpBridge.download].
     *
     * Note: [baudRate] is the *initial* rate from [RadioInfo.baudRate].  The
     * CHIRP driver will typically override it via radio.pipe.baudrate inside
     * its own sync_in() — the control channel handles that reconfiguration.
     */
    fun openSocketBridge(driver: UsbSerialDriver, baudRate: Int): String {
        // Stop prior accept loop and release USB/sockets (MainActivity usually calls
        // [close] first; this covers races and guarantees old names are unbound).
        close()

        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val connection = manager.openDevice(driver.device)
            ?: error("USB permission not granted for ${driver.device.deviceName}")

        val port = driver.ports[0]
        port.open(connection)
        port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        usbPort = port

        val base = "rdusb_" + UUID.randomUUID().toString().replace("-", "")
        val dataSocketName = base
        val ctrlName = "${base}_ctrl"

        // Create both server sockets before returning so Python can connect
        // to either in any order — connections queue in the backlog.
        serverSocket     = LocalServerSocket(dataSocketName)
        ctrlServerSocket = LocalServerSocket(ctrlName)

        CoroutineScope(Dispatchers.IO).launch {
            // Accept connections in a loop so Python can reconnect between operations
            // (e.g. download followed immediately by upload without disconnect).
            // Each download/upload opens a fresh AndroidSerial(), does its work,
            // then closes the sockets.  Without the loop, the second connect hangs
            // because nobody calls accept() again after the first relay exits.
            //
            // startRelay() is non-blocking (it launches coroutines and returns), so
            // the loop immediately circles back to accept() and waits for the next
            // Python connection — which is correct; Python will not connect again
            // until the previous close() has returned.
            //
            // IMPORTANT: wrap the entire loop in try-catch.  If close() is called
            // while blocked in accept(), serverSocket.close() unblocks with a
            // SocketException.  Without a catch here the unstructured CoroutineScope
            // has no handler and the exception crashes the app.
            try {
                while (serverSocket != null && ctrlServerSocket != null) {
                    val dataDeferred = async { serverSocket!!.accept() }
                    val ctrlDeferred = async { ctrlServerSocket!!.accept() }

                    val dataSocket = dataDeferred.await()
                    val ctrlSocket = ctrlDeferred.await()

                    client     = dataSocket
                    ctrlClient = ctrlSocket

                    startRelay(port, dataSocket, ctrlSocket)
                    // startRelay returned — Python closed this connection.
                    // Loop back to accept the next one (next download/upload).
                }
            } catch (_: Exception) {
                // Server socket was closed (normal on disconnect or USB unplug).
            }
        }

        return "android://$dataSocketName"
    }

    // ── Relay ─────────────────────────────────────────────────────────────────

    /**
     * Runs the three relay directions (USB RX→socket, socket→USB TX, control)
     * concurrently and **suspends until all three have exited**.
     *
     * This is intentionally a suspend function rather than a fire-and-forget
     * launcher.  Making it blocking lets the accept() loop in openSocketBridge()
     * wait for the previous relay to be fully torn down before accepting the
     * next Python connection.  Without this guarantee, a download immediately
     * followed by an upload races: two relay instances share the USB port
     * simultaneously, each consuming bytes the other needs — causing intermittent
     * protocol errors on upload.
     *
     * coroutineScope {} keeps all three children in the same structured scope:
     *  - It suspends until all three children complete normally (break from loop).
     *  - If the parent coroutine is cancelled (e.g. close() closes the server
     *    socket), cancellation propagates here and tears down all children.
     */
    private suspend fun startRelay(
        port:       UsbSerialPort,
        dataSocket: LocalSocket,
        ctrlSocket: LocalSocket,
    ) {
        val toSocket   = dataSocket.outputStream
        val fromSocket = dataSocket.inputStream

        // Shared stop flag.  Set by Socket→USB TX when Python closes the data
        // socket (fromSocket EOF).  Read by USB RX→socket so it exits the polling
        // loop even when the radio is silent (port.read returning 0 every 50 ms
        // — if n==0 the loop never tries toSocket.write so it never discovers the
        // socket is closed; without this flag coroutineScope would hang forever).
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)

        coroutineScope {
            // USB RX → socket  (radio → Python)
            launch {
                val buf = ByteArray(512)
                while (!stopped.get()) {
                    val n = try {
                        // Short read timeout (50 ms) keeps the loop responsive and
                        // gives stopped.get() a chance to be re-checked quickly.
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
            launch {
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
                // Python closed its end → signal the USB RX coroutine to stop.
                // Without this, USB RX loops calling port.read(50ms) indefinitely
                // when the radio is quiet, stalling coroutineScope forever and
                // preventing the accept() loop from accepting the next operation.
                stopped.set(true)
            }

            // Control channel  (Python → Kotlin commands)
            // Currently handles: BAUD:<baud>\n
            launch {
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
        // All three relay coroutines have exited — safe to accept next connection.
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
