package com.radiodroid.app.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.radiodroid.app.radio.RadioStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Bluetooth Serial Port Profile (SPP) connection for nicFWRemoteBT-style link to TD-H3.
 * Standard SPP UUID; fallback to createRfcommSocket(1) if needed.
 */
class BtSerialManager(private val context: Context) {

    private var socket: BluetoothSocket? = null
    private var currentStream: BtRadioStream? = null
    private var connectedDevice: BluetoothDevice? = null

    val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<BluetoothDevice> {
        val ad = adapter ?: return emptyList()
        return ad.bondedDevices?.toList() ?: emptyList()
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice): Result<RadioStream> {
        disconnect()
        return try {
            val sock = connectSocket(device)
            socket = sock
            connectedDevice = device
            val stream = BtRadioStream(sock, READ_TIMEOUT_MS)
            currentStream = stream
            Result.success(stream)
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectSocket(device: BluetoothDevice): BluetoothSocket {
        try {
            return device.createRfcommSocketToServiceRecord(SPP_UUID).apply { connect() }
        } catch (e: IOException) {
            // Fallback for some devices that don't advertise SPP UUID
            @Suppress("DEPRECATION")
            val fallback = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                .invoke(device, 1) as BluetoothSocket
            fallback.connect()
            return fallback
        }
    }

    fun disconnect() {
        currentStream?.close()
        currentStream = null
        connectedDevice = null
        try {
            socket?.close()
        } catch (_: IOException) { }
        socket = null
    }

    @SuppressLint("MissingPermission")
    fun connectedDeviceName(): String? = connectedDevice?.name

    fun isConnected(): Boolean = socket?.isConnected == true

    fun getStream(): RadioStream? = currentStream

    companion object {
        private const val READ_TIMEOUT_MS = 500
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

private class BtRadioStream(
    private val socket: BluetoothSocket,
    timeoutMs: Int
) : RadioStream {

    override val input: InputStream = socket.inputStream
    override val output: OutputStream = socket.outputStream
    override var readTimeoutMs: Int = timeoutMs

    override fun close() {
        try {
            input.close()
            output.close()
            socket.close()
        } catch (_: IOException) { }
    }
}
