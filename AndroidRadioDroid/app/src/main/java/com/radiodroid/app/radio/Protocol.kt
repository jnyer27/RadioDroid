package com.radiodroid.app.radio

/**
 * Protocol stub for RadioDroid.
 *
 * The nicFW project used a binary radio protocol over BLE/SPP to read/write
 * a fixed-size EEPROM image.  RadioDroid replaces this with CHIRP drivers
 * via ChirpBridge (Python → Chaquopy), so no real binary protocol is used.
 *
 * Constants and stubs are retained so legacy call-sites in copies of nicFW
 * source files continue to compile without large-scale rewrites.
 */
object Protocol {

    /**
     * Nominal EEPROM size — set to 0 so legacy `eep.size < EEPROM_SIZE` guards
     * always evaluate false (any non-null ByteArray passes the check).
     */
    const val EEPROM_SIZE: Int = 0

    /**
     * Offset of the tune-settings block inside the nicFW EEPROM.
     * Retained as a compile-time constant; never dereferenced in RadioDroid.
     */
    const val TUNE_SETTINGS_BASE: Int = 0x1DFB

    /**
     * Stub download — RadioDroid uses ChirpBridge.download() instead.
     * Throws at runtime so any accidental call surfaces immediately.
     */
    fun download(
        @Suppress("UNUSED_PARAMETER") stream: RadioStream,
        @Suppress("UNUSED_PARAMETER") progress: (Int, Int) -> Unit = { _, _ -> }
    ): ByteArray = throw NotImplementedError(
        "Protocol.download() is a nicFW stub — use ChirpBridge.download() in RadioDroid"
    )

    /**
     * Stub upload — RadioDroid uses ChirpBridge.upload() instead.
     */
    fun upload(
        @Suppress("UNUSED_PARAMETER") stream: RadioStream,
        @Suppress("UNUSED_PARAMETER") data: ByteArray,
        @Suppress("UNUSED_PARAMETER") progress: (Int, Int) -> Unit = { _, _ -> }
    ): Unit = throw NotImplementedError(
        "Protocol.upload() is a nicFW stub — use ChirpBridge.upload() in RadioDroid"
    )
}
