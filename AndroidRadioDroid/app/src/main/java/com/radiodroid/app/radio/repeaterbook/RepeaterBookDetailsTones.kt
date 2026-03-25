package com.radiodroid.app.radio.repeaterbook

import com.radiodroid.app.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Loads **Uplink Tone** / **Downlink Tone** from RepeaterBook HTML detail pages and maps them to
 * export-style **PL** (transmit / access) and **TSQ** (receive squelch) for [RepeaterBookToChannelMapper].
 *
 * GMRS `details.php` often hides tone cells behind login; when [BuildConfig.REPEATERBOOK_APP_TOKEN]
 * is set, [enrichGmrsRows] falls back to [RepeaterBookHttp.fetchRepeaters] (`export.php`, `stype=gmrs`)
 * for the same repeater so PL/TSQ can still be filled.
 */
object RepeaterBookDetailsTones {

    internal const val KEY_STATE_ID = "_rb_state_id"
    internal const val KEY_REPEATER_ID = "_rb_repeater_id"

    private val AMATEUR_DETAILS = "https://www.repeaterbook.com/repeaters/details.php".toHttpUrl()
    private val GMRS_DETAILS = "https://www.repeaterbook.com/gmrs/details.php".toHttpUrl()
    private const val BASE_AMATEUR = "https://www.repeaterbook.com/repeaters/"
    private const val BASE_GMRS = "https://www.repeaterbook.com/gmrs/"

    data class TonePair(val uplink: String?, val downlink: String?)

    @Throws(IOException::class)
    fun fetchAmateurTones(client: OkHttpClient, stateId: String, repeaterId: String): TonePair {
        val url = AMATEUR_DETAILS.newBuilder()
            .addQueryParameter("state_id", stateId)
            .addQueryParameter("ID", repeaterId)
            .build()
        val html = fetchHtml(client, url)
        return parseAmateurHtml(html)
    }

    @Throws(IOException::class)
    fun fetchGmrsTones(client: OkHttpClient, stateId: String, repeaterId: String): TonePair {
        val url = GMRS_DETAILS.newBuilder()
            .addQueryParameter("state_id", stateId)
            .addQueryParameter("ID", repeaterId)
            .build()
        val html = fetchHtml(client, url)
        return parseGmrsHtml(html)
    }

    private fun fetchHtml(client: OkHttpClient, url: okhttp3.HttpUrl): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/html,application/xhtml+xml")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("details HTTP ${response.code}: ${response.message}")
            }
            return body
        }
    }

    internal fun parseAmateurHtml(html: String): TonePair {
        val doc = Jsoup.parse(html, BASE_AMATEUR)
        var uplink: String? = null
        var downlink: String? = null
        for (tr in doc.select("tr")) {
            val th = tr.selectFirst("th") ?: continue
            val td = tr.selectFirst("td") ?: continue
            val label = th.text().trim().lowercase(Locale.US)
            if (!label.contains("tone")) continue
            val raw = td.text().trim()
            val v = normalizeToneCell(raw) ?: continue
            when {
                label.contains("uplink") -> uplink = v
                label.contains("downlink") -> downlink = v
            }
        }
        return TonePair(uplink, downlink)
    }

    /**
     * Parses GMRS `details.php` HTML. Tries **`th` + `td`** rows first (live site layout), then
     * **`td` + `td`** (legacy). Uplink/downlink rows must include both **tone** and **uplink** or
     * **downlink** so labels like "Travel Tone" are ignored. Last matching row wins.
     */
    internal fun parseGmrsHtml(html: String): TonePair {
        val doc = Jsoup.parse(html, BASE_GMRS)
        var uplink: String? = null
        var downlink: String? = null
        for (tr in doc.select("tr")) {
            val labelRaw: String
            val valueEl: Element
            val th = tr.selectFirst("th")
            val tdFirst = tr.selectFirst("td")
            if (th != null && tdFirst != null) {
                labelRaw = th.text()
                valueEl = tdFirst
            } else {
                val tds = tr.select("td")
                if (tds.size < 2) continue
                labelRaw = tds[0].text()
                valueEl = tds[1]
            }
            val label = normalizeGmrsDetailLabel(labelRaw)
            if (!label.contains("tone")) continue
            val raw = valueEl.text().trim()
            val v = normalizeToneCell(raw) ?: continue
            when {
                label.contains("uplink") && label.contains("tone") -> uplink = v
                label.contains("downlink") && label.contains("tone") -> downlink = v
            }
        }
        return TonePair(uplink, downlink)
    }

    /** Lowercase, trim, strip trailing punctuation (e.g. "Uplink Tone:"). */
    internal fun normalizeGmrsDetailLabel(raw: String): String =
        raw.trim().lowercase(Locale.US).trimEnd(':', '.')

    private const val DETAIL_FETCH_THREADS = 6

    /**
     * One cached `export.php` result per (country, state_id) for GMRS when narrow queries miss
     * (cleared at the start of each [enrichGmrsRows] run).
     */
    private val gmrsExportStateListCache = ConcurrentHashMap<String, List<JSONObject>>()

    fun enrichAmateurRows(client: OkHttpClient, rows: List<JSONObject>) {
        enrichRowsParallel(client, rows) { c, s, r -> fetchAmateurTones(c, s, r) }
    }

    fun enrichGmrsRows(client: OkHttpClient, rows: List<JSONObject>) {
        if (rows.isEmpty()) return
        gmrsExportStateListCache.clear()
        val pool = Executors.newFixedThreadPool(DETAIL_FETCH_THREADS)
        try {
            val futures = rows.map { obj ->
                pool.submit {
                    enrichOneGmrsRow(client, obj)
                }
            }
            futures.forEach { runCatching { it.get() } }
        } finally {
            pool.shutdown()
            pool.awaitTermination(120L, TimeUnit.SECONDS)
        }
    }

    private fun enrichOneGmrsRow(client: OkHttpClient, obj: JSONObject) {
        val sid = obj.optString(KEY_STATE_ID, "").ifBlank { stripInternalKeys(obj); return }
        val rid = obj.optString(KEY_REPEATER_ID, "").ifBlank { stripInternalKeys(obj); return }
        val tones = runCatching {
            val htmlPair = fetchGmrsTones(client, sid, rid)
            if (htmlPair.uplink == null && htmlPair.downlink == null) {
                fetchGmrsTonesFromExport(obj, sid, rid) ?: htmlPair
            } else {
                htmlPair
            }
        }.getOrElse {
            TonePair(null, null)
        }
        mergeTones(obj, tones)
        stripInternalKeys(obj)
    }

    private fun enrichRowsParallel(
        client: OkHttpClient,
        rows: List<JSONObject>,
        fetch: (OkHttpClient, String, String) -> TonePair,
    ) {
        if (rows.isEmpty()) return
        val pool = Executors.newFixedThreadPool(DETAIL_FETCH_THREADS)
        try {
            val futures = rows.map { obj ->
                pool.submit {
                    enrichOneRow(client, obj, fetch)
                }
            }
            futures.forEach { runCatching { it.get() } }
        } finally {
            pool.shutdown()
            pool.awaitTermination(120L, TimeUnit.SECONDS)
        }
    }

    private fun enrichOneRow(
        client: OkHttpClient,
        obj: JSONObject,
        fetch: (OkHttpClient, String, String) -> TonePair,
    ) {
        val sid = obj.optString(KEY_STATE_ID, "").ifBlank { stripInternalKeys(obj); return }
        val rid = obj.optString(KEY_REPEATER_ID, "").ifBlank { stripInternalKeys(obj); return }
        val tones = runCatching { fetch(client, sid, rid) }.getOrElse {
            TonePair(null, null)
        }
        mergeTones(obj, tones)
        stripInternalKeys(obj)
    }

    internal fun mergeTones(obj: JSONObject, tones: TonePair) {
        tones.uplink?.let { obj.put("PL", it) }
        tones.downlink?.let { obj.put("TSQ", it) }
    }

    internal fun stripInternalKeys(obj: JSONObject) {
        obj.remove(KEY_STATE_ID)
        obj.remove(KEY_REPEATER_ID)
    }

    private fun JSONObject.optFreqMhzField(key: String): Double? {
        if (!has(key) || JSONObject.NULL == get(key)) return null
        return when (val v = get(key)) {
            is Number -> v.toDouble()
            is String -> v.trim().toDoubleOrNull()
            else -> null
        }
    }

    private fun formatApiFrequencyMhz(mhz: Double): String =
        String.format(Locale.US, "%.5f", mhz).trimEnd('0').trimEnd('.')

    /** Strip leading zeros for all-digit IDs (`06` ≡ `6`, `005` ≡ `5`). Alphanumeric (e.g. `CA01`) unchanged. */
    internal fun normalizeRbNumericIdSegment(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return t
        if (t.all { it.isDigit() }) {
            val s = t.trimStart('0')
            return if (s.isEmpty()) "0" else s
        }
        return t
    }

    private fun sameRbState(a: String, b: String): Boolean {
        if (a.equals(b, ignoreCase = true)) return true
        val x = a.trim()
        val y = b.trim()
        if (x.all { it.isDigit() } && y.all { it.isDigit() }) {
            return normalizeRbNumericIdSegment(x) == normalizeRbNumericIdSegment(y)
        }
        return false
    }

    private fun sameRbRepeaterId(a: String, b: String): Boolean {
        if (a.equals(b, ignoreCase = true)) return true
        val x = a.trim()
        val y = b.trim()
        if (x.all { it.isDigit() } && y.all { it.isDigit() }) {
            return normalizeRbNumericIdSegment(x) == normalizeRbNumericIdSegment(y)
        }
        return false
    }

    /** Normalized `state-repeater` compound for export rows that encode both in one field. */
    internal fun gmrsCompoundRepeaterKey(stateId: String, repeaterId: String): String {
        val s = stateId.trim()
        val r = repeaterId.trim()
        val sn = if (s.all { it.isDigit() }) normalizeRbNumericIdSegment(s) else s
        val rn = if (r.all { it.isDigit() }) normalizeRbNumericIdSegment(r) else r
        return "$sn-$rn"
    }

    /**
     * True if [record] is the same repeater as RepeaterBook `state_id` + `ID` from details URLs.
     *
     * **export.php** rows use separate **[State ID]** and **[Rptr ID]** fields (e.g. `"41"` and `"2"`),
     * not a single compound `"41-2"` in `Rptr ID` — matching only the compound form never hit.
     */
    internal fun gmrsExportRowMatches(
        record: JSONObject,
        stateId: String,
        repeaterId: String,
        freqMhz: Double?,
        callsign: String,
    ): Boolean {
        val sid = stateId.trim()
        val rid = repeaterId.trim()

        var recState = ""
        for (k in listOf("State ID", "State_ID", "state_id")) {
            val v = record.optString(k, "").trim()
            if (v.isNotEmpty()) {
                recState = v
                break
            }
        }
        var recRptrRaw = ""
        for (k in listOf("Rptr ID", "Rptr_ID", "RptrID")) {
            val v = record.optString(k, "").trim()
            if (v.isNotEmpty()) {
                recRptrRaw = v
                break
            }
        }
        if (recState.isNotEmpty() && recRptrRaw.isNotEmpty()) {
            if (sameRbState(recState, sid) && sameRbRepeaterId(recRptrRaw, rid)) return true
        }
        val recRptrCompact = recRptrRaw.replace(Regex("\\s+"), "")
        if (recRptrCompact.isNotEmpty()) {
            if (recRptrCompact.equals(gmrsCompoundRepeaterKey(sid, rid), ignoreCase = true)) return true
            if (recRptrCompact.contains('-')) {
                val prefix = recRptrCompact.substringBeforeLast('-')
                val suffix = recRptrCompact.substringAfterLast('-')
                if (sameRbState(prefix, sid) && sameRbRepeaterId(suffix, rid)) return true
            }
        }
        if (freqMhz != null && callsign.isNotBlank()) {
            val rf = record.optFreqMhzField("Frequency") ?: return false
            val rc = record.optString("Callsign", "").trim()
            if (rc.equals(callsign, ignoreCase = true) && abs(rf - freqMhz) < 0.002) return true
        }
        return false
    }

    /**
     * Fetches PL/TSQ via authenticated [RepeaterBookHttp] JSON export when HTML details omit tones.
     */
    internal fun fetchGmrsTonesFromExport(
        obj: JSONObject,
        stateId: String,
        repeaterId: String,
    ): TonePair? {
        if (BuildConfig.REPEATERBOOK_APP_TOKEN.isBlank()) return null
        val freqMhz = obj.optFreqMhzField("Frequency") ?: return null
        val callsign = obj.optString("Callsign", "").trim()
        val freqQ = formatApiFrequencyMhz(freqMhz)
        val countries = listOf("United States", "Canada", "Mexico")
        val callsignVariants = if (callsign.isNotEmpty()) listOf(callsign, "") else listOf("")
        for (country in countries) {
            for (cs in callsignVariants.distinct()) {
                val query = RepeaterBookQuery(
                    northAmerica = true,
                    country = country,
                    stateId = stateId,
                    stype = "gmrs",
                    frequency = freqQ,
                    callsign = cs,
                )
                val list = try {
                    val json = RepeaterBookHttp.fetchRepeaters(query)
                    RepeaterBookJsonParser.parseResults(json)
                } catch (_: Exception) {
                    continue
                }
                val match = list.firstOrNull {
                    gmrsExportRowMatches(it, stateId, repeaterId, freqMhz, callsign)
                } ?: continue
                val tones = exportRowToTonePair(match)
                if (tones != null) return tones
            }
        }
        val cacheKeyPrefix = "$stateId|"
        for (country in countries) {
            val cacheKey = "$cacheKeyPrefix$country"
            val wideList = gmrsExportStateListCache.computeIfAbsent(cacheKey) {
                try {
                    val json = RepeaterBookHttp.fetchRepeaters(
                        RepeaterBookQuery(
                            northAmerica = true,
                            country = country,
                            stateId = stateId,
                            stype = "gmrs",
                        ),
                    )
                    RepeaterBookJsonParser.parseResults(json)
                } catch (_: Exception) {
                    emptyList()
                }
            }
            val match = wideList.firstOrNull {
                gmrsExportRowMatches(it, stateId, repeaterId, freqMhz, callsign)
            } ?: continue
            val tones = exportRowToTonePair(match)
            if (tones != null) return tones
        }
        return null
    }

    private fun exportRowToTonePair(match: JSONObject): TonePair? {
        val pl = match.optString("PL", "").trim().takeIf { it.isNotEmpty() }
        val tsq = match.optString("TSQ", "").trim().takeIf { it.isNotEmpty() }
        return if (pl != null || tsq != null) TonePair(pl, tsq) else null
    }

    internal fun normalizeToneCell(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val u = t.uppercase(Locale.US)
        if (t == "—" || t == "–" || t == "-" || t == "\u2014" || t == "\u2013") return null
        if (u == "N/A" || u == "NA") return null
        if (u == "CSQ" || u.contains("CARRIER") && u.contains("SQUELCH")) return null
        if (u == "NONE" || u == "OPEN") return null
        if (u.contains("LOG IN TO VIEW") || u.contains("LOGIN TO VIEW")) return null
        if (u.contains("SUBSCRIBE") && u.contains("VIEW")) return null
        return t
    }
}

