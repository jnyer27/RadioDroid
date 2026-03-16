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
        /**
         * Construct a RadioInfo from a Python dict returned by chirp_bridge.
         *
         * NOTE: obj["key"] in Kotlin compiles to PyObject.get(String) which Chaquopy
         * implements as Python getattr() — NOT dict subscript (__getitem__).
         * Python dicts have no "vendor" / "model" attributes, so obj["vendor"] always
         * returns null silently.  Use obj.callAttr("get", "key") to call Python
         * dict.get(key) which is the correct subscript-style lookup.
         */
        fun fromPyObject(obj: PyObject): RadioInfo = RadioInfo(
            vendor   = obj.callAttr("get", "vendor")?.toString() ?: "",
            model    = obj.callAttr("get", "model")?.toString()  ?: "",
            baudRate = obj.callAttr("get", "baud_rate")?.toInt() ?: 9600,
        )
    }
}
