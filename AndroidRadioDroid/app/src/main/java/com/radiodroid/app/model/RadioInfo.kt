package com.radiodroid.app.model

import com.chaquo.python.PyObject

/**
 * Represents a radio model supported by a CHIRP driver.
 */
data class RadioInfo(
    val vendor: String,
    val model: String,
    val baudRate: Int = 9600,
) {
    val displayName: String get() = "$vendor $model"

    companion object {
        fun fromPyObject(obj: PyObject): RadioInfo = RadioInfo(
            vendor   = obj["vendor"]?.toString() ?: "",
            model    = obj["model"]?.toString()  ?: "",
            baudRate = obj["baud_rate"]?.toInt() ?: 9600,
        )
    }
}
