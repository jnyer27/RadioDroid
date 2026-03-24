package com.radiodroid.app.radio.repeaterbook.chirp

import com.radiodroid.app.radio.repeaterbook.RepeaterBookQuery

/**
 * Maps CHIRP-style country / state / service UI ([ChirpRepeaterBookData]) to
 * [RepeaterBookQuery] for RepeaterBook’s export.php / exportROW.php endpoints
 * (see https://www.repeaterbook.com/wiki/doku.php?id=api).
 *
 * US numeric `state_id` values align with CHIRP’s [fips.FIPS_STATES](https://github.com/kk7ds/chirp/blob/master/chirp/sources/fips.py).
 * Canada uses `CA##` strings from the same source. Mexico uses FIPS 10-4-style `MX##` codes
 * keyed to the state names shown in the app.
 *
 * **US GMRS + lat/lon/distance** is handled in the app via HTML
 * [prox_result.php](https://www.repeaterbook.com/gmrs/prox_result.php), not export.php.
 */
object ChirpRepeaterBookApiQuery {

    /** US state / territory name → RepeaterBook `state_id` (numeric FIPS, as string). */
    private val usStateIdByName: Map<String, String> = mapOf(
        "Alabama" to "1",
        "Alaska" to "2",
        "Arizona" to "4",
        "Arkansas" to "5",
        "California" to "6",
        "Colorado" to "8",
        "Connecticut" to "9",
        "Delaware" to "10",
        "District of Columbia" to "11",
        "Florida" to "12",
        "Georgia" to "13",
        "Guam" to "66",
        "Hawaii" to "15",
        "Idaho" to "16",
        "Illinois" to "17",
        "Indiana" to "18",
        "Iowa" to "19",
        "Kansas" to "20",
        "Kentucky" to "21",
        "Louisiana" to "22",
        "Maine" to "23",
        "Maryland" to "24",
        "Massachusetts" to "25",
        "Michigan" to "26",
        "Minnesota" to "27",
        "Mississippi" to "28",
        "Missouri" to "29",
        "Montana" to "30",
        "Nebraska" to "31",
        "Nevada" to "32",
        "New Hampshire" to "33",
        "New Jersey" to "34",
        "New Mexico" to "35",
        "New York" to "36",
        "North Carolina" to "37",
        "North Dakota" to "38",
        "Ohio" to "39",
        "Oklahoma" to "40",
        "Oregon" to "41",
        "Pennsylvania" to "42",
        "Puerto Rico" to "72",
        "Rhode Island" to "44",
        "South Carolina" to "45",
        "South Dakota" to "46",
        "Tennessee" to "47",
        "Texas" to "48",
        "Utah" to "49",
        "Vermont" to "50",
        "Virginia" to "51",
        "Virgin Islands" to "78",
        "Washington" to "53",
        "West Virginia" to "54",
        "Wisconsin" to "55",
        "Wyoming" to "56",
    )

    private val canadaStateIdByName: Map<String, String> = mapOf(
        "Alberta" to "CA01",
        "British Columbia" to "CA02",
        "Manitoba" to "CA03",
        "New Brunswick" to "CA04",
        "Newfoundland" to "CA05",
        "Northwest Territories" to "CA13",
        "Nova Scotia" to "CA07",
        "Nunavut" to "CA14",
        "Ontario" to "CA08",
        "Prince Edward Island" to "CA09",
        "Quebec" to "CA10",
        "Saskatchewan" to "CA11",
        "Yukon Territory" to "CA12",
    )

    /** Keys match [ChirpRepeaterBookData.mexicoStates] spellings. */
    private val mexicoStateIdByName: Map<String, String> = mapOf(
        "Aguascalientes" to "MX01",
        "Baja California" to "MX02",
        "Baja California Sur" to "MX03",
        "Campeche" to "MX04",
        "Chiapas" to "MX05",
        "Chihuahua" to "MX06",
        "Coahuila" to "MX07",
        "Colima" to "MX08",
        "Durango" to "MX10",
        "Guanajuato" to "MX11",
        "Guerrero" to "MX12",
        "Hidalgo" to "MX13",
        "Jalisco" to "MX14",
        "Mexico City" to "MX09",
        "Mexico" to "MX15",
        "Michoacán" to "MX16",
        "Morelos" to "MX17",
        "Nayarit" to "MX18",
        "Nuevo Leon" to "MX19",
        "Puebla" to "MX21",
        "Queretaro" to "MX22",
        "Quintana Roo" to "MX23",
        "San Luis Potosi" to "MX24",
        "Sinaloa" to "MX25",
        "Sonora" to "MX26",
        "Tabasco" to "MX27",
        "Tamaulipas" to "MX28",
        "Tlaxcala" to "MX29",
        "Veracruz" to "MX30",
        "Yucatan" to "MX31",
        "Zacatecas" to "MX32",
    )

    fun toRepeaterBookQuery(
        country: String,
        stateUi: String,
        serviceGmrs: Boolean,
    ): RepeaterBookQuery {
        val all = stateUi.equals(ChirpRepeaterBookData.stateAll, ignoreCase = true)

        if (country !in ChirpRepeaterBookData.naCountries) {
            return RepeaterBookQuery(
                northAmerica = false,
                country = country,
                region = if (all) "" else stateUi,
            )
        }

        val stype = if (serviceGmrs && country == "United States") "gmrs" else ""
        val stateId = when {
            all -> ""
            country == "United States" -> usStateIdByName[stateUi].orEmpty()
            country == "Canada" -> canadaStateIdByName[stateUi].orEmpty()
            country == "Mexico" -> mexicoStateIdByName[stateUi].orEmpty()
            else -> ""
        }

        return RepeaterBookQuery(
            northAmerica = true,
            country = country,
            stateId = stateId,
            stype = stype,
        )
    }
}

