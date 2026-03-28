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

/**
 * Fetches GMRS proximity results from the public HTML page
 * [gmrs/prox_result.php](https://www.repeaterbook.com/gmrs/prox_result.php) and turns each table row
 * into a minimal [JSONObject] compatible with [RepeaterBookToChannelMapper] (export-style field names).
 *
 * The JSON API does not document lat/long radius; this mirrors the website’s own search.
 * Distance units match amateur **prox2**: `Dunit=m` is miles, `Dunit=k` is kilometres (same query convention).
 *
 * **TX frequency:** When the listed RX frequency is in the usual 462–467 MHz GMRS repeater output
 * range, [RepeaterBookToChannelMapper] expects an input frequency; we set **Input Freq = RX + 5 MHz**
 * (common GMRS repeater split). Otherwise input equals RX (simplex-style).
 */
object RepeaterBookGmrsProx {

    private val PROX_PAGE = "https://www.repeaterbook.com/gmrs/prox_result.php".toHttpUrl()
    private const val HTML_BASE = "https://www.repeaterbook.com/gmrs/"

    /**
     * @param distance value interpreted per [miles] (miles if true, else kilometres — matches `Dunit=m` / `k` on the site).
     */
    /**
     * @param enrichFromDetails when true, one GET per repeater to [gmrs/details.php] fills **PL** / **TSQ**;
     * if tones are login-gated in HTML, [RepeaterBookDetailsTones] falls back to JSON **export.php**
     * when [com.radiodroid.app.BuildConfig.REPEATERBOOK_APP_TOKEN] is configured.
     * When false, internal `_rb_state_id` / `_rb_repeater_id` are kept for deferred enrichment.
     */
    @Throws(IOException::class)
    fun fetchRepeaters(
        client: OkHttpClient,
        latDeg: Double,
        longDeg: Double,
        distance: Double,
        miles: Boolean,
        enrichFromDetails: Boolean = true,
    ): List<JSONObject> {
        val url = PROX_PAGE.newBuilder()
            .addQueryParameter("city", "")
            .addQueryParameter("lat", formatCoord(latDeg))
            .addQueryParameter("long", formatCoord(longDeg))
            .addQueryParameter("distance", String.format(Locale.US, "%.2f", distance))
            .addQueryParameter("Dunit", if (miles) "m" else "k")
            .addQueryParameter("call", "")
            .addQueryParameter("status_id", "1")
            .addQueryParameter("use", "%")
            .addQueryParameter("order", "distance, state_id ASC")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/html,application/xhtml+xml")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("GMRS prox HTTP ${response.code}: ${response.message}")
            }
            val rows = parseHtml(body)
            if (enrichFromDetails) {
                RepeaterBookDetailsTones.enrichGmrsRows(client, rows)
            }
            // If false: keep KEY_STATE_ID / KEY_REPEATER_ID for deferred per-row enrichment.
            return rows
        }
    }

    internal fun formatCoord(deg: Double): String =
        String.format(Locale.US, "%.6f", deg).trimEnd('0').trimEnd('.')

    /** Package-visible for unit tests. */
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
        if (tds.size < 7) return null

        val freqA = tds[0].selectFirst("a[href*=details.php]") ?: return null
        val href = freqA.attr("href").ifBlank { return null }
        if (!href.contains("ID=", ignoreCase = true)) return null

        val freqMhz = freqA.text().trim().toDoubleOrNull() ?: return null
        val q = parseDetailsQuery(href) ?: return null

        val callsign = tds.getOrNull(3)?.selectFirst("a")?.text()?.trim().orEmpty()
        val city = tds.getOrNull(4)?.text()?.trim().orEmpty()
        val state = tds.getOrNull(5)?.text()?.trim().orEmpty()
        val use = tds.getOrNull(6)?.text()?.trim().orEmpty()

        val inputMhz = typicalGmrsInputMhz(freqMhz)
        return JSONObject().apply {
            put(RepeaterBookDetailsTones.KEY_STATE_ID, q.first)
            put(RepeaterBookDetailsTones.KEY_REPEATER_ID, q.second)
            put("Frequency", freqMhz)
            put("Input Freq", inputMhz)
            put("Callsign", callsign)
            put("Nearest City", city)
            put("State", state)
            put("Use", use)
            put("Operational Status", "On-air")
            put("FM Analog", "Yes")
            put("DMR", "No")
            put("Notes", "GMRS prox_result state_id=${q.first} ID=${q.second}")
        }
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

    /**
     * Typical US GMRS repeater: listener RX in 462–467 MHz band, transmit +5 MHz (467.x inputs).
     */
    internal fun typicalGmrsInputMhz(rxMhz: Double): Double =
        if (rxMhz >= 462.0 && rxMhz < 467.0) rxMhz + 5.0 else rxMhz
}

