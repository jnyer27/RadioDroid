package com.radiodroid.app.radio

import java.io.InputStream
import java.io.OutputStream

/**
 * Abstraction for serial I/O used by the radio protocol.
 * Implemented over Bluetooth SPP or a mock for tests.
 */
interface RadioStream {
    val input: InputStream
    val output: OutputStream
    var readTimeoutMs: Int
    fun close()
}
