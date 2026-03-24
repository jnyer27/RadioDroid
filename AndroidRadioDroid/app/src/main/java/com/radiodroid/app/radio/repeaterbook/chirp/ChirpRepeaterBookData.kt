package com.radiodroid.app.radio.repeaterbook.chirp

/**
 * Country / state lists aligned with CHIRP’s RepeaterBook source
 * ([repeaterbook.py](https://github.com/kk7ds/chirp/blob/master/chirp/sources/repeaterbook.py)),
 * using US/Canada names from CHIRP’s [fips.py](https://github.com/kk7ds/chirp/blob/master/chirp/sources/fips.py).
 */
object ChirpRepeaterBookData {

    val naCountries = listOf("United States", "Canada", "Mexico")

    /** US states / territories where CHIRP’s FIPS map uses a numeric code (sorted for display). */
    val unitedStatesStates: List<String> = listOf(
        "Alabama", "Alaska", "Arizona", "Arkansas", "California", "Colorado",
        "Connecticut", "Delaware", "District of Columbia", "Florida", "Georgia", "Guam",
        "Hawaii", "Idaho", "Illinois", "Indiana", "Iowa", "Kansas", "Kentucky",
        "Louisiana", "Maine", "Maryland", "Massachusetts", "Michigan", "Minnesota",
        "Mississippi", "Missouri", "Montana", "Nebraska", "Nevada", "New Hampshire",
        "New Jersey", "New Mexico", "New York", "North Carolina", "North Dakota",
        "Ohio", "Oklahoma", "Oregon", "Pennsylvania", "Puerto Rico", "Rhode Island",
        "South Carolina", "South Dakota", "Tennessee", "Texas", "Utah", "Vermont",
        "Virgin Islands", "Virginia", "Washington", "West Virginia", "Wisconsin", "Wyoming",
    ).sorted()

    val canadaProvinces: List<String> = listOf(
        "Alberta", "British Columbia", "Manitoba", "New Brunswick", "Newfoundland",
        "Northwest Territories", "Nova Scotia", "Nunavut", "Ontario", "Prince Edward Island",
        "Quebec", "Saskatchewan", "Yukon Territory",
    ).sorted()

    val mexicoStates: List<String> = listOf(
        "Aguascalientes", "Baja California Sur", "Baja California",
        "Campeche", "Chiapas", "Chihuahua", "Coahuila", "Colima",
        "Durango", "Guanajuato", "Guerrero", "Hidalgo", "Jalisco",
        "Mexico City", "Mexico", "Michoacán", "Morelos", "Nayarit",
        "Nuevo Leon", "Puebla", "Queretaro", "Quintana Roo", "San Luis Potosi",
        "Sinaloa", "Sonora", "Tabasco", "Tamaulipas", "Tlaxcala", "Veracruz",
        "Yucatan", "Zacatecas",
    ).sorted()

    val rowCountries: List<String> = listOf(
        "Albania", "Andorra", "Argentina", "Australia", "Austria", "Azerbaijan",
        "Bahamas", "Barbados", "Belarus", "Belgium", "Belize", "Bolivia",
        "Bosnia and Herzegovina", "Brazil", "Bulgaria", "Caribbean Netherlands",
        "Cayman Islands", "Chile", "China", "Colombia", "Costa Rica", "Croatia",
        "Curacao", "Cyprus", "Czech Republic", "Denmark", "Dominican Republic",
        "Ecuador", "El Salvador", "Estonia", "Faroe Islands", "Finland", "France",
        "Georgia", "Germany", "Greece", "Grenada", "Guatemala", "Guernsey",
        "Haiti", "Honduras", "Hungary", "Iceland", "India", "Indonesia",
        "Ireland", "Isle of Man", "Israel", "Italy", "Jamaica", "Japan", "Jersey",
        "Kosovo", "Kuwait", "Latvia", "Liechtenstein", "Lithuania", "Luxembourg",
        "Malaysia", "Malta", "Moldova", "Morocco", "Namibia", "Nepal",
        "Netherlands", "New Zealand", "Nicaragua", "North Macedonia", "Norway", "Oman",
        "Panama", "Paraguay", "Peru", "Philippines", "Poland", "Portugal",
        "Romania", "Russia", "Saint Kitts and Nevis", "Saint Vincent and the Grenadines",
        "San Marino", "Serbia", "Singapore", "Slovakia", "Slovenia", "South Africa",
        "South Korea", "Spain", "Sri Lanka", "Sweden", "Switzerland", "Taiwan",
        "Thailand", "Trinidad and Tobago", "Turkey", "Ukraine", "United Arab Emirates",
        "United Kingdom", "Uruguay", "Venezuela",
    ).sorted()

    val allCountries: List<String> = (naCountries + rowCountries).distinct().sorted()

    const val stateAll = "All"

    fun statesForCountry(country: String): List<String> {
        val states = when (country) {
            "United States" -> unitedStatesStates
            "Canada" -> canadaProvinces
            "Mexico" -> mexicoStates
            else -> return listOf(stateAll)
        }
        return listOf(stateAll) + states
    }
}

