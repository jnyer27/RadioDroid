package com.radiodroid.app.radio.repeaterbook.chirp

import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Client-side filtering / sorting modeled on CHIRP’s [RepeaterBook.do_fetch]
 * ([repeaterbook.py](https://github.com/kk7ds/chirp/blob/master/chirp/sources/repeaterbook.py)).
 */
data class ChirpRbClientParams(
    val lat: Double,
    val lon: Double,
    val distKm: Double,
    val filterText: String,
    val openOnly: Boolean,
)

object ChirpRepeaterBookFilters {

    private const val EARTH_RADIUS_KM = 6371.0

    /** HTML/API rows we still import (matches CHIRP-style usefulness; includes reduced/testing). */
    private val usableOperationalStatus = setOf("On-air", "Testing/Reduced")

    /** Haversine distance in km — same formula as CHIRP’s `distance()`. */
    fun distanceKm(latA: Double, lonA: Double, latB: Double, lonB: Double): Double {
        val rLatA = Math.toRadians(latA)
        val rLonA = Math.toRadians(lonA)
        val rLatB = Math.toRadians(latB)
        val rLonB = Math.toRadians(lonB)
        val dLon = rLonB - rLonA
        val dLat = rLatB - rLatA
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(rLatA) * cos(rLatB) * sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_KM * c
    }

    private fun JSONObject.optCoord(key: String): Double? {
        if (!has(key)) return null
        val raw = optString(key, "").ifBlank { opt(key)?.toString().orEmpty() }
        return raw.toDoubleOrNull()
    }

    private fun chirpLocationMatch(item: JSONObject, needle: String): Boolean {
        val fields = listOf("County", "State", "Landmark", "Nearest City", "Callsign", "Region", "Notes")
        val content = buildString {
            for (k in fields) {
                if (!item.has(k)) continue
                if (isNotEmpty()) append(' ')
                append(item.optString(k, ""))
            }
        }
        return content.lowercase().contains(needle)
    }

    fun apply(items: List<JSONObject>, p: ChirpRbClientParams): List<JSONObject> {
        var list = items.filter { it.optString("Operational Status", "") in usableOperationalStatus }
        if (p.openOnly) {
            list = list.filter { it.optString("Use", "") == "OPEN" }
        }
        val needle = p.filterText.trim()
        if (needle.isNotEmpty()) {
            val n = needle.lowercase()
            list = list.filter { chirpLocationMatch(it, n) }
        }
        val hasCoords = p.lat != 0.0 || p.lon != 0.0
        if (hasCoords && p.distKm > 0) {
            list = list.filter { item ->
                val la = item.optCoord("Lat") ?: return@filter false
                val lo = item.optCoord("Long") ?: return@filter false
                distanceKm(p.lat, p.lon, la, lo) <= p.distKm
            }
        }
        if (hasCoords) {
            list = list.sortedBy { item ->
                val la = item.optCoord("Lat")
                val lo = item.optCoord("Long")
                if (la == null || lo == null) Double.MAX_VALUE
                else distanceKm(p.lat, p.lon, la, lo)
            }
        }
        return list
    }
}

