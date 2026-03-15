package com.radiodroid.app.radio

/**
 * CTCSS/DTCS encode/decode matching _decode_tone / _encode_tone (tidradio_h3_nicfw25.py).
 *
 * EEPROM u16 layout (big-endian):
 *   0x0000          → no tone
 *   0x0001–0x0BB8   → CTCSS: value in 0.1 Hz units (1 = 0.1 Hz … 3000 = 300.0 Hz)
 *   bit15 set       → DCS: bit14 = polarity (1=R, 0=N), bits 0–8 = code (decimal)
 *
 * DCS storage convention confirmed from live EEPROM dump (tdh3_tones_20260304_080440.txt):
 *   The radio stores DCS codes as the DECIMAL VALUE of their OCTAL label.
 *   DCS "023" → octal 023 = decimal  19 → stored in bits 0–8 as 19  (0x8013)
 *   DCS "754" → octal 754 = decimal 492 → stored in bits 0–8 as 492 (0x81EC)
 *
 * Therefore:
 *   decode: raw9 (decimal) → raw9.toString(8) → toInt() → CHIRP label  (19 → "23" → 23)
 *   encode: CHIRP label    → .toString()      → toInt(8) → raw9         (23 → "23" →  19)
 */
object ToneCodec {

    fun decode(toneWord: Int): Triple<String?, Double?, String?> {
        val w = toneWord and 0xFFFF
        if (w in 1..3000)
            return Triple("Tone", w / 10.0, null)
        if ((w and 0x8000) != 0) {
            val raw9 = w and 0x01FF           // decimal value stored in EEPROM bits 0–8
            val polarity = if ((w and 0x4000) != 0) "R" else "N"
            if (raw9 in 1..511) {
                // Convert stored decimal back to the CHIRP octal-label integer:
                //   19  → "23"  → 23  (DCS 023)
                //   492 → "754" → 754 (DCS 754)
                val chirpCode = raw9.toString(8).toInt()
                return Triple("DTCS", chirpCode.toDouble(), polarity)
            }
        }
        return Triple(null, null, null)
    }

    fun encodeTone(mode: String?, value: Double?, polarity: String?): Int {
        if (mode == "Tone" && value != null) {
            return (value * 10).toInt().coerceIn(0, 3000)
        }
        if (mode == "DTCS" && value != null) {
            // value is the CHIRP/display code (e.g. 23, 754) whose digits are an octal number.
            // Parse those digits as octal to get the decimal value the radio stores.
            //   23  → "23".toInt(8)  =  19  → 0x8013
            //   754 → "754".toInt(8) = 492  → 0x81EC
            val code = value.toInt().toString().toInt(8)
            var w = 0x8000 or code
            if (polarity == "R" || polarity == "I") w = w or 0x4000
            return w
        }
        return 0
    }
}
