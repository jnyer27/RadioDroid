package com.radiodroid.app.radio.repeaterbook

import com.radiodroid.app.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Query parameters for RepeaterBook JSON export endpoints ([wiki](https://www.repeaterbook.com/wiki/doku.php?id=api)):
 *
 * **export.php** (North America): `callsign`, `city`, `landmark`, `state_id`, `country`, `county`,
 * `frequency`, `mode`, `emcomm`, `stype`.
 *
 * **exportROW.php**: `callsign`, `city`, `landmark`, `country`, `region`, `frequency`, `mode` only
 * (no `state_id`, `county`, `emcomm`, or `stype` in the published API).
 */
data class RepeaterBookQuery(
    val northAmerica: Boolean = true,
    val country: String = "",
    val stateId: String = "",
    val region: String = "",
    val county: String = "",
    val city: String = "",
    val landmark: String = "",
    val callsign: String = "",
    val frequency: String = "",
    /** e.g. analog, DMR — empty = omit */
    val mode: String = "",
    val emcomm: String = "",
    /** e.g. gmrs — empty = omit */
    val stype: String = "",
)

class RepeaterBookApiException(
    val statusCode: Int,
    message: String,
    val errorBody: String? = null,
) : IOException(message)

/**
 * HTTP client for RepeaterBook JSON export. Sets User-Agent and token from [BuildConfig].
 *
 * Auth style is [BuildConfig.REPEATERBOOK_AUTH_MODE] (`local.properties` → `REPEATERBOOK_AUTH_MODE`).
 * Modes include `x_rb_app_token` (canonical `X-RB-App-Token`), `bearer` (`Authorization: Bearer`),
 * and others — see `local.properties` / UserGuide.
 */
object RepeaterBookHttp {

    private const val BASE = "https://www.repeaterbook.com/api"

    fun userAgent(): String {
        val override = BuildConfig.REPEATERBOOK_USER_AGENT.trim()
        if (override.isNotEmpty()) return override
        val email = BuildConfig.REPEATERBOOK_CONTACT_EMAIL.ifBlank { "configure@local.properties" }
        val url = BuildConfig.REPEATERBOOK_APP_URL.trim()
        val ver = BuildConfig.VERSION_NAME
        return if (url.isNotEmpty()) {
            "RadioDroid/$ver (+$url; $email)"
        } else {
            "RadioDroid/$ver ($email)"
        }
    }

    private val authInterceptor = Interceptor { chain ->
        val token = BuildConfig.REPEATERBOOK_APP_TOKEN.trim()
        val mode = BuildConfig.REPEATERBOOK_AUTH_MODE.lowercase(Locale.US)
        val original = chain.request()
        var url = original.url

        if (token.isNotEmpty()) {
            when (mode) {
                "query_key" ->
                    url = url.newBuilder().addQueryParameter("key", token).build()
                "query_token" ->
                    url = url.newBuilder().addQueryParameter("token", token).build()
                "query_api_key" ->
                    url = url.newBuilder().addQueryParameter("api_key", token).build()
            }
        }

        val b = original.newBuilder().url(url).header("User-Agent", userAgent())
        if (token.isNotEmpty() && !mode.startsWith("query_")) {
            when (mode) {
                "x_rb_app_token" -> b.header("X-RB-App-Token", token)
                "raw" -> b.header("Authorization", token)
                "token", "token_prefix" -> b.header("Authorization", "Token $token")
                "x_api_key" -> b.header("X-API-Key", token)
                else -> b.header("Authorization", "Bearer $token")
            }
        }
        chain.proceed(b.build())
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .addInterceptor(authInterceptor)
        .build()

    /** Same stack (User-Agent + RepeaterBook token) for API or related HTTP fetches. */
    fun httpClient(): OkHttpClient = client

    /**
     * Executes GET export with [query]. Retries on HTTP 429 with simple backoff.
     */
    @Throws(IOException::class)
    fun fetchRepeaters(query: RepeaterBookQuery): String {
        val path = if (query.northAmerica) "$BASE/export.php" else "$BASE/exportROW.php"
        val url = path.toHttpUrl().newBuilder().apply {
            fun addIfNonEmpty(name: String, value: String) {
                val v = value.trim()
                if (v.isNotEmpty()) addQueryParameter(name, v)
            }
            /** Wiki sample: `country=United States&country=Canada` — repeat the parameter per value. */
            fun addCountryParams(commaSeparated: String) {
                for (part in commaSeparated.split(',', ';')) {
                    val v = part.trim()
                    if (v.isNotEmpty()) addQueryParameter("country", v)
                }
            }
            addCountryParams(query.country)
            if (query.northAmerica) {
                addIfNonEmpty("state_id", query.stateId)
                addIfNonEmpty("county", query.county)
            } else {
                addIfNonEmpty("region", query.region)
            }
            addIfNonEmpty("city", query.city)
            addIfNonEmpty("landmark", query.landmark)
            addIfNonEmpty("callsign", query.callsign)
            addIfNonEmpty("frequency", query.frequency)
            addIfNonEmpty("mode", query.mode)
            if (query.northAmerica) {
                addIfNonEmpty("emcomm", query.emcomm)
                addIfNonEmpty("stype", query.stype)
            }
        }.build()

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()
        var attempt = 0
        while (attempt < 4) {
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    when {
                        response.code == 429 -> {
                            val waitSec = (response.header("Retry-After")?.toLongOrNull()
                                ?: (2L shl attempt)).coerceIn(1L, 60L)
                            Thread.sleep(TimeUnit.SECONDS.toMillis(waitSec))
                            attempt++
                        }
                        !response.isSuccessful ->
                            throw RepeaterBookApiException(
                                response.code,
                                "HTTP ${response.code}: ${response.message}",
                                body,
                            )
                        else -> return body
                    }
                }
            } catch (e: RepeaterBookApiException) {
                throw e
            } catch (e: IOException) {
                attempt++
                if (attempt >= 4) throw e
                Thread.sleep(min(2000L * attempt, 8000L))
            }
        }
        throw IOException("RepeaterBook: too many retries")
    }
}

object RepeaterBookJsonParser {

    /**
     * Parses JSON body into raw repeater objects. Handles API error payloads.
     */
    fun parseResults(jsonText: String): List<JSONObject> {
        val root = JSONObject(jsonText)
        if (root.has("ok") && !root.optBoolean("ok", true)) {
            val msg = root.optString("message", root.optString("error_code", "API error"))
            throw IOException(msg)
        }
        val results = root.optJSONArray("results") ?: return emptyList()
        return buildList {
            for (i in 0 until results.length()) {
                val o = results.optJSONObject(i) ?: continue
                add(o)
            }
        }
    }
}

