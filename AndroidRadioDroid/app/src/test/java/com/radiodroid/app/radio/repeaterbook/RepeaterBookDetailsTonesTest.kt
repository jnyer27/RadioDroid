package com.radiodroid.app.radio.repeaterbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RepeaterBookDetailsTonesTest {

    @Test
    fun parseGmrsHtml_thTd_numericTones() {
        val html = """
            <html><body><table>
            <tr><th>Uplink Tone:</th><td>192.8</td></tr>
            <tr><th>Downlink Tone:</th><td>192.8</td></tr>
            </table></body></html>
        """.trimIndent()
        val p = RepeaterBookDetailsTones.parseGmrsHtml(html)
        assertEquals("192.8", p.uplink)
        assertEquals("192.8", p.downlink)
    }

    @Test
    fun parseGmrsHtml_tdTd_legacyTwoColumn() {
        val html = """
            <html><body><table>
            <tr><td>Uplink Tone</td><td>107.2</td></tr>
            <tr><td>Downlink Tone</td><td>107.2</td></tr>
            </table></body></html>
        """.trimIndent()
        val p = RepeaterBookDetailsTones.parseGmrsHtml(html)
        assertEquals("107.2", p.uplink)
        assertEquals("107.2", p.downlink)
    }

    @Test
    fun parseGmrsHtml_loginWall_returnsNullTones() {
        val html = """
            <html><body><table>
            <tr><th>Uplink Tone:</th><td><a href="/login">LOG IN TO VIEW</a></td></tr>
            <tr><th>Downlink Tone:</th><td>LOG IN TO VIEW</td></tr>
            </table></body></html>
        """.trimIndent()
        val p = RepeaterBookDetailsTones.parseGmrsHtml(html)
        assertNull(p.uplink)
        assertNull(p.downlink)
    }

    @Test
    fun parseGmrsHtml_travelTone_ignored() {
        val html = """
            <html><body><table>
            <tr><th>Travel Tone:</th><td>Yes</td></tr>
            <tr><th>Uplink Tone:</th><td>88.5</td></tr>
            </table></body></html>
        """.trimIndent()
        val p = RepeaterBookDetailsTones.parseGmrsHtml(html)
        assertEquals("88.5", p.uplink)
        assertNull(p.downlink)
    }

    @Test
    fun parseGmrsHtml_duplicateUplink_lastWins() {
        val html = """
            <html><body><table>
            <tr><th>Uplink Tone:</th><td>100.0</td></tr>
            <tr><th>Uplink Tone:</th><td>123.0</td></tr>
            </table></body></html>
        """.trimIndent()
        val p = RepeaterBookDetailsTones.parseGmrsHtml(html)
        assertEquals("123.0", p.uplink)
    }

    @Test
    fun normalizeToneCell_rejectsLoginPhrases() {
        assertNull(RepeaterBookDetailsTones.normalizeToneCell("LOG IN TO VIEW"))
        assertNull(RepeaterBookDetailsTones.normalizeToneCell("log in to view"))
        assertNull(RepeaterBookDetailsTones.normalizeToneCell("LOGIN TO VIEW"))
    }

    @Test
    fun normalizeToneCell_dcsPassesThrough() {
        assertEquals("DCS 023", RepeaterBookDetailsTones.normalizeToneCell("DCS 023"))
    }

    @Test
    fun normalizeGmrsDetailLabel_stripsTrailingColon() {
        assertEquals(
            "uplink tone",
            RepeaterBookDetailsTones.normalizeGmrsDetailLabel("Uplink Tone:"),
        )
    }

    @Test
    fun normalizeRbNumericIdSegment_stripsLeadingZeros() {
        assertEquals("6", RepeaterBookDetailsTones.normalizeRbNumericIdSegment("06"))
        assertEquals("48", RepeaterBookDetailsTones.normalizeRbNumericIdSegment("048"))
        assertEquals("123", RepeaterBookDetailsTones.normalizeRbNumericIdSegment("0123"))
    }

    @Test
    fun gmrsCompoundRepeaterKey_normalizesNumericParts() {
        assertEquals("48-5", RepeaterBookDetailsTones.gmrsCompoundRepeaterKey("048", "005"))
    }

    @Test
    fun normalizeRbNumericIdSegment_leavesAlphanumericState() {
        assertEquals("CA01", RepeaterBookDetailsTones.normalizeRbNumericIdSegment("CA01"))
    }
}
