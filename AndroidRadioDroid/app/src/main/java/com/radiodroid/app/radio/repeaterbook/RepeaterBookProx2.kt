package com.radiodroid.app.radio.repeaterbook

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.abs

/**
 * Fetches amateur (non-GMRS) proximity results from RepeaterBook’s HTML
 * [repeaters/prox2_result.php](https://www.repeaterbook.com/repeaters/prox2_result.php)
 * (Proximity Search 2.0) and maps rows to export-style [JSONObject] for [RepeaterBookToChannelMapper].
 *
 * Query shape matches the site (e.g. `band[]=4` for 2 m, `mode[]=1` for FM, `status_id=%` for any status).
 * **`Dunit`:** same as GMRS [prox_result.php](https://www.repeaterbook.com/gmrs/prox_result.php): `m` = miles, `k` = km.
 * **TX frequency:** taken from the **Offset** column relative to the listed output frequency:
 * `Input Freq = Frequency + signed_offset_MHz` (e.g. −0.6 MHz → subtract 600 kHz). Em dash / missing offset → simplex.
 *
 * By default, each row is followed by a request to [details.php] so **PL** / **TSQ** match the site’s
 * **Uplink Tone** / **Downlink Tone** fields (see [RepeaterBookDetailsTones]).
 */
object RepeaterBookProx2 {

    private val PROX2_PAGE = "https://www.repeaterbook.com/repeaters/prox2_result.php".toHttpUrl()
    private const val HTML_BASE = "https://www.repeaterbook.com/repeaters/"

    /** RepeaterBook band id for 2 m (matches default proximity UI). */
    const val BAND_10M = "1"
    const val BAND_6M = "2"
    const val BAND_2M = "4"
    const val BAND_125M = "8"
    const val BAND_70CM = "16"
    const val BAND_33CM = "32"
    const val BAND_23CM = "64"

    /** Mode id for FM on prox2 form. */
    const val MODE_FM = "1"
    const val MODE_DMR = "2"
    const val MODE_DSTAR = "4"
    const val MODE_M17 = "8"
    const val MODE_NXDN = "16"
    const val MODE_P25 = "32"
    const val MODE_FUSION = "64"

    /** `status_id` query value: any operational status (matches RepeaterBook prox2 form). */
    const val STATUS_ANY = "%"

    /** Confirmed on-air only. */
    const val STATUS_ON_AIR_CONFIRMED = "1"

    const val FEATURE_ALLSTAR = "1"
    const val FEATURE_AUTOPATCH = "2"
    const val FEATURE_EPOWER = "4"
    const val FEATURE_ECHOLINK = "8"
    const val FEATURE_IRLP = "16"
    const val FEATURE_WIRES_X = "32"
    const val FEATURE_WIDE_AREA = "64"
    const val FEATURE_WX = "128"

    /**
     * @param distance interpreted per [miles] (`Dunit=m` vs `k`).
     * @param bandIds passed as repeated `band[]` (e.g. [BAND_2M]). Omit all only when [freqMhz] is non-blank
     * (RepeaterBook requires at least one band **or** a frequency).
     * @param modeIds passed as repeated `mode[]` (non-empty).
     * @param freqMhz optional single frequency (MHz), prox2 `freq` field.
     * @param featureIds `feature[]` AND semantics on RepeaterBook.
     * @param statusId [STATUS_ANY] or [STATUS_ON_AIR_CONFIRMED].
     * @param includeSimplex when true, adds `include_simplex=1` (same-frequency simplex nodes).
     * @param enrichFromDetails when true, one HTTP GET per repeater to [details.php] fills **PL** / **TSQ**
     * from Uplink / Downlink tone rows (slower but matches the site’s full technical data).
     */
    @Throws(IOException::class)
    fun fetchRepeaters(
        client: OkHttpClient,
        latDeg: Double,
        longDeg: Double,
        distance: Double,
        miles: Boolean,
        bandIds: List<String> = listOf(BAND_2M),
        modeIds: List<String> = listOf(MODE_FM),
        freqMhz: String = "",
        featureIds: List<String> = emptyList(),
        statusId: String = STATUS_ANY,
        includeSimplex: Boolean = false,
        enrichFromDetails: Boolean = true,
    ): List<JSONObject> {
        val url = buildProx2Url(
            latDeg = latDeg,
            longDeg = longDeg,
            distance = distance,
            miles = miles,
            bandIds = bandIds,
            modeIds = modeIds,
            freqMhz = freqMhz,
            featureIds = featureIds,
            statusId = statusId,
            includeSimplex = includeSimplex,
        )

        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/html,application/xhtml+xml")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("prox2_result HTTP ${response.code}: ${response.message}")
            }
            val rows = parseHtml(body)
            if (enrichFromDetails) {
                RepeaterBookDetailsTones.enrichAmateurRows(client, rows)
            } else {
                rows.forEach { RepeaterBookDetailsTones.stripInternalKeys(it) }
            }
            return rows
        }
    }

    internal fun buildProx2Url(
        latDeg: Double,
        longDeg: Double,
        distance: Double,
        miles: Boolean,
        bandIds: List<String>,
        modeIds: List<String>,
        freqMhz: String,
        featureIds: List<String>,
        statusId: String,
        includeSimplex: Boolean,
    ) = PROX2_PAGE.newBuilder()
        .addQueryParameter("city", "")
        .addQueryParameter("lat", RepeaterBookGmrsProx.formatCoord(latDeg))
        .addQueryParameter("long", RepeaterBookGmrsProx.formatCoord(longDeg))
        .addQueryParameter("distance", String.format(Locale.US, "%.2f", distance))
        .addQueryParameter("Dunit", if (miles) "m" else "k")
        .addQueryParameter("freq", freqMhz.trim())
        .addQueryParameter("status_id", statusId)
        .apply {
            for (id in bandIds) {
                addQueryParameter("band[]", id)
            }
            for (id in modeIds) {
                addQueryParameter("mode[]", id)
            }
            for (id in featureIds) {
                addQueryParameter("feature[]", id)
            }
            if (includeSimplex) {
                addQueryParameter("include_simplex", "1")
            }
        }
        .build()

    internal fun parseHtml(html: String): List<JSONObject> {
        val doc = Jsoup.parse(html, HTML_BASE)
        val out = ArrayList<JSONObject>()
        for (tr in doc.select("tr")) {
            val row = parseRow(tr) ?: continue
            out.add(row)
        }
        return out
    }

    private fun parseRow(tr: Element): JSONObject? {
        val tds = tr.select("td")
        val freqTdIdx = tds.indexOfFirst { it.selectFirst("a[href*=details.php]") != null }
        if (freqTdIdx < 0) return null
        if (tds.size < freqTdIdx + 10) return null

        val freqA = tds[freqTdIdx].selectFirst("a[href*=details.php]") ?: return null
        val href = freqA.attr("href").ifBlank { return null }
        if (!href.contains("ID=", ignoreCase = true)) return null

        val freqMhz = freqA.text().trim().toDoubleOrNull() ?: return null
        val q = parseDetailsQuery(href) ?: return null

        val offsetText = tds.getOrNull(freqTdIdx + 1)?.text()?.trim().orEmpty()
        val accessText = tds.getOrNull(freqTdIdx + 2)?.text()?.trim().orEmpty()
        val callsign = tds.getOrNull(freqTdIdx + 3)?.selectFirst("a")?.text()?.trim()
            ?: tds.getOrNull(freqTdIdx + 3)?.text()?.trim().orEmpty()
        val city = tds.getOrNull(freqTdIdx + 4)?.text()?.trim().orEmpty()
        val state = tds.getOrNull(freqTdIdx + 5)?.text()?.trim().orEmpty()
        val use = tds.getOrNull(freqTdIdx + 6)?.text()?.trim().orEmpty()
        val modeText = tds.getOrNull(freqTdIdx + 7)?.text()?.trim().orEmpty()
        val statusText = tds.getOrNull(freqTdIdx + 9)?.text()?.trim()
            ?: tds.lastOrNull()?.text()?.trim().orEmpty()

        val inputMhz = inputMhzFromOffset(freqMhz, offsetText)
        val pl = accessToPl(accessText)
        val opStatus = operationalStatusFromCell(statusText)

        val modeUpper = modeText.uppercase(Locale.US)
        val fmAnalog = modeUpper.contains("FM")
        val dmr = modeUpper.contains("DMR")

        return JSONObject().apply {
            put(RepeaterBookDetailsTones.KEY_STATE_ID, q.first)
            put(RepeaterBookDetailsTones.KEY_REPEATER_ID, q.second)
            put("Frequency", freqMhz)
            put("Input Freq", inputMhz)
            put("Callsign", callsign)
            put("Nearest City", city)
            put("State", state)
            put("Use", use)
            put("Operational Status", opStatus)
            put("FM Analog", if (fmAnalog) "Yes" else "No")
            put("DMR", if (dmr) "Yes" else "No")
            if (pl != null) put("PL", pl)
            put("Notes", "prox2 state_id=${q.first} ID=${q.second} · $modeText")
        }
    }

    internal fun inputMhzFromOffset(rxMhz: Double, offsetText: String): Double {
        val t = offsetText.trim()
        if (t.isEmpty() || isNoOffsetDash(t)) return rxMhz
        val m = Regex("""([+-]?\d+(?:\.\d+)?)\s*MHz""", RegexOption.IGNORE_CASE).find(t)
            ?: return rxMhz
        val delta = m.groupValues[1].toDoubleOrNull() ?: return rxMhz
        val input = rxMhz + delta
        return if (input > 0 && abs(input - rxMhz) > 1e-6) input else rxMhz
    }

    private fun isNoOffsetDash(s: String): Boolean {
        if (s == "—" || s == "–" || s == "-") return true
        if (s == "\u2014" || s == "\u2013") return true
        return false
    }

    private fun accessToPl(access: String): String? {
        val t = access.trim()
        if (t.isEmpty() || isNoOffsetDash(t)) return null
        return t
    }

    internal fun operationalStatusFromCell(cell: String): String {
        val t = cell.trim()
        if (t.contains("\uD83D\uDFE2") || t.contains("🟢")) return "On-air"
        if (t.contains("\uD83D\uDFE1") || t.contains("🟡")) return "Testing/Reduced"
        if (t.contains("\uD83D\uDD34") || t.contains("🔴")) return "Off-air"
        val u = t.uppercase(Locale.US)
        if (u.contains("OFF-AIR") || u.contains("OFF AIR")) return "Off-air"
        if (u.contains("TEST")) return "Testing/Reduced"
        if (u.contains("ON-AIR") || u.contains("ON AIR")) return "On-air"
        if (u.contains("UNKNOWN")) return "Unknown"
        return "On-air"
    }

    private fun parseDetailsQuery(href: String): Pair<String, String>? {
        val raw = href.substringAfter('?', "")
        if (raw.isEmpty()) return null
        val utf8 = StandardCharsets.UTF_8.name()
        var stateId: String? = null
        var id: String? = null
        for (part in raw.split('&')) {
            val idx = part.indexOf('=')
            if (idx <= 0) continue
            val key = URLDecoder.decode(part.substring(0, idx), utf8).lowercase(Locale.US)
            val value = URLDecoder.decode(part.substring(idx + 1), utf8)
            when (key) {
                "state_id" -> stateId = value
                "id" -> id = value
            }
        }
        if (stateId == null || id == null) return null
        return Pair(stateId, id)
    }
}

