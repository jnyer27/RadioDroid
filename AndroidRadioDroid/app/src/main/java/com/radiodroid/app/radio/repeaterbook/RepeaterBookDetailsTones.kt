package com.radiodroid.app.radio.repeaterbook

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Loads **Uplink Tone** / **Downlink Tone** from RepeaterBook HTML detail pages and maps them to
 * export-style **PL** (transmit / access) and **TSQ** (receive squelch) for [RepeaterBookToChannelMapper].
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

    internal fun parseGmrsHtml(html: String): TonePair {
        val doc = Jsoup.parse(html, BASE_GMRS)
        var uplink: String? = null
        var downlink: String? = null
        for (tr in doc.select("tr")) {
            val tds = tr.select("td")
            if (tds.size < 2) continue
            val label = tds[0].text().trim().lowercase(Locale.US)
            if (!label.contains("tone")) continue
            val raw = tds[1].text().trim()
            val v = normalizeToneCell(raw) ?: continue
            when {
                label.contains("uplink") -> uplink = v
                label.contains("downlink") -> downlink = v
            }
        }
        return TonePair(uplink, downlink)
    }

    private const val DETAIL_FETCH_THREADS = 6

    fun enrichAmateurRows(client: OkHttpClient, rows: List<JSONObject>) {
        enrichRowsParallel(client, rows) { c, s, r -> fetchAmateurTones(c, s, r) }
    }

    fun enrichGmrsRows(client: OkHttpClient, rows: List<JSONObject>) {
        enrichRowsParallel(client, rows) { c, s, r -> fetchGmrsTones(c, s, r) }
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
        val tones = runCatching { fetch(client, sid, rid) }.getOrElse { TonePair(null, null) }
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

    internal fun normalizeToneCell(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val u = t.uppercase(Locale.US)
        if (t == "—" || t == "–" || t == "-" || t == "\u2014" || t == "\u2013") return null
        if (u == "N/A" || u == "NA") return null
        if (u == "CSQ" || u.contains("CARRIER") && u.contains("SQUELCH")) return null
        if (u == "NONE" || u == "OPEN") return null
        return t
    }
}

